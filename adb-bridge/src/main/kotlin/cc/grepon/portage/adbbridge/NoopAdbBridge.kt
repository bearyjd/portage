/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.adbbridge

/**
 * The bridge when there is no bridge: Wireless Debugging unavailable (API < 30) or the user
 * skipped privilege setup. Every operation reports unavailability as a typed result — callers
 * built against [AdbBridge] degrade without special-casing (Tier 0 must always work, PRP §3).
 */
object NoopAdbBridge : AdbBridge {

    override suspend fun pair(pairingPort: Int, pairingCode: String): AdbBridge.PairingResult =
        AdbBridge.PairingResult.Unsupported

    override suspend fun connect(): AdbBridge.ConnectionResult =
        AdbBridge.ConnectionResult.Unsupported

    override fun isConnected(): Boolean = false

    override fun disconnect() = Unit

    override suspend fun shell(command: String): AdbBridge.ShellResult =
        AdbBridge.ShellResult.NotConnected

    override suspend fun selfGrant(permission: String): AdbBridge.GrantResult =
        AdbBridge.GrantResult.BRIDGE_UNAVAILABLE

    override suspend fun installApk(stagedApkPath: String): AdbBridge.InstallResult =
        AdbBridge.InstallResult.BridgeUnavailable

    override suspend fun probeCapabilities(): Set<AdbBridge.PrivilegedCapability> = emptySet()
}
