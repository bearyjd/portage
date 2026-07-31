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
    fun encode(snapshot: DefaultRoleSnapshot): String =
        JsonLines.format.encodeToString(DefaultRoleSnapshot.serializer(), snapshot)

    /** Returns null on anything unparseable — including an unknown role, which fails the enum. */
    fun decode(source: InputStream): DefaultRoleSnapshot? = runCatching {
        JsonLines.format.decodeFromString(
            DefaultRoleSnapshot.serializer(),
            source.bufferedReader(Charsets.UTF_8).readText(),
        )
    }.getOrNull()
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
 * A validated, restorable candidate: the user HAD this app as their default, and it is installed
 * here. Surfaced for an explicit opt-in tap; nothing is applied to produce one.
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
 *  - unparseable payload, or any unknown role → the whole item is rejected (`WRITE_ERROR`)
 *  - malformed package name → that entry dropped
 *  - duplicate role → first wins (a hostile snapshot must not produce two rows for one role, which
 *    would also break Compose's list keys — same guard as the inventory/Bluetooth lists)
 *  - package not installed here → dropped; restoring a role to a missing app is meaningless, and
 *    `isInstalled` is the qualification gate the spike flagged as untested platform behaviour
 */
class DefaultRolesApplyProvider(
    private val isInstalled: (String) -> Boolean,
    private val onCandidates: (List<RoleRestoreCandidate>) -> Unit,
) : ApplyProvider {

    override val kind = ItemKind.DEFAULT_ROLES

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val snapshot = DefaultRolesCodec.decode(source)
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable default-roles snapshot")

        val wellFormed = snapshot.roles
            .filter { PACKAGE_NAME.matches(it.packageName) }
            .distinctBy { it.role }
            .take(MAX_ROLES)

        val candidates = wellFormed
            .filter { runCatching { isInstalled(it.packageName) }.getOrDefault(false) }
            .map { RoleRestoreCandidate(it.role, it.packageName) }

        onCandidates(candidates)

        // An empty snapshot that PARSED is not an error — the sender simply had no defaults worth
        // carrying. Only a non-empty input that produced nothing usable is worth flagging, and even
        // then it is SKIPPED rather than WRITE_ERROR: "the apps aren't installed here" is a defined,
        // honest outcome, not a failure to write.
        return when {
            snapshot.roles.isEmpty() -> ApplyOutcome(ItemStatus.OK)
            candidates.isEmpty() -> ApplyOutcome(ItemStatus.SKIPPED, "no matching apps installed here")
            else -> ApplyOutcome(ItemStatus.OK)
        }
    }

    private companion object {
        /** Three roles exist; the cap bounds a hostile snapshot rather than trusting the enum count. */
        const val MAX_ROLES = 8
    }
}

/**
 * The Android package grammar: dot-separated `[A-Za-z0-9_]` segments, two or more. Same regex the
 * relay header and the inventory deep link use — everything it accepts is inert by construction, so
 * no shell metacharacter or intent scheme can hide in a validated package name. The bridge's
 * `ShellArgs` rejects metacharacters again independently.
 */
private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")
