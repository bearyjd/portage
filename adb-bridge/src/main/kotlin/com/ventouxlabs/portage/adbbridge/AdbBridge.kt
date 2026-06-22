/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.adbbridge

/**
 * The single privilege boundary (ADR-003). portage owns the full privileged stack — ADB key
 * generation, Wireless Debugging pairing, self-connection over localhost, shell-uid command
 * execution — with no third-party bridge app. NOTHING outside :adb-bridge may speak the ADB
 * wire protocol; every privileged operation in the rest of the codebase goes through this
 * interface.
 *
 * This interface is also the GrapheneOS contribution surface: a privileged system-app build
 * would implement it natively against platform APIs (no pairing, no keys, no ADB) and the rest
 * of the codebase is unchanged (ADR-003 §OS-integration).
 *
 * Per ADR-001 the grant architecture is unchanged: after [selfGrant] installs
 * WRITE_SECURE_SETTINGS once, settings writes use the normal `Settings.*` API with NO live
 * bridge. The bridge must be live only for operations that need shell uid at call time.
 *
 * Raw [shell] exists because this module owns the whole stack (the old Shizuku UserService
 * allowlist boundary is gone — there is no second process to defend). Discipline instead lives
 * at the call-site rule: code outside :adb-bridge and :wizard calling [shell] directly is a
 * review blocker; use the typed operations.
 */
interface AdbBridge {

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────

    /**
     * Run the Android 11+ Wireless Debugging pairing exchange against the local pairing port.
     * One-time per install (the pairing key persists in adbd's keystore across reboots).
     * The [pairingCode] is the 6-digit code from Developer options; it is never logged or
     * persisted (THREAT_MODEL §1 — same accepted String residual as the QR PSK).
     */
    suspend fun pair(pairingPort: Int, pairingCode: String): PairingResult

    /** Connect to the local adbd Wireless Debugging port (mDNS-discovered, TLS, key auth). */
    suspend fun connect(): ConnectionResult

    fun isConnected(): Boolean

    /**
     * Tear the connection down. Must be safe to call from any thread, must be idempotent, and
     * must abort an in-flight [shell] (the socket closes underneath it) rather than wait —
     * holding shell uid open in the background is the failure mode this guards against.
     */
    fun disconnect()

    // ── Privileged operations ────────────────────────────────────────────────────────────────

    /**
     * Run one shell-uid command. Exit code and output are captured; a transport failure or a
     * dead connection is a typed result, never an exception. See the call-site rule above.
     */
    suspend fun shell(command: String): ShellResult

    /** ADR-001 Phase A one-shot: `pm grant <self> <permission>`. Persists across reboots (V5). */
    suspend fun selfGrant(permission: String): GrantResult

    /**
     * Batched silent install of a single app from one or more staged APK files (ADR-001 V6,
     * ADR-006 split support): one `pm install-create` session into which every [StagedApk] is
     * written, then a single commit. A base APK plus zero or more configuration splits all land
     * in the same session — that is how the platform installs a split app atomically.
     *
     * Each [StagedApk] is streamed over the adb `exec:` stream's stdin into
     * `pm install-write -S <size> .. -`; no shell-readable file path ever exists (ADR-006 P6 — the
     * receiver's app-private staging is not shell-uid-readable, so the bytes are piped, never a path).
     * The [StagedApk.name] is re-validated at this boundary before it reaches `pm install-write`
     * (defence in depth — the name is wire-derived).
     */
    suspend fun installApk(staged: List<StagedApk>): InstallResult

    /**
     * Probe what this device actually allows (ADR-001 V4–V7), one independent check per
     * capability — a single probe failing must not abort the others. Returns the empty set
     * when not connected. Probes are non-invasive (grant-to-self, read-only listings, an
     * immediately-abandoned install session).
     */
    suspend fun probeCapabilities(): Set<PrivilegedCapability>

    // ── Derived convenience ops (typed argv built by ShellArgs — never raw interpolation) ────

    /**
     * NOTE: not the parity data path. Settings parity writes use the normal `Settings.*` API
     * after the one-shot grant (ADR-001). These exist for completeness of the privilege
     * surface (OS-integration parity) and for recovery tooling.
     */
    suspend fun writeSecureSetting(key: String, value: String): OpResult =
        typedOp("settings", "put", "secure", key, value)

    suspend fun writeGlobalSetting(key: String, value: String): OpResult =
        typedOp("settings", "put", "global", key, value)

    /** Tier 1 runtime-permission parity (`pm grant/revoke`). Opt-in, gated in UI. */
    suspend fun grantRuntimePermission(packageName: String, permission: String): OpResult =
        typedOp("pm", "grant", packageName, permission)

    suspend fun revokeRuntimePermission(packageName: String, permission: String): OpResult =
        typedOp("pm", "revoke", packageName, permission)

    /** Switch navigation mode via overlay (`cmd overlay enable-exclusive`, ADR-001 V7). */
    suspend fun setNavigationMode(mode: NavigationMode): OpResult = typedOp(
        "cmd", "overlay", "enable-exclusive",
        "--category", "android.theme.customization.navigation", mode.overlayPackage,
    )

