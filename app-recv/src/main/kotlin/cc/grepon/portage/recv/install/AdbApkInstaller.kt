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
 */
class AdbApkInstaller(private val bridge: AdbBridge) : ApkSilentInstaller {

    override suspend fun install(
        packageName: String,
        files: List<ApkInstallFile>,
    ): ApkInstallResult {
        val staged = files.map { AdbBridge.StagedApk(it.name, it.length, it.open) }
        try {
            if (!bridge.isConnected()) {
                when (bridge.connect()) {
                    is AdbBridge.ConnectionResult.Connected -> Unit // proceed
                    else -> return ApkInstallResult.BridgeUnavailable
                }
            }
            return when (val result = bridge.installApk(staged)) {
                AdbBridge.InstallResult.Installed -> ApkInstallResult.Installed
                is AdbBridge.InstallResult.Failed -> ApkInstallResult.Failed(result.reason)
                AdbBridge.InstallResult.BridgeUnavailable -> ApkInstallResult.BridgeUnavailable
            }
        } finally {
            // AC-11: never hold shell uid open; the bridge reconnects with the persisted key next time.
            bridge.disconnect()
        }
    }
}
