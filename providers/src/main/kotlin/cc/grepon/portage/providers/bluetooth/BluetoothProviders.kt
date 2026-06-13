/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.bluetooth

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProvider
import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.providers.wire.JsonLines
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * One bonded Bluetooth device on the wire (PRP-07, public-API approach). Names + addresses +
 * class ONLY — link keys / bond secrets are NEVER carried (controller-bound, non-transferable;
 * re-pairing is unavoidable). The class fields are opaque integer HINTS for an icon/label, never
 * interpreted as a destination.
 *
 *  - [address]  BD_ADDR "AA:BB:CC:DD:EE:FF" — validated by [RePairEntry.from] on apply.
 *  - [name]     display label from the bonded device; display-only, sanitized before it reaches UI.
 *  - [devType]  BluetoothDevice.getType(): 1=CLASSIC, 2=LE, 3=DUAL, 0=UNKNOWN — drives an assist hint.
 *  - [majorClass] the BluetoothClass major-device-class int — an icon hint only.
 *
 * Privacy: a bonded-device list (names + MACs) is mildly privacy-relevant — stable identifiers
 * that fingerprint the user's device environment (THREAT_MODEL "Bluetooth roster"). It travels
 * ONLY inside the authenticated Noise channel to the user's own device; it MUST NOT be logged.
 */
@Serializable
data class BondedDevice(
    val address: String,
    val name: String,
    val devType: Int,
    val majorClass: Int,
)

/** The bonded roster as a single JSON document. */
@Serializable
data class BtPairingRoster(val devices: List<BondedDevice>)

/** JSON (de)serialization for the roster snapshot — separated so tests can frame payloads. */
object BtRosterCodec {
    fun encode(roster: BtPairingRoster): String =
        JsonLines.format.encodeToString(BtPairingRoster.serializer(), roster)

    fun decode(source: InputStream): BtPairingRoster? = runCatching {
        JsonLines.format.decodeFromString(
            BtPairingRoster.serializer(),
            source.bufferedReader(Charsets.UTF_8).readText(),
        )
    }.getOrNull()
}

/**
 * The `BluetoothAdapter` boundary, mirroring SoundStore / InventorySource. Reading the bonded
 * roster uses the PUBLIC `BluetoothAdapter.getBondedDevices()` API guarded by the normal
 * `BLUETOOTH_CONNECT` runtime permission — Tier 0, NO ADB bridge, NO privilege escalation. The
 * seam keeps the providers JVM-testable with a fake; the Android adapter lives in
 * [AndroidBluetoothStore].
 */
interface BluetoothStore {
    /** Whether the roster can be read now: an adapter exists, it is ON, and the grant is held. */
    fun isReadable(): Boolean

    /** This device's bonded devices. Returns empty (or throws SecurityException) when not readable. */
    fun bondedDevices(): List<BondedDevice>
}

/**
 * Sender side: snapshot the bonded-device roster (PRP-07 Phase 1). Reads degrade to "nothing to
 * send" on any failure — Bluetooth off, no `BLUETOOTH_CONNECT` grant, or a thrown SecurityException
 * all yield an empty payload and an absent manifest item, never an exception (the Tier-0 graceful-
 * degrade contract, Providers.kt). The roster is names + MACs + class only; no secrets, no logging.
 */
class BtPairingsExportProvider(
    private val store: BluetoothStore,
) : ExportProvider {

    override val kind = ItemKind.BLUETOOTH_DEVICES
    override val displayName = "Paired Bluetooth devices"
    override val group = "Bluetooth"

    private fun snapshot(): BtPairingRoster {
        if (!runCatching { store.isReadable() }.getOrDefault(false)) return BtPairingRoster(emptyList())
        val devices = runCatching { store.bondedDevices() }.getOrDefault(emptyList())
        return BtPairingRoster(devices)
    }

    override suspend fun available(): Boolean =
        runCatching { snapshot().devices.isNotEmpty() }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val roster = runCatching { snapshot() }.getOrNull() ?: return
        if (roster.devices.isEmpty()) return
        val writer = sink.bufferedWriter(Charsets.UTF_8)
        writer.write(BtRosterCodec.encode(roster))
        writer.flush()
    }
}

/**
 * A validated re-pair checklist entry: one device the user was paired to, surfaced as "re-pair this
 * here". Phase 1 NEVER bonds — it stores and presents the list; assisted `createBond` is deferred to
 * Phase 2. Mirrors InventoryProviders' [cc.grepon.portage.providers.inventory.InstallAction]: a
 * sender-supplied address that is not a well-formed MAC is DROPPED (a malformed MAC could smuggle
 * text into a UI label or a future intent extra — same HIGH-severity reasoning as the inventory
 * deep-link review), and the display name is sanitized (control chars stripped, length-capped)
 * because it is shown verbatim.
 */
data class RePairEntry(
    val address: String,
    val name: String,
    val devType: Int,
    val majorClass: Int,
) {
    companion object {
        /** Display-name cap: long enough for any real device label, short enough to bound a row. */
        const val MAX_NAME_LENGTH = 80

        /** Canonical BD_ADDR grammar: six colon-separated hex octets, nothing else. */
        private val MAC = Regex("""^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$""")

        /**
         * Build a validated entry for one bonded device, or null to DROP it: the address must be a
         * well-formed MAC and the (sanitized) name must be non-blank. Everything the MAC regex
         * accepts is inert by construction — no scheme/query can hide in it.
         */
        fun from(device: BondedDevice): RePairEntry? {
            if (!MAC.matches(device.address)) return null
            val name = sanitizeName(device.name)
            if (name.isBlank()) return null
            return RePairEntry(device.address, name, device.devType, device.majorClass)
        }

        /** Strip control characters (incl. newlines/tabs) and cap the length for safe display. */
        private fun sanitizeName(raw: String): String =
            raw.filter { !it.isISOControl() }.trim().take(MAX_NAME_LENGTH)
    }
}

/**
 * Receiver side: parse the roster, validate + sanitize each entry, dedupe by address, bound the
 * count, and hand the surviving [RePairEntry]s to the UI via [onEntries]. Applying never bonds
 * anything — Phase 1 produces the "you were paired to these — re-pair each here" checklist the user
 * works through (assisted `createBond` is deferred to Phase 2). Idempotent and side-effect-light:
 * the only effect is the callback. Mirrors [cc.grepon.portage.providers.inventory.AppInventoryApplyProvider].
 */
class BtPairingsApplyProvider(
    private val onEntries: (List<RePairEntry>) -> Unit,
) : ApplyProvider {

    override val kind = ItemKind.BLUETOOTH_DEVICES

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val roster = BtRosterCodec.decode(source)
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable bluetooth roster")

        val valid = roster.devices.mapNotNull(RePairEntry::from)
        // Dedupe by address: a hostile or duplicated roster must not reach the checklist's
        // LazyColumn keys twice (Compose throws on duplicate keys) — same guard as the inventory
        // reinstall list. `dropped` counts only invalid entries (bad MAC / blank name), not dupes.
        val deduped = valid.distinctBy { it.address }
        // Bound the count so a pathological roster can't produce an unbounded UI list.
        val entries = deduped.take(MAX_ENTRIES)
        val dropped = roster.devices.size - valid.size
        onEntries(entries)
        val detail = buildString {
            append("${entries.size} to re-pair")
            if (dropped > 0) append(", $dropped dropped (invalid entry)")
        }
        return ApplyOutcome(ItemStatus.OK, detail)
    }

    companion object {
        /** Upper bound on surfaced entries — far above any real bonded-device count. */
        const val MAX_ENTRIES = 256
    }
}
