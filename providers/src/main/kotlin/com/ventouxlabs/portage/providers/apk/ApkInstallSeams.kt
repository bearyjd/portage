/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.apk

import java.io.InputStream

/**
 * The narrow install seams the [ApkApplyProvider] is wired through (ADR-006 D2/D6). Every Android-,
 * privilege-, and `:adb-bridge`-specific concern is INJECTED from `:app-recv` via these value types
 * and fun-interfaces — `:providers` keeps its `:core-model` + `:settings-catalog`-only dependency set
 * and never gains an edge to `:adb-bridge` (the Critic C1 module-boundary hazard). The capability set
 * is consumed in `:app-recv` and never reaches here; the apply provider only sees which seam was
 * injected and, per app, a typed install result.
 */

/**
 * One staged APK file handed to the silent installer (ADR-006 D6). It carries the wire-validated split
 * [name] (the literal `"base"` or a validated split name — never a path), its [length] in bytes, and an
 * [open] opener that yields a FRESH stream of exactly [length] bytes from the per-split staged file.
 *
 * The byte-stream shape (an opener, NOT a device path) is intentional: the eventual silent adapter
 * stdin-streams each split into `pm install-write -S <size> .. -` rather than handing `pm` a path,
 * because the receiver's app-private staging is not shell-uid-readable (ADR-006 D3/LOW-1). Until that
 * lands the adapter returns [ApkInstallResult.Deferred] and the bytes are re-opened by the Tier-0
 * `PackageInstaller` path instead.
 */
class ApkInstallFile(val name: String, val length: Long, val open: () -> InputStream)

/**
 * The result of one silent (privileged) install attempt (ADR-006 D6). [Deferred] and
 * [BridgeUnavailable] both route to the Tier-0 `PackageInstaller` fallback — [Deferred] is the
 * "not wired yet / silent path unavailable" signal the P4 app-recv adapter always returns today,
 * and [BridgeUnavailable] is the stale-positive case where `SILENT_INSTALL` was probed present but the
 * bridge is gone at apply time (ADR-006 D6: degrade to Tier-0 for the remaining apps). [Failed]
 * surfaces a real install rejection (it is NOT silently retried as Tier-0 here; the provider reports it).
 */
sealed interface ApkInstallResult {
    data object Installed : ApkInstallResult
    data class Failed(val reason: String) : ApkInstallResult

    /** The silent path is unavailable (not wired / app-private staging unreadable) — route to Tier-0. */
    data object Deferred : ApkInstallResult

    /**
     * The bridge was probed `SILENT_INSTALL`-present but is gone (or failed to connect) at apply time —
     * route to Tier-0. [reason] is a short, human-readable cause (e.g. "Wireless Debugging is off") so a
     * wizard-set-up transfer that nonetheless taps surfaces WHY rather than degrading silently (#86): the
     * apply provider folds it into the install outcome detail instead of swallowing it.
     */
    data class BridgeUnavailable(val reason: String) : ApkInstallResult
}

/**
 * The silent (privileged, batched) install seam (ADR-006 D6). Implemented in `:app-recv` over the
 * `AdbBridge`; injected here so `:providers` stays privilege-agnostic. The P4 app-recv adapter returns
 * [ApkInstallResult.Deferred] for every call (the silent stdin-streaming path is the deferred P6
 * concern), so every install currently routes to the Tier-0 `PackageInstaller` fallback.
 */
fun interface ApkSilentInstaller {
    suspend fun install(packageName: String, files: List<ApkInstallFile>): ApkInstallResult

    companion object {
        /** The default: always defer to Tier-0. Used wherever no real silent seam is wired (tests, no-grant). */
        val Deferred = ApkSilentInstaller { _, _ -> ApkInstallResult.Deferred }
    }
}

/**
 * The Tier-0 install action the apply provider emits when falling back (ADR-006 D3/D6), mirroring the
 * inventory [com.ventouxlabs.portage.providers.inventory.InstallAction] emit seam. It carries the app's
 * [packageName] + [label] and the reconciled set of staged [files] (base + the kept splits). The
 * `:app-recv` side turns each action into a `PackageInstaller` multi-split session that fires the
 * system install-confirm UI — our own app reading our own staged files, no shell uid (ADR-006 D6).
 */
