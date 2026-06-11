/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.privileged

import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shizuku-backed [PrivilegedOps] (ADR-001 grant architecture). The ONE method wired up is
 * [ensureWriteSecureSettingsGranted]: it has the shell uid `pm grant` us WRITE_SECURE_SETTINGS
 * exactly once, after which `Settings.Secure`/`Global` writes use the normal API with no live
 * bridge. The grant persists across reboots and across the bridge dying (ADR-001 §1, V4/V5).
 *
 * The Shizuku PERMISSION (the user authorizing portage to use Shizuku at all) is a UI interaction
 * and is NOT requested here — this method assumes it is already held and fails closed
 * ([GrantOutcome.BRIDGE_UNAVAILABLE]) otherwise, so an apply with no grant simply self-skips its
 * Tier-1 keys. The in-app "unlock secure settings" affordance that drives the permission request
 * is a separate follow-up.
 *
 * The remaining privileged ops (runtime-permission parity, installer, nav-mode, SMS role) stay
 * deferred to their own least-privilege PRs and report the bridge as unavailable.
 *
 * Decision logic lives here and is unit-tested through [ShizukuGate]; all device-only Shizuku
 * surface lives in [AndroidShizukuGate].
 */
class ShizukuPrivilegedOps internal constructor(
    private val selfPackage: String,
    private val gate: ShizukuGate,
) : PrivilegedOps {

    constructor(context: Context) : this(context.packageName, AndroidShizukuGate(context))

    override fun availability(): PrivilegedOps.Availability = when {
        !gate.isBinderAlive() ->
            if (gate.isInstalled()) PrivilegedOps.Availability.INSTALLED_NOT_RUNNING
            else PrivilegedOps.Availability.NOT_INSTALLED
        // Running but too old for portage to drive — the user's fix is "update", not "start".
        gate.isPreV11() -> PrivilegedOps.Availability.OUTDATED
        !gate.hasPermission() -> PrivilegedOps.Availability.PERMISSION_DENIED
        else -> PrivilegedOps.Availability.LIVE
    }

    override suspend fun ensureWriteSecureSettingsGranted(): PrivilegedOps.GrantOutcome {
        if (!gate.isBinderAlive() || gate.isPreV11() || !gate.hasPermission()) {
            return PrivilegedOps.GrantOutcome.BRIDGE_UNAVAILABLE
        }
        // Fixed argv — never a shell string. `selfPackage` is our own package, not wire input.
        // Bound the wait: a bind that is accepted but never connects (a real risk on GOS, where
        // the binder can die between the liveness check and the bind) must fail closed, not hang
        // the apply. Timing out cancels runAsShell, whose invokeOnCancellation unbinds.
        val exitCode = withTimeoutOrNull(GRANT_TIMEOUT_MS) {
            gate.runAsShell(listOf("pm", "grant", selfPackage, WRITE_SECURE_SETTINGS))
        }
        return when (exitCode) {
            0 -> PrivilegedOps.GrantOutcome.GRANTED
            null -> PrivilegedOps.GrantOutcome.BRIDGE_UNAVAILABLE // timeout or bind/exec failure
            else -> PrivilegedOps.GrantOutcome.GRANT_REJECTED // ran, but the grant did not take
        }
    }

    /**
     * Drive the Shizuku authorization. Pure decision logic over [gate]; the device-only dialog
     * plumbing lives in [ShizukuGate.requestPermission]. Mirrors [ensureWriteSecureSettingsGranted]:
     * gate the preconditions, then bound the wait so a never-answered dialog can't hang the unlock.
     *
     * - Unreachable (dead binder / pre-v11) → false (the UI then shows start/update guidance).
     * - Already authorized → true, with no second prompt.
     * - Otherwise issue the request and await the user, failing closed to false on a decline, an
     *   un-issuable request, or the timeout.
     */
    internal suspend fun requestAccess(): Boolean {
        if (!gate.isBinderAlive() || gate.isPreV11()) return false
        if (gate.hasPermission()) return true
        return withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { gate.requestPermission() } ?: false
    }

    override suspend fun grantRuntimePermission(packageName: String, permission: String): PrivilegedOps.OpResult =
        PrivilegedOps.OpResult.BridgeUnavailable

    override suspend fun revokeRuntimePermission(packageName: String, permission: String): PrivilegedOps.OpResult =
        PrivilegedOps.OpResult.BridgeUnavailable

    override suspend fun installApk(stagedApkPath: String): PrivilegedOps.OpResult =
        PrivilegedOps.OpResult.BridgeUnavailable

    override suspend fun setNavigationMode(mode: PrivilegedOps.NavigationMode): PrivilegedOps.OpResult =
        PrivilegedOps.OpResult.BridgeUnavailable

    override suspend fun setSmsRoleHolder(packageName: String): PrivilegedOps.OpResult =
        PrivilegedOps.OpResult.BridgeUnavailable

    private companion object {
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

        // Mirrors the transport handshake timeout — a one-shot `pm grant` is sub-second when the
        // bridge is healthy; anything slower is a stuck bind we'd rather fail closed on.
        const val GRANT_TIMEOUT_MS = 10_000L

        // Generous (the user may read the Shizuku dialog) but finite — a never-answered dialog must
        // not hang the unlock. 2 minutes, matching AndroidSmsRoleCoordinator's role-dialog cap.
        const val PERMISSION_TIMEOUT_MS = 120_000L
    }
}
