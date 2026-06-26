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

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.permission.PermissionAllowlist
import com.ventouxlabs.portage.providers.permission.PermissionParityPlanner
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Receiver side of [ItemKind.APK] (ADR-006 D1/D3/D6). The keystone-completing apply: decode the framed
 * split container straight off the hash-verified item stream, stage each split to its own temp file,
 * reconcile against the target device, then install — silently via the injected [ApkSilentInstaller]
 * when `SILENT_INSTALL` was probed present, otherwise (and on Deferred / stale-positive) via the Tier-0
 * `PackageInstaller` fallback emitted through [onApkInstall]. Stage → verify → act → wipe is preserved:
 * every failure path wipes the per-split staged files before returning.
 *
 * Module discipline (ADR-006 D2/C1): NO Android types, NO `:adb-bridge` edge. Everything privileged or
 * platform-specific (the silent bridge install, the `PackageInstaller` session, the target config, the
 * installed-version lookup) is INJECTED via the narrow seams in `ApkInstallSeams.kt`. The probed
 * capability set is consumed in `:app-recv`; here we only see [hasSilentInstall] (a supplier, read per
 * apply so a stale wizard probe never forces a wrong silent path) and the typed install result.
 *
 * Security carry-forwards (the codec deferred these to the consumer):
 *  - M1: the streamed byte count for each split is enforced == its declared [ApkFileEntry.length]; a
 *    mismatch is WRITE_ERROR + wipe (truncated/corrupt frame, never a partial install).
 *  - M3: each split [ApkFileEntry.name] is re-validated via [ApkContainerValidation.validatedSplitNameOrNull]
 *    AT THE MOMENT it becomes a staged FILENAME — defence in depth on top of the codec's own validation.
 */
/**
 * The single-sourced marker prefixed to a Tier-0 apply outcome when a silent install was ATTEMPTED but
 * degraded (#86). Its presence vs absence is the (1)-vs-(2) diagnostic signal — keep it one string so the
 * two degraded arms (Deferred / BridgeUnavailable) and the tests that assert on it can't drift apart.
 */
private const val NO_TAP_DEGRADED_NOTE = " — no-tap install unavailable"

