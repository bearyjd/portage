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
 * The single boundary behind which all privilege escalation lives. Swapping the
 * Shizuku implementation for a root-shell one (or a no-op for Tier 0) stays local to
 * this module — see docs/prp/portage-prp-prompt.md §4 and ADR-001 §1.
 *
 * IMPORTANT: per ADR-001, `Settings.Secure`/`Global` writes are NOT in this interface.
 * After [ensureWriteSecureSettingsGranted] succeeds once, the app writes settings through
 * the normal `Settings.*` API with no live privilege bridge. Only operations that
 * genuinely need shell uid AT CALL TIME live here.
 */
interface PrivilegedOps {

    /** Current liveness of the bridge. Re-check at each use; GOS auto-reboot kills it. */
    fun availability(): Availability

    /**
     * Phase A one-shot: have the shell uid `pm grant` WRITE_SECURE_SETTINGS to us. The
     * grant persists across reboots and across the bridge dying (ADR-001 §1, V4/V5).
     * @return [GrantOutcome.GRANTED] or [GrantOutcome.GRANT_REJECTED] (→ live-shell fallback).
     */
    suspend fun ensureWriteSecureSettingsGranted(): GrantOutcome

    /** Tier 1 runtime-permission parity (`pm grant/revoke`). Opt-in, gated in UI. */
    suspend fun grantRuntimePermission(packageName: String, permission: String): OpResult
    suspend fun revokeRuntimePermission(packageName: String, permission: String): OpResult

    /** Batched silent install via PackageInstaller session over the shell (V6). */
    suspend fun installApk(stagedApkPath: String): OpResult

    /** Switch navigation mode via overlay (`cmd overlay enable-exclusive`, V7). Opt-in. */
    suspend fun setNavigationMode(mode: NavigationMode): OpResult

    /** Restore the recorded prior SMS role holder (`cmd role add-role-holder`, V7). */
    suspend fun setSmsRoleHolder(packageName: String): OpResult

    /**
     * Escape hatch / live-shell fallback (ADR-001 §4): run a raw shell command when the
     * grant architecture is unavailable. Use sparingly; prefer typed methods above.
     */
    suspend fun exec(command: List<String>): ShellResult

    enum class Availability { LIVE, INSTALLED_NOT_RUNNING, NOT_INSTALLED, PERMISSION_DENIED }

    enum class GrantOutcome { GRANTED, GRANT_REJECTED, BRIDGE_UNAVAILABLE }

    enum class NavigationMode { GESTURAL, THREE_BUTTON }

    sealed interface OpResult {
        data object Ok : OpResult
        data class Failed(val reason: String) : OpResult
        data object BridgeUnavailable : OpResult
    }

    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)
}
