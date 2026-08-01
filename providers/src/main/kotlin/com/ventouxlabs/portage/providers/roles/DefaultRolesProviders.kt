/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.roles

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.wire.JsonLines
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * Default-app role restore (#122) — the generalization of the shipped transient-SMS-role mechanism.
 *
 * SCOPE: portage carries the user's *choice* of default browser / dialer / launcher, not any app or
 * its data. On the receiver the choice is re-applied through the bridge's `cmd role
 * add-role-holder`, verified on GOS A17 to take effect AND survive reboot
 * (`docs/prp/features/SPIKE-RESULTS-2026-07-31.md` §2, §8.2).
 *
 * CONSENT IS LOAD-BEARING: the shell path applies a role change with **no system confirm dialog**.
 * The platform will not ask on portage's behalf, so the entire consent burden sits in portage's UI.
 * Accordingly [DefaultRolesApplyProvider] **restores nothing** — it validates and surfaces
 * candidates, and the restore happens only on an explicit per-role user action. Same shape as the
 * opt-in permission grants and the Bluetooth re-pair checklist.
 *
 * SENDER PRIVILEGE: none. The capture side reads defaults through ordinary intent resolution
 * (`PackageManager.resolveActivity`), which was measured on A17 to return exactly what the role
 * service reports. `app-send` links no privilege stack — the no-escalation CI assert stays intact.
 */

/**
 * The roles portage may carry and restore. CLOSED SET.
 *
 * The wire carries this ENUM (via [SerialName]), never a free role string — an unknown value fails
 * to deserialize and the whole item is rejected. That is deliberate: the role argument selects WHICH
 * system capability gets handed to a package, so a hostile-but-authenticated sender must not be able
 * to name one portage never intended to restore. Restricting it to a compiled set makes that
 * unrepresentable rather than merely validated. The bridge enforces the same closure independently
 * with its own enum ([com.ventouxlabs.portage.adbbridge.AdbBridge.RoleTarget]) — the mapping is an
 * exhaustive `when` in the degoogle adapter, so adding a role forces both sides to be updated.
 *
 * SMS is deliberately ABSENT: it already ships with its own transient acquire/write/relinquish
 * discipline and self-gate, and is currently broken on GOS (#61).
 */
@Serializable
enum class RestorableRole {
    @SerialName("browser")
    BROWSER,

    @SerialName("dialer")
    DIALER,

    @SerialName("home")
    HOME,
}

/** One captured default: which role, and which package held it on the sender. */
@Serializable
data class DefaultRoleRecord(val role: RestorableRole, val packageName: String)

/** The captured defaults as a single small JSON document. */
@Serializable
data class DefaultRoleSnapshot(val roles: List<DefaultRoleRecord>)

/** JSON (de)serialization, separated so tests can frame payloads directly. */
object DefaultRolesCodec {
    /**
     * Hard ceiling on how many bytes of UNTRUSTED payload are read before anything is parsed.
     *
     * This bound is on the ALLOCATION, and it has to be: the per-item receive cap is 64 MiB, so a
     * hostile sender can legitimately frame an item that large. An earlier version read the whole
     * stream with `readText()` and bounded only the decoded LIST, which bounded the output while the
     * 64 MiB had already been pulled into the heap — the expensive part was over before the limit
     * ran. A real snapshot is three short records; 64 KiB is orders of magnitude of headroom.
     */
    internal const val MAX_PAYLOAD_BYTES = 64 * 1024

    fun encode(snapshot: DefaultRoleSnapshot): String =
        JsonLines.format.encodeToString(DefaultRoleSnapshot.serializer(), snapshot)

    /** Returns null on anything unparseable — including an unknown role, which fails the enum. */
    fun decode(source: InputStream): DefaultRoleSnapshot? = runCatching {
        val text = source.readBoundedUtf8(MAX_PAYLOAD_BYTES) ?: return null
        JsonLines.format.decodeFromString(DefaultRoleSnapshot.serializer(), text)
    }.getOrNull()
}

/**
 * Read at most [maxBytes], or return null if the source has more to give.
 *
 * REJECTS rather than truncates. Truncation would hand a half-document to the parser, and a prefix
 * of a valid snapshot can itself be valid JSON — that would silently apply an attacker-chosen SUBSET
 * of a payload portage refused to read in full. Over-length input is not a snapshot portage will
 * act on, so the honest answer is "unreadable".
 */
private fun InputStream.readBoundedUtf8(maxBytes: Int): String? {
    // One byte of headroom: filling it proves the source exceeded the ceiling.
    val buffer = ByteArray(maxBytes + 1)
    var filled = 0
    while (filled < buffer.size) {
        val n = read(buffer, filled, buffer.size - filled)
        if (n < 0) break
        filled += n
    }
    if (filled > maxBytes) return null
    return String(buffer, 0, filled, Charsets.UTF_8)
}

/**
 * Sender-side boundary, mirroring `InventorySource` / `BluetoothStore`. Reads the CURRENT default
 * for a role using non-privileged intent resolution; the Android implementation lives in app-send.
 * Returns null when there is no unambiguous default (no handler, or a chooser rather than a
 * user-chosen default).
 */
fun interface DefaultRolesStore {
    fun currentHolder(role: RestorableRole): String?
}

/**
 * Receiver-side privileged seam — the ONLY way this feature reaches the bridge, mirroring
 * `TierOneGrant`. The real implementation lives under `app-recv/src/degoogle` and maps
 * [RestorableRole] onto the bridge's own enum; the play flavor gets [Unavailable] because it ships
 * no bridge at all.
 */
fun interface RoleRestorer {
    suspend fun restore(role: RestorableRole, packageName: String): Outcome

    enum class Outcome {
        /** The role service accepted the change. */
        RESTORED,

        /** Reached the bridge, but the platform refused (e.g. the app does not qualify). */
        REJECTED,

        /** No bridge on this build/device, or it is not connected. */
        UNAVAILABLE,
    }

    companion object {
        val Unavailable = RoleRestorer { _, _ -> Outcome.UNAVAILABLE }
    }
}

/**
 * Sender side: snapshot the user's current default browser / dialer / launcher.
 *
 * Degrades to "nothing to send" on any failure, per the Tier-0 graceful-degrade contract: a role
 * with no unambiguous holder is simply omitted, never an error. Carries package names only — no
 * app data, no APK, nothing about the apps themselves beyond which one the user had chosen.
 */
class DefaultRolesExportProvider(
    private val store: DefaultRolesStore,
) : ExportProvider {

    override val kind = ItemKind.DEFAULT_ROLES
    override val displayName = "Default apps"
    override val group = "Apps"

    private fun snapshot(): DefaultRoleSnapshot {
        val records = RestorableRole.entries.mapNotNull { role ->
            val pkg = runCatching { store.currentHolder(role) }.getOrNull()?.trim()
            if (pkg.isNullOrBlank() || !PACKAGE_NAME.matches(pkg)) null
            else DefaultRoleRecord(role, pkg)
        }
        return DefaultRoleSnapshot(records)
    }

    override suspend fun available(): Boolean =
        runCatching { snapshot().roles.isNotEmpty() }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val snapshot = runCatching { snapshot() }.getOrNull() ?: return
        if (snapshot.roles.isEmpty()) return
        val writer = sink.bufferedWriter(Charsets.UTF_8)
        writer.write(DefaultRolesCodec.encode(snapshot))
        writer.flush()
    }
}

/**
 * A validated candidate: the user HAD this app as their default, and the entry survived every
 * security filter (known role, well-formed package, within the input bound, deduped).
 *
 * Deliberately NOT "and it is installed here". Installedness is a LIVE property, re-evaluated by the
 * receiver at surface time and again at tap time — see [DefaultRolesApplyProvider]'s note on why an
 * apply-time installed check cannot work. Nothing is applied to produce one of these.
 */
data class RoleRestoreCandidate(val role: RestorableRole, val packageName: String)

/**
 * Receiver side: validate the captured defaults and hand the surviving candidates to the UI via
 * [onCandidates]. **Applies nothing.**
 *
 * That is the consent design, not an omission: the shell path shows no system confirm dialog, so a
 * silently-applied role change would be exactly the "power without consent UX" the threat model
 * forbids. The restore itself runs from the ViewModel on a per-role user action, through
 * [RoleRestorer].
 *
 * Filtering, in order:
 *  - unparseable payload (including over-length), or any unknown role → the whole item is rejected
 *    (`WRITE_ERROR`)
 *  - input bound applied BEFORE per-entry work
 *  - malformed package name → that entry dropped
 *  - duplicate role → first wins (a hostile snapshot must not produce two rows for one role, which
 *    would also break Compose's list keys — same guard as the inventory/Bluetooth lists)
 *
 * WHY THERE IS NO "is it installed here" FILTER, though restoring a role to a missing app is
 * meaningless: at apply time the answer is not yet knowable. Tier-0 APK installs — the ordinary
 * path, and the only one working on GOS today (#86) — are USER-CONFIRMED and ASYNCHRONOUS:
 * `ApkApplyProvider.apply` returns once the install prompt is surfaced, NOT once the app exists, so
 * the apps this same transfer is carrying are typically installed after the Done screen first
 * renders. An apply-time installed-set read therefore filters out exactly the headline case
 * ("restore my defaults after reinstalling my apps"), and no reordering of the item stream fixes
 * that — the install simply has not happened yet.
 *
 * So installedness is evaluated where it is actually live: the receiver re-filters when it builds
 * the Done state, again on every resume (i.e. after the user returns from the system install
 * dialogs), and once more as a belt when the user taps restore.
 */
class DefaultRolesApplyProvider(
    /**
     * Whether this BUILD can restore a role at all — false on the play flavor, which ships no
     * bridge. Only affects the reported status: a build that cannot restore reports the item
     * `SKIPPED` rather than `OK`, so it is not counted as moved when nothing is restorable.
     */
    private val canRestore: () -> Boolean = { false },
    private val onCandidates: (List<RoleRestoreCandidate>) -> Unit,
) : ApplyProvider {

    override val kind = ItemKind.DEFAULT_ROLES

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val snapshot = DefaultRolesCodec.decode(source)
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable default-roles snapshot")

        // Bound the UNTRUSTED input before doing per-entry work. Previously this .take() sat at the
        // END of the chain, which bounded the OUTPUT while still walking every entry of a hostile
        // snapshot — a work bound that did not bound work.
        val candidates = snapshot.roles
            .take(MAX_ROLES_INPUT)
            .filter { PACKAGE_NAME.matches(it.packageName) }
            .distinctBy { it.role }
            .map { RoleRestoreCandidate(it.role, it.packageName) }

        onCandidates(candidates)

        // An empty snapshot that PARSED is not an error — the sender simply had no defaults worth
        // carrying. The non-OK cases are SKIPPED rather than WRITE_ERROR: "this build can't restore
        // defaults" and "the snapshot held nothing usable" are defined, honest outcomes, not
        // failures to write.
        return when {
            snapshot.roles.isEmpty() -> ApplyOutcome(ItemStatus.OK)
            !runCatching { canRestore() }.getOrDefault(false) ->
                ApplyOutcome(ItemStatus.SKIPPED, "this build can't set default apps")
            candidates.isEmpty() ->
                ApplyOutcome(ItemStatus.SKIPPED, "no usable entries in the default-apps snapshot")
            else -> ApplyOutcome(ItemStatus.OK)
        }
    }

    private companion object {
        /**
         * Input bound, applied BEFORE any per-entry work. Not a limit on how many roles portage
         * supports (the enum is that) — a ceiling on how much attacker-controlled input is walked.
         */
        const val MAX_ROLES_INPUT = 8
    }
}

/**
 * The Android package grammar: dot-separated `[A-Za-z0-9_]` segments, two or more. Same regex the
 * relay header and the inventory deep link use — everything it accepts is inert by construction, so
 * no shell metacharacter or intent scheme can hide in a validated package name. The bridge's
 * `ShellArgs` rejects metacharacters again independently.
 */
private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")