class ApkApplyProvider(
    private val stagingDir: File,
    private val targetConfig: () -> ApkTargetConfig,
    private val installedVersions: InstalledPackageVersions = InstalledPackageVersions.None,
    private val silentInstaller: ApkSilentInstaller = ApkSilentInstaller.Deferred,
    private val hasSilentInstall: () -> Boolean = { false },
    /**
     * Runtime-permission parity (ADR-006 D5), executed ONLY on the silent-install success path: after a
     * privileged silent install, re-grant the source device's captured runtime permissions on the target
     * via [permissionGranter], restricted to the planner's `auto` set (which the allowlist constrains to
     * [PermissionAllowlist.DEFAULT_SAFE]). [targetDeclaredPermissions] supplies what the freshly-installed
     * target actually declares — the other half of the `captured ∩ targetDeclared ∩ DEFAULT_SAFE`
     * intersection. Both default to no-op, so the Tier-0 path and any caller that does not wire them grant
     * nothing. Grants are deliberately absent from the Tier-0 fallback: that path has no live bridge to run
     * `pm grant`, and the install has not even completed (it is pending the user's system-confirm tap).
     */
    private val permissionGranter: RuntimePermissionGranter = RuntimePermissionGranter.NoOp,
    private val targetDeclaredPermissions: TargetDeclaredPermissions = TargetDeclaredPermissions.None,
    private val onApkInstall: (ApkInstallAction) -> Unit,
    /**
     * Surfaces an "incompatible on this device — install from store" deep link (ADR-006 D3 step 2),
     * reusing the inventory reinstall list. Optional: a null sink just means no store fallback row is
     * shown for an incompatible app (the outcome detail still reports it).
     */
    private val onStoreFallback: ((packageName: String, label: String) -> Unit)? = null,
    /**
     * Observability side-channel for runtime-permission parity (ADR-006 D5): invoked after a silent
     * install with the package and the permissions actually re-granted (the granter's confirmed subset),
     * so the receiver can surface "restored Network, Sensors" on the Done screen. Display-only — it never
     * influences WHAT is granted (that is the planner + the [PermissionAllowlist.DEFAULT_SAFE] belt) and
     * is not invoked when nothing was restored. Mirrors the [onApkInstall]/[onStoreFallback] emit seams.
     */
    private val onPermissionsRestored: ((packageName: String, permissions: List<String>) -> Unit)? = null,
    /**
     * Opt-in dangerous-permission surface (ADR-006 D5, Phase 5d): invoked after a silent install with the
     * planner's `optIn` set — the DANGEROUS perms the source app held that the target declares (e.g.
     * CAMERA, ACCESS_FINE_LOCATION). DATA ONLY — nothing is granted here; the receiver surfaces these for
     * an EXPLICIT per-item user confirm before any `pm grant` (the grant is the Phase 5d UI slice's job).
     * Emitted only on the silent-install success path (the only path with a live bridge to grant later)
     * and only when `optIn` is non-empty. The planner guarantees `optIn` excludes signature/system
     * ([PermissionAllowlist.NEVER]) and the default-safe set, so this never offers an ungrantable or
     * auto-grantable permission.
     */
    private val onOptInPermissions: ((packageName: String, permissions: List<String>) -> Unit)? = null,
) : ApplyProvider {

    override val kind = ItemKind.APK

    override suspend fun apply(source: InputStream): ApplyOutcome {
        // The per-app staging subdirectory: wiped on EVERY return path (stage → act → wipe).
        val appDir = File(stagingDir, "apk-${System.nanoTime()}").apply { mkdirs() }
        val staged = mutableListOf<StagedSplit>()
        try {
            val header = ApkCodec.readHeaderFrom(source)?.let(ApkContainerValidation::validatedHeaderOrNull)
                ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable or invalid APK container header")

            // Stream each split straight to its own staged file; never materialize the container in
            // memory (an item can be up to 1 GiB, ADR-006 D1/D4).
            repeat(header.fileCount) {
                val entry = ApkCodec.readEntryFrom(source)?.let(ApkContainerValidation::validatedEntryOrNull)
                    ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable or invalid APK file entry")
                // M3: re-validate the split name at the moment it becomes a staged FILENAME.
                val safeName = ApkContainerValidation.validatedSplitNameOrNull(entry.name)
                    ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "rejected split name")
                val splitFile = File(appDir, "$safeName.apk")
                val written = FileOutputStream(splitFile).use { sink ->
                    ApkCodec.streamBlob(source, sink, entry.length)
                }
                // M1: the streamed count MUST equal the declared length — a mismatch is a truncated frame.
                if (written != entry.length) {
                    return ApplyOutcome(ItemStatus.WRITE_ERROR, "APK split length mismatch (truncated frame)")
                }
                staged += StagedSplit(entry, splitFile)
            }

            val entries = ApkContainerValidation.validatedEntriesOrNull(header, staged.map { it.entry })
                ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "invalid APK container (file set)")

            // AC-18: skip an already-installed app at >= the container's versionCode (no downgrade).
            installedVersions.installedVersionCode(header.packageName)?.let { installed ->
                if (installed >= header.versionCode) {
                    return ApplyOutcome(
                        ItemStatus.SKIPPED,
                        "already installed (v$installed ≥ v${header.versionCode}) — not reinstalled",
                    )
                }
            }

            // Derive-never-trust (ADR-006 D3): the install plan is computed from tags RE-DERIVED from
            // each split's validated [ApkFileEntry.name] via [deriveTags], NOT from the sender's wire
            // role/abi/density/lang (which are advisory and could be mislabeled). The validated [name] is
            // the only field reconcile is allowed to trust; the byte payload and length are untouched.
            val derivedEntries = entries.map { it.withDerivedTags() }

            // AC-15: reconcile the split set against the target device before any install attempt.
            val reconcile = ApkReconcile.reconcile(derivedEntries, targetConfig())
            val keptEntries = when (reconcile) {
                is ApkReconcile.Result.Incompatible -> {
                    onStoreFallback?.invoke(header.packageName, header.packageName)
                    return ApplyOutcome(
                        ItemStatus.SKIPPED,
                        "incompatible on this device — install from store (${reconcile.reason})",
                    )
                }
                is ApkReconcile.Result.Compatible -> reconcile.files
            }
            // Name-keyed join: safe because validatedEntriesOrNull now enforces split-name uniqueness
            // (fix 1). The identity-based `===` join was fragile if reconcile ever copied an entry.
            val byName = staged.associateBy { it.entry.name }
            val keptFiles = keptEntries.mapNotNull { byName[it.name]?.toInstallFile() }
            // Invariant: every kept entry must have a matching staged file. A shortfall means a
            // bug in the join or in reconcile producing entries not in the staged set — never attempt
            // a partial install with missing splits.
            if (keptFiles.size != keptEntries.size) {
                return ApplyOutcome(ItemStatus.WRITE_ERROR, "staged split set incomplete after reconcile")
            }

            // Capability branch: try the silent (privileged) path only when probed present. A degraded
            // silent result (Deferred / BridgeUnavailable) falls through to Tier-0 but records WHY in the
            // outcome detail (#86): a wizard-set-up transfer that nonetheless taps shows the reason. The
            // presence of the note vs a plain message distinguishes "no SILENT_INSTALL at apply time"
            // (capability not live — plain message) from "bridge gone at apply time" (note + reason).
            val silentNote: String = if (hasSilentInstall()) {
                when (val result = silentInstaller.install(header.packageName, keptFiles)) {
                    is ApkInstallResult.Installed -> {
                        // Silent install succeeded and the bridge path is live — the ONLY place runtime
                        // permission parity (ADR-006 D5) runs. Best-effort; never downgrades the OK status.
                        val parity = grantRuntimePermissionParity(header.packageName, header.capturedPermissions)
                        return ApplyOutcome(ItemStatus.OK, "installed ${header.packageName} silently$parity")
                    }
                    is ApkInstallResult.Failed ->
                        return ApplyOutcome(ItemStatus.WRITE_ERROR, "silent install failed: ${result.reason}")
                    is ApkInstallResult.Deferred ->
                        "$NO_TAP_DEGRADED_NOTE; used the tap installer"
                    is ApkInstallResult.BridgeUnavailable ->
                        "$NO_TAP_DEGRADED_NOTE (${result.reason}); used the tap installer"
                }
            } else {
                "" // no SILENT_INSTALL capability at apply time — the expected Tier-0 path, no note
            }

            // Tier-0: emit the install action; the app-recv PackageInstaller adapter fires the system
            // confirm UI. The provider's success here is "install prompt surfaced", not "installed".
            onApkInstall(ApkInstallAction(header.packageName, header.packageName, keptFiles))
            return ApplyOutcome(
                ItemStatus.OK,
                "ready to install ${header.packageName} — confirm on the next screen$silentNote",
            )
        } finally {
            // Stage → act → wipe: drop the staged splits on every path. The Tier-0 PackageInstaller
            // adapter copies the bytes it needs into its own session synchronously inside onApkInstall,
            // so the staged files are safe to wipe once apply returns.
            appDir.deleteRecursively()
        }
    }

    /**
     * Runtime-permission parity (ADR-006 D5), best-effort. Returns a human-readable audit suffix for the
     * outcome detail (empty when nothing is granted). Decision is the PURE [PermissionParityPlanner]; the
     * privileged execution is the injected [permissionGranter].
     *
     * Two over-grant guards, defence in depth:
     *  1. Only [PermissionParityPlanner.GrantPlan.auto] is ever executed — `optIn` (dangerous perms) needs
     *     an explicit confirm (Phase 5d) and `skipped` is never granted.
     *  2. A belt re-filter to [PermissionAllowlist.DEFAULT_SAFE] AT THE CALL SITE: even if a planner
     *     regression let a non-default perm into `auto`, this site refuses to hand it to `pm grant`. The
     *     planner already guarantees `auto ⊆ DEFAULT_SAFE`; this makes the grant site safe in isolation.
     *
     * The package name is the header's, already validated against the package grammar
     * ([ApkContainerValidation.validatedHeaderOrNull]); the permission strings are allowlist constants.
     * `AdbBridge.grantRuntimePermission` still re-validates both via `ShellArgs` at the wire boundary.
     */
    private suspend fun grantRuntimePermissionParity(packageName: String, captured: List<String>): String {
        if (captured.isEmpty()) return ""
        val declared = targetDeclaredPermissions.declaredPermissions(packageName)
        val plan = PermissionParityPlanner.plan(captured, declared)
        // Phase 5d: surface the opt-in (dangerous) perms for an explicit user confirm — NEVER granted
        // here. Emitted before the auto-grant so an app with only dangerous perms (empty auto) still
        // offers them.
        if (plan.optIn.isNotEmpty()) onOptInPermissions?.invoke(packageName, plan.optIn)
        val toGrant = plan.auto.filter { it in PermissionAllowlist.DEFAULT_SAFE }
        if (toGrant.isEmpty()) return ""
        val granted = permissionGranter.grant(packageName, toGrant)
        // Preserve the captured order for display; emit the confirmed subset to the Done-screen sink.
        val restored = toGrant.filter { it in granted }
        if (restored.isNotEmpty()) onPermissionsRestored?.invoke(packageName, restored)
        return " — restored ${restored.size}/${toGrant.size} runtime permissions"
    }

    /** One staged split: its validated wire [entry] plus the on-disk file the bytes were streamed to. */
    private class StagedSplit(val entry: ApkFileEntry, val file: File) {
        fun toInstallFile(): ApkInstallFile =
            ApkInstallFile(name = entry.name, length = entry.length, open = { file.inputStream() })
    }
}

/**
 * Re-derive this entry's role/abi/density/lang from its validated [ApkFileEntry.name] (derive-never-trust,
 * ADR-006 D3) using the pure [deriveTags] helper, leaving [ApkFileEntry.name] and [ApkFileEntry.length]
 * intact so the name-keyed join back to staged files still holds. The sender's advisory wire tags are
 * discarded for the install plan.
 */
private fun ApkFileEntry.withDerivedTags(): ApkFileEntry {
    val tags = deriveTags(name)
    return copy(role = tags.role, abi = tags.abi, density = tags.density, lang = tags.lang)
}
