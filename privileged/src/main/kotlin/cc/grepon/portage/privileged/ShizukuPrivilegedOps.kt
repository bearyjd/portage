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

/**
 * Shizuku-backed implementation of [PrivilegedOps].
 *
 * SCAFFOLD STUB — implement only after ADR-001 verification (V1–V8) on a real GOS device
 * decides between the two architectures:
 *   - grant architecture (V4/V5 pass): [ensureWriteSecureSettingsGranted] does one
 *     `pm grant`, then settings writes leave this class entirely.
 *   - live-shell architecture (V4/V5 fail): route settings writes through [exec].
 *
 * All methods currently signal the bridge is not wired up rather than pretending to work.
 */
class ShizukuPrivilegedOps : PrivilegedOps {

    override fun availability(): PrivilegedOps.Availability =
        PrivilegedOps.Availability.NOT_INSTALLED // TODO: query Shizuku.pingBinder() + permission

    override suspend fun ensureWriteSecureSettingsGranted(): PrivilegedOps.GrantOutcome =
        PrivilegedOps.GrantOutcome.BRIDGE_UNAVAILABLE // TODO: exec(["pm","grant",SELF_PKG,WRITE_SECURE_SETTINGS])

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

    /**
     * Private live-shell escape hatch (ADR-001 §4). The typed methods above route through
     * this; it is deliberately NOT on the public [PrivilegedOps] boundary. Every use is
     * internal to the bridge and security-reviewed.
     */
    @Suppress("unused") // wired up when the typed ops are implemented
    private suspend fun exec(command: List<String>): ShellResult =
        ShellResult(exitCode = -1, stdout = "", stderr = "Shizuku bridge not implemented")

    @Suppress("unused")
    private data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    }
}
