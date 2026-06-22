/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.install

import com.ventouxlabs.portage.adbbridge.AdbBridge
import com.ventouxlabs.portage.providers.apk.RuntimePermissionGranter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The FIRST production call site of [AdbBridge.grantRuntimePermission] (ADR-006 D5, Phase 5b). Adapts the
 * `:providers` [RuntimePermissionGranter] seam over the [AdbBridge]: after a silent install the apply
 * provider hands this the planner's `auto` set (already filtered to
 * [com.ventouxlabs.portage.providers.permission.PermissionAllowlist.DEFAULT_SAFE]); this opens ONE bridge
 * session and runs `pm grant <pkg> <perm>` per permission. The C1/D2 module boundary is preserved: only
 * `:app-recv` ties `:providers` to `:adb-bridge`; `:providers` never gains the edge.
 *
 * This grants the SOURCE device's already-held permissions on the target, restricted to the allowlist
 * default-safe set — it is policy-free here. It never decides WHAT to grant; the pure
 * [com.ventouxlabs.portage.providers.permission.PermissionParityPlanner] + the apply provider's belt
 * re-filter own that, and `AdbBridge.grantRuntimePermission` re-validates pkg/perm via `ShellArgs` at the
 * wire boundary. Both args reaching here are already trustworthy (a grammar-validated package name and
 * allowlist-constant permission strings); the ShellArgs check is the final belt.
 *
 * Best-effort, never fatal (ADR-006 D5): a per-permission failure, an unavailable bridge, a timeout, or
 * an unexpected throw mid-loop simply omits that permission from the returned set — the transfer is never
 * aborted by a failed parity grant. The returned set is exactly the permissions confirmed granted.
 *
 * Lifecycle (AC-11, mirroring [AdbApkInstaller]): this assumes EXCLUSIVE use of the bridge for the
 * duration of the call — it connects for the grants and DISCONNECTS in a `finally`, so it must not run
 * concurrently with another holder of the same process-scoped bridge. shell uid is never held open in
 * the background. The silent installer ran just before this and disconnected, so this re-establishes its
 * own session. The same hang guard applies:
 * [AdbBridge.connect] self-gates on Wireless Debugging (fast [AdbBridge.ConnectionResult.NoEndpoint] when
 * off, never driving libadb into the uninterruptible mDNS-discovery hang), and the whole attempt is
 * wrapped in [withTimeoutOrNull] as a last-resort ceiling so a parity grant can never hang the apply.
 */
class AdbRuntimePermissionGranter(
    private val bridge: AdbBridge,
    private val attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS,
) : RuntimePermissionGranter {

    override suspend fun grant(packageName: String, permissions: List<String>): Set<String> {
        if (permissions.isEmpty()) return emptySet()
        val granted = mutableSetOf<String>()
        try {
            withTimeoutOrNull(attemptTimeoutMs) {
                if (!bridge.isConnected()) {
                    when (bridge.connect()) {
                        is AdbBridge.ConnectionResult.Connected -> Unit // proceed
                        else -> return@withTimeoutOrNull // no live bridge → grant nothing, best-effort
                    }
                }
                for (permission in permissions) {
                    // Per-permission isolation: one failure (or an unexpected throw) must not abort the
                    // rest or the transfer. CancellationException is rethrown so structured cancellation
                    // and the outer timeout still work.
                    val ok = try {
                        bridge.grantRuntimePermission(packageName, permission) == AdbBridge.OpResult.Ok
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        false
                    }
                    if (ok) granted += permission
                }
            }
        } finally {
            // AC-11: never hold shell uid open; the bridge reconnects with the persisted key next time.
            bridge.disconnect()
        }
        return granted
    }

    private companion object {
        /**
         * Belt-and-suspenders ceiling for the whole connect + grant-loop attempt. Generous (the bridge's
         * own connect/shell timeouts are the primary bound) but finite, so a worst case still degrades to
         * "granted nothing" within a bounded, user-tolerable window rather than hanging the apply.
         */
        const val ATTEMPT_TIMEOUT_MS = 90_000L
    }
}
