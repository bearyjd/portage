/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.settings

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProvider
import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.providers.wire.JsonLines
import cc.grepon.portage.settings.Classification
import cc.grepon.portage.settings.Namespace
import cc.grepon.portage.settings.Reach
import cc.grepon.portage.settings.SettingKey
import cc.grepon.portage.settings.SettingsAllowlist
import cc.grepon.portage.settings.accepts
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * One settings key/value pair on the wire. Name+value only — the namespace is NEVER carried;
 * the receiver derives it from the compiled allowlist match ([SettingsAllowlist.byName]) so a
 * payload can never steer a write into the wrong provider table (THREAT_MODEL.md).
 */
@Serializable
data class SettingEntry(val name: String, val value: String)

@Serializable
data class SettingsSnapshot(val entries: List<SettingEntry>)

/** JSON (de)serialization for the snapshot — kept separate so tests can frame payloads. */
object SettingsCodec {
    fun encode(snapshot: SettingsSnapshot): String =
        JsonLines.format.encodeToString(SettingsSnapshot.serializer(), snapshot)

    fun decode(source: InputStream): SettingsSnapshot? = runCatching {
        JsonLines.format.decodeFromString(
            SettingsSnapshot.serializer(),
            source.bufferedReader(Charsets.UTF_8).readText(),
        )
    }.getOrNull()
}

/**
 * The Settings.System seam (ADR-001 reach table, T0_SYSTEM). Reading needs no permission;
 * writing needs the user-granted "Modify system settings" special access ([canWrite],
 * ACTION_MANAGE_WRITE_SETTINGS).
 */
interface SystemSettingsStore {
    fun read(name: String): String?
    fun canWrite(): Boolean
    fun write(name: String, value: String): Boolean
}

/**
 * The Settings.Secure / Settings.Global seam (ADR-001 reach table, T1_GRANT). READING needs no
 * permission. WRITING needs the WRITE_SECURE_SETTINGS grant that the one-shot privilege bridge
 * installs once (ADR-001 §1); [canWrite] reflects whether that grant is currently held. The
 * [namespace] is passed explicitly per call (never inferred) — the receiver derives it from the
 * matched [SettingKey], so the SYSTEM table is never reachable through this seam.
 */
interface SecureGlobalSettingsStore {
    fun read(namespace: Namespace, name: String): String?
    fun canWrite(): Boolean
    fun write(namespace: Namespace, name: String, value: String): Boolean
}

/**
 * The narrow Tier-1 grant seam: "make WRITE_SECURE_SETTINGS held, if you can" (ADR-001 Phase A,
 * one-shot, persists across reboots). The receiver app implements this over its AdbBridge
 * (ADR-003); providers stay privilege-agnostic and the sender never links a privilege stack.
 * The default is [Unavailable], so Tier-1 keys self-skip wherever nothing is wired.
 */
fun interface TierOneGrant {
    suspend fun ensureWriteSecureSettingsGranted(): Outcome

    enum class Outcome { GRANTED, REJECTED, UNAVAILABLE }

    companion object {
        val Unavailable = TierOneGrant { Outcome.UNAVAILABLE }
    }
}

/**
 * The SAFE keys the parity DATA PATH can carry: SYSTEM keys via [SystemSettingsStore] (Tier 0)
 * and SECURE/GLOBAL keys via [SecureGlobalSettingsStore] (Tier 1, after the one-shot grant).
 * RISKY and DEVICE_SPECIFIC keys are never in this default cut. The actual safety boundary is
 * this allowlist filter plus the per-value validator — never the wire tier tag.
 */
private fun exportableSafeKeys(): List<SettingKey> = SettingsAllowlist.all.filter {
    it.classification == Classification.SAFE &&
        (it.reach == Reach.T0_SYSTEM || it.reach == Reach.T1_GRANT)
}

/**
 * Sender side: read the SAFE keys that have a value on this device, across the SYSTEM
 * (Tier 0) and SECURE/GLOBAL (Tier 1) namespaces. Reading needs no privilege bridge on either
 * seam; the receiver decides what it can actually write.
 *
 * Rides [ItemKind.SETTINGS], which the frozen wire enum tags TIER1 (so the checklist treats it
 * opt-in). The safety boundary is the allowlist cut enforced here and in [SettingsApplyProvider].
 */
class SettingsExportProvider(
    private val systemStore: SystemSettingsStore,
    private val secureGlobalStore: SecureGlobalSettingsStore,
) : ExportProvider {

    override val kind = ItemKind.SETTINGS
    override val displayName = "Device settings"
    override val group = "Settings"

    private fun readValue(key: SettingKey): String? = runCatching {
        when (key.reach) {
            Reach.T0_SYSTEM -> systemStore.read(key.name)
            Reach.T1_GRANT -> secureGlobalStore.read(key.namespace, key.name)
            Reach.T1_SHELL, Reach.NA -> null
        }
    }.getOrNull()

    private fun snapshot(): SettingsSnapshot = SettingsSnapshot(
        exportableSafeKeys().mapNotNull { key ->
            readValue(key)?.let { SettingEntry(key.name, it) }
        },
    )

    override suspend fun available(): Boolean =
        runCatching { snapshot().entries.isNotEmpty() }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val writer = sink.bufferedWriter(Charsets.UTF_8)
        writer.write(SettingsCodec.encode(snapshot()))
        writer.flush()
    }
}