class ApkInstallAction(
    val packageName: String,
    val label: String,
    val files: List<ApkInstallFile>,
)

/**
 * The target device's install-relevant config (ADR-006 D3/AC-15). Filled in `:app-recv` from
 * `Build.SUPPORTED_ABIS`, the display `densityDpi` bucket, and the configured `locales`, then injected
 * so the PURE [ApkReconcile] function can select the installable split subset without any Android type
 * reaching `:providers`. A supplier (not a snapshot) so the value is read at transfer time.
 */
data class ApkTargetConfig(
    val supportedAbis: List<String>,
    val densityBucket: String,
    val locales: List<String>,
)

/**
 * The installed-package-version seam for the AC-18 already-installed / would-downgrade skip
 * (ADR-006 D3, mirroring the inventory dedup). Returns the installed `longVersionCode` for a package,
 * or null when it is not installed. Implemented in `:app-recv` over `PackageManager`; a fake in tests.
 * Kept a thin, APK-local seam (not folded into the names-only inventory seam) so the apply provider is
 * self-contained.
 */
fun interface InstalledPackageVersions {
    fun installedVersionCode(packageName: String): Long?

    companion object {
        /** The default: nothing installed, so AC-18 never skips. Used in tests / when no source is wired. */
        val None = InstalledPackageVersions { null }
    }
}

/**
 * The set of runtime permissions the freshly-installed TARGET app declares (ADR-006 D5). Read in
 * `:app-recv` from `PackageManager` (`GET_PERMISSIONS` → `requestedPermissions`) AFTER a silent install
 * completes; a fake in tests. It is the second half of the parity intersection: nothing the target does
 * not itself declare can ever be planned for grant ([PermissionParityPlanner] skips it), so a
 * sender-supplied permission name can never cause a `pm grant` of a permission the installed app did
 * not request.
 */
fun interface TargetDeclaredPermissions {
    fun declaredPermissions(packageName: String): Set<String>

    companion object {
        /**
         * The default: the target declares nothing, so the parity planner's `auto` set is empty and NO
         * grant is ever executed. Used in tests and wherever no privileged granter is wired (Tier-0-only).
         */
        val None = TargetDeclaredPermissions { emptySet() }
    }
}

/**
 * The privileged runtime-permission grant seam (ADR-006 D5) — the first production use of
 * `AdbBridge.grantRuntimePermission`. Implemented in `:app-recv`
 * ([com.ventouxlabs.portage.recv.install.AdbRuntimePermissionGranter]) over the `AdbBridge`; a fake in
 * tests. Injected here so `:providers` stays privilege-agnostic (C1/D2 module discipline — no
 * `:adb-bridge` edge).
 *
 * Contract: grant [permissions] to [packageName] over the bridge (`pm grant`), best-effort, and return
 * the subset that were actually granted — that returned set IS the audit record (the caller folds its
 * size into the apply outcome detail; no per-name logging happens in this Android-free layer). It MUST
 * NOT throw and MUST NOT be fatal: a per-permission failure, an unavailable bridge, or a timeout simply
 * omits that permission from the returned set (ADR-006 D5 — a failed `pm grant` is never fatal to the
 * transfer). The implementation assumes EXCLUSIVE use of the bridge for the call and tears down the
 * session it opens (AC-11 — never hold shell uid open). The apply provider only ever passes a set it has
 * already filtered to [PermissionAllowlist.DEFAULT_SAFE] — the granter is the executor, never the policy.
 */
fun interface RuntimePermissionGranter {
    suspend fun grant(packageName: String, permissions: List<String>): Set<String>

    companion object {
        /** The default: grants nothing. Used in tests and wherever no privileged granter is wired. */
        val NoOp = RuntimePermissionGranter { _, _ -> emptySet() }
    }
}
