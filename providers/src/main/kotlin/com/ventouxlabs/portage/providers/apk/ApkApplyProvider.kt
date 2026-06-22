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
class ApkApplyProvider(
    private val stagingDir: File,
    private val targetConfig: () -> ApkTargetConfig,
    private val installedVersions: InstalledPackageVersions = InstalledPackageVersions.None,
    private val silentInstaller: ApkSilentInstaller = ApkSilentInstaller.Deferred,
    private val hasSilentInstall: () -> Boolean = { false },
    private val onApkInstall: (ApkInstallAction) -> Unit,
    /**
     * Surfaces an "incompatible on this device — install from store" deep link (ADR-006 D3 step 2),
     * reusing the inventory reinstall list. Optional: a null sink just means no store fallback row is
     * shown for an incompatible app (the outcome detail still reports it).
     */
    private val onStoreFallback: ((packageName: String, label: String) -> Unit)? = null,
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

            // Capability branch: try the silent (privileged) path only when probed present; Deferred /
            // BridgeUnavailable fall through to Tier-0, and an absent capability goes straight to Tier-0.
            if (hasSilentInstall()) {
                when (val result = silentInstaller.install(header.packageName, keptFiles)) {
                    is ApkInstallResult.Installed ->
                        return ApplyOutcome(ItemStatus.OK, "installed ${header.packageName} silently")
                    is ApkInstallResult.Failed ->
                        return ApplyOutcome(ItemStatus.WRITE_ERROR, "silent install failed: ${result.reason}")
                    is ApkInstallResult.Deferred,
                    is ApkInstallResult.BridgeUnavailable -> Unit // fall through to Tier-0
                }
            }

            // Tier-0: emit the install action; the app-recv PackageInstaller adapter fires the system
            // confirm UI. The provider's success here is "install prompt surfaced", not "installed".
            onApkInstall(ApkInstallAction(header.packageName, header.packageName, keptFiles))
            return ApplyOutcome(ItemStatus.OK, "ready to install ${header.packageName} — confirm on the next screen")
        } finally {
            // Stage → act → wipe: drop the staged splits on every path. The Tier-0 PackageInstaller
            // adapter copies the bytes it needs into its own session synchronously inside onApkInstall,
            // so the staged files are safe to wipe once apply returns.
            appDir.deleteRecursively()
        }
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