    /** Restore the recorded prior SMS role holder (`cmd role add-role-holder`, ADR-001 V7). */
    suspend fun setSmsRoleHolder(packageName: String): OpResult =
        typedOp("cmd", "role", "add-role-holder", "android.app.role.SMS", packageName)

    /** Build a validated argv, run it, and fold the [ShellResult] into a typed [OpResult]. */
    private suspend fun typedOp(vararg argv: String): OpResult {
        val command = ShellArgs.command(*argv)
            ?: return OpResult.Failed("rejected argument (shell metacharacters)")
        return shell(command).toOpResult()
    }

    // ── Result types ─────────────────────────────────────────────────────────────────────────

    enum class NavigationMode(internal val overlayPackage: String) {
        GESTURAL("com.android.internal.systemui.navbar.gestural"),
        THREE_BUTTON("com.android.internal.systemui.navbar.threebutton"),
    }

    /** What the connected device verifiably allows — gates checklist items and wizard copy. */
    enum class PrivilegedCapability {
        /** Commands run as uid 2000 (shell). Floor for everything else. */
        SHELL,

        /** The WRITE_SECURE_SETTINGS self-grant landed (ADR-001 V4). */
        SETTINGS_SECURE,

        /** `pm` permission ops are reachable (ADR-001 V7, per-permission verdicts at op time). */
        PERMISSION_PARITY,

        /** `pm install-create` sessions open silently (ADR-001 V6). */
        SILENT_INSTALL,

        /** The navigation-mode overlays are present and `cmd overlay` is reachable (V7). */
        NAV_MODE,

        /** The role service is reachable for `cmd role add-role-holder` (V7). */
        SMS_ROLE,
    }

    sealed interface PairingResult {
        data object Paired : PairingResult

        /** The SPAKE2 exchange failed — virtually always a mistyped or expired code. */
        data object WrongCode : PairingResult
        data object Timeout : PairingResult

        /** Pairing endpoint unreachable (dialog closed, Wireless Debugging off, port stale). */
        data class Unavailable(val reason: String) : PairingResult

        /** This build/device cannot pair at all (no Wireless Debugging support). */
        data object Unsupported : PairingResult
    }

    sealed interface ConnectionResult {
        data object Connected : ConnectionResult

        /** No `_adb-tls-connect` endpoint found — Wireless Debugging is off (reboot resets it). */
        data object NoEndpoint : ConnectionResult

        /** adbd refused our key — pairing was lost; a re-pair is needed. */
        data class Rejected(val reason: String) : ConnectionResult
        data object Timeout : ConnectionResult
        data object Unsupported : ConnectionResult
    }

    sealed interface ShellResult {
        /** The command ran; [exitCode] is the command's own status (non-zero is NOT an error here). */
        data class Completed(val exitCode: Int, val stdout: String, val stderr: String) : ShellResult {
            val ok: Boolean get() = exitCode == 0
        }

        data object NotConnected : ShellResult
        data class TransportFailure(val reason: String) : ShellResult
    }

    enum class GrantResult { GRANTED, REJECTED, BRIDGE_UNAVAILABLE }

    /**
     * One staged APK file in an install session: its [name] (the literal `"base"` for the base
     * APK, or a validated split name), its [size] in bytes, and an [open] opener that yields a
     * FRESH [java.io.InputStream] of exactly [size] bytes.
     *
     * P6 design (docs/prp/P6-apk-hardware-runbook.md §Part 2): `pm install-write` runs as shell uid
     * (2000), which cannot read the receiver's app-private staging (SELinux + 0700 dirs). So the
     * bytes are streamed over the adb `exec:` stream's stdin into `pm install-write -S <size> .. -`
     * — no shared on-disk file ever exists. The bridge passes [size] as `-S` and pipes [open]'s
     * stream; the caller owns where the bytes come from.
     */
    data class StagedApk(val name: String, val size: Long, val open: () -> java.io.InputStream)

    sealed interface InstallResult {
        data object Installed : InstallResult
        data class Failed(val reason: String) : InstallResult
        data object BridgeUnavailable : InstallResult
    }

    sealed interface OpResult {
        data object Ok : OpResult
        data class Failed(val reason: String) : OpResult
        data object BridgeUnavailable : OpResult
    }
}

internal fun AdbBridge.ShellResult.toOpResult(): AdbBridge.OpResult = when (this) {
    is AdbBridge.ShellResult.Completed ->
        if (ok) {
            AdbBridge.OpResult.Ok
        } else {
            AdbBridge.OpResult.Failed("exit $exitCode: ${stdout.trim().take(MAX_REASON_CHARS)}")
        }

    is AdbBridge.ShellResult.NotConnected -> AdbBridge.OpResult.BridgeUnavailable
    is AdbBridge.ShellResult.TransportFailure -> AdbBridge.OpResult.BridgeUnavailable
}

private const val MAX_REASON_CHARS = 200