/**
 * Receiver side: apply a key ONLY if every gate passes — present in the compiled allowlist,
 * classified SAFE, reachable on this data path (T0_SYSTEM or T1_GRANT), and the value accepted
 * by the catalog validator. The matched key's own [SettingKey.namespace]/[SettingKey.reach]
 * decide which seam the write goes through; the sender's payload can never introduce keys or
 * redirect a namespace (THREAT_MODEL.md, malicious-sender row; DEVILS_ADVOCATE Q2). A failed
 * key is a per-key skip, never a transport error (PROTOCOL.md §4).
 *
 * Tier-1 (SECURE/GLOBAL) writes need WRITE_SECURE_SETTINGS. Per ADR-001 the grant is installed
 * ONCE — normally by the privilege wizard's capability probe (ADR-003), with [TierOneGrant] as
 * the lazy in-apply fallback; thereafter writes use the normal Settings.* API with no live
 * bridge. Where no grant path is wired, [SecureGlobalSettingsStore.canWrite] is false and
 * Tier-1 keys self-skip cleanly, leaving Tier-0 behavior unchanged.
 */
class SettingsApplyProvider(
    private val systemStore: SystemSettingsStore,
    private val secureGlobalStore: SecureGlobalSettingsStore,
    private val tierOneGrant: TierOneGrant = TierOneGrant.Unavailable,
) : ApplyProvider {

    override val kind = ItemKind.SETTINGS

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val snapshot = SettingsCodec.decode(source)
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable settings payload")

        val admissible: List<Pair<SettingKey, String>> = snapshot.entries.mapNotNull { entry ->
            val key = SettingsAllowlist.byName(entry.name) ?: return@mapNotNull null
            val ok = key.classification == Classification.SAFE &&
                (key.reach == Reach.T0_SYSTEM || key.reach == Reach.T1_GRANT) &&
                key.validator.accepts(entry.value)
            if (ok) key to entry.value else null
        }

        // Writability per tier, each resolved at most once and only when that tier is actually
        // present. Tier 1 attempts the one-shot grant only if it isn't already held.
        val t0Writable = admissible.any { it.first.reach == Reach.T0_SYSTEM } &&
            runCatching { systemStore.canWrite() }.getOrDefault(false)
        val t1Writable = admissible.any { it.first.reach == Reach.T1_GRANT } &&
            tier1Writable()

        var applied = 0
        var skipped = snapshot.entries.size - admissible.size // inadmissible entries: silent skips
        var grantBlocked = false
        for ((key, value) in admissible) {
            val tierWritable = if (key.reach == Reach.T0_SYSTEM) t0Writable else t1Writable
            if (!tierWritable) {
                grantBlocked = true
                skipped++
                continue
            }
            val wrote = runCatching {
                when (key.reach) {
                    Reach.T0_SYSTEM -> systemStore.write(key.name, value)
                    Reach.T1_GRANT -> secureGlobalStore.write(key.namespace, key.name, value)
                    Reach.T1_SHELL, Reach.NA -> false
                }
            }.getOrDefault(false)
            if (wrote) applied++ else skipped++
        }

        // A missing grant is surfaced on BOTH the full-skip path (SKIPPED) and the partial path
        // (OK with a hint) so the done-summary never silently hides "some settings need a grant".
        return when {
            applied > 0 -> ApplyOutcome(
                ItemStatus.OK,
                "applied $applied, skipped $skipped" +
                    if (grantBlocked) " (some need the secure-settings grant)" else "",
            )
            grantBlocked -> ApplyOutcome(
                ItemStatus.SKIPPED,
                "settings need the system or secure-settings grant",
            )
            else -> ApplyOutcome(ItemStatus.OK, "applied 0, skipped $skipped")
        }
    }

    /**
     * Whether Settings.Secure/Global is writable now — either the grant already persists from a
     * prior run (the wizard's probe installs it, ADR-001 V5: survives reboots), or the lazy
     * [TierOneGrant] path installs it this call. Consulted at most once per apply.
     */
    private suspend fun tier1Writable(): Boolean {
        if (runCatching { secureGlobalStore.canWrite() }.getOrDefault(false)) return true
        val outcome = runCatching { tierOneGrant.ensureWriteSecureSettingsGranted() }
            .getOrDefault(TierOneGrant.Outcome.UNAVAILABLE)
        return outcome == TierOneGrant.Outcome.GRANTED &&
            runCatching { secureGlobalStore.canWrite() }.getOrDefault(false)
    }
}
