/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.install

import cc.grepon.portage.adbbridge.AdbBridge
import cc.grepon.portage.providers.apk.ApkInstallFile
import cc.grepon.portage.providers.apk.ApkInstallResult
import cc.grepon.portage.providers.apk.ApkSilentInstaller
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The REAL silent (privileged) install seam (ADR-006 P6), replacing the prior deferred silent path
 * (which always returned Deferred → Tier-0). It
 * adapts the `:providers` [ApkSilentInstaller] over the [AdbBridge]: each staged split is handed to
 * the bridge as a stdin stream source ([AdbBridge.StagedApk]), the bridge opens ONE `pm
 * install-create` session and stdin-streams every split into `pm install-write -S <size> .. -`
 * (no shell-readable path ever exists — the receiver's app-private staging stays unreadable to
 * shell uid). The C1/D2 module boundary is preserved: only `:app-recv` ties `:providers` to
 * `:adb-bridge`; `:providers` never gains the edge.
 *
 * Lifecycle (AC-11): the bridge is connected for the install and DISCONNECTED in a `finally` — shell
 * uid is never held open in the background (mirrors [cc.grepon.portage.wizard.PrivilegeWizard]'s
 * connect→probe→disconnect idiom). A bridge that was probed `SILENT_INSTALL`-present but is gone at
 * apply time maps to [ApkInstallResult.BridgeUnavailable], which the apply provider degrades to the
 * Tier-0 `PackageInstaller` path (stale-positive tolerance).
 *
 * Hang guard (hardware bug, GOS A16): the apply must NEVER drive [bridge].connect() into a missing
 * endpoint — libadb's NsdManager mDNS discovery wait ignores thread interruption, so a connect()
 * with Wireless Debugging off hangs INDEFINITELY (`withTimeout` fires but the worker can't unwind)
 * and `disconnect()` cannot rescue it. That guard now lives uniformly INSIDE [AdbBridge.connect]
 * (it returns [AdbBridge.ConnectionResult.NoEndpoint] when Wireless Debugging is off, without ever
 * calling libadb), so every caller — including this one — is protected; the connect() below simply
 * sees a fast NoEndpoint → [ApkInstallResult.BridgeUnavailable] → Tier-0. As a last-resort backstop
 * the whole connect+install attempt is wrapped in [withTimeoutOrNull] so even an unforeseen block
 * can never hang the apply; a timeout degrades to Tier-0 too.
 */
class AdbApkInstaller(
    private val bridge: AdbBridge,
    private val attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS,
) : ApkSilentInstaller {

    override suspend fun install(
        packageName: String,
        files: List<ApkInstallFile>,
    ): ApkInstallResult {
        val staged = files.map { AdbBridge.StagedApk(it.name, it.length, it.open) }
        return try {
            // Outer hard ceiling: even an unforeseen uninterruptible block degrades to Tier-0
            // rather than hanging the apply. null ⇒ timed out ⇒ BridgeUnavailable.
            withTimeoutOrNull(attemptTimeoutMs) {
                if (!bridge.isConnected()) {
                    // connect() self-guards on Wireless Debugging (NoEndpoint when off) — it never
                    // drives libadb into the uninterruptible discovery hang. Any non-Connected → Tier-0.
                    when (bridge.connect()) {
                        is AdbBridge.ConnectionResult.Connected -> Unit // proceed
                        else -> return@withTimeoutOrNull ApkInstallResult.BridgeUnavailable
                    }
                }
                when (val result = bridge.installApk(staged)) {
                    AdbBridge.InstallResult.Installed -> ApkInstallResult.Installed
                    is AdbBridge.InstallResult.Failed -> ApkInstallResult.Failed(result.reason)
                    AdbBridge.InstallResult.BridgeUnavailable -> ApkInstallResult.BridgeUnavailable
                }
            } ?: ApkInstallResult.BridgeUnavailable
        } finally {
            // AC-11: never hold shell uid open; the bridge reconnects with the persisted key next time.
            bridge.disconnect()
        }
    }

    private companion object {
        /**
         * Belt-and-suspenders ceiling for the whole connect+install attempt. Generous (the bridge's
         * own connect/shell timeouts are the primary bound at ~15-20s each) but finite, so a worst
         * case still degrades to Tier-0 within a bounded, user-tolerable window.
         */
        const val ATTEMPT_TIMEOUT_MS = 90_000L
    }
}
