/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.apk

import java.io.InputStream

/**
 * One installed user app the sender can offer to carry as its split-APK set (ADR-006 Phase 1b). A pure
 * value record: it carries the app's identity ([packageName], [label], [versionCode]) plus the list of
 * its on-disk APK [files] — each a coordinate ([InstalledApkFile.absolutePath]) and a length, never the
 * bytes. The Android `PackageManager` adapter ([InstalledAppSource]) produces these from
 * `applicationInfo.sourceDir` + `splitSourceDirs`; everything downstream stays JVM-testable because the
 * record holds plain values, not Android types.
 *
 * [versionCode] is the full `longVersionCode` (versionCodeMajor << 32 | versionCode). [totalBytes] sums every file's
 * length — the value the Home screen shows per app and folds into the running "apps to carry" total so
 * the user is never surprised by a multi-GB pick.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val files: List<InstalledApkFile>,
    /**
     * The source app's GRANTED runtime/special permissions to carry for parity (ADR-006 D5, Phase 5b),
     * already protection-level-filtered by [cc.grepon.portage.providers.permission.PermissionCapture]
     * (signature/system dropped) in the app-send adapter. Defaults empty (e.g. tests / inventory path).
     */
    val grantedRuntimePermissions: List<String> = emptyList(),
) {
    /** The on-disk size of the whole split set — the glance value shown next to the app. */
    val totalBytes: Long get() = files.sumOf { it.length }
}

/**
 * One APK file on disk belonging to an [InstalledApp]: the source [name] (e.g. `"base.apk"` or a split
 * file name), its [absolutePath] (used only to open a read stream — never sent on the wire), and its
 * [length] in bytes. The split role/tags are re-derived from [name] via [deriveTags] when the provider
 * is built, so this record stays a thin filesystem fact.
 */
data class InstalledApkFile(
    val name: String,
    val absolutePath: String,
    val length: Long,
)

/**
 * The `PackageManager` seam for enumerating installed user apps and their APK files (ADR-006 Phase 1b).
 * Mirrors [cc.grepon.portage.providers.inventory.InventorySource]: an Android adapter implements it in
 * app-send, tests use a fake. Listing packages + reading their own APK files needs no runtime grant on
 * GOS (QUERY_ALL_PACKAGES is install-time, ADR-001); the read is the normal `PackageManager` +
 * file-read path, NO privilege, NO ADB bridge.
 */
interface InstalledAppSource {
    /** Every user-installed app (system apps excluded), each with its split-APK file list. */
    fun installedUserApps(): List<InstalledApp>
}

/**
 * Build one [ApkExportProvider] per [InstalledApp] — the pure, JVM-testable core of the sender's
 * "apps to carry" path (ADR-006 Phase 1b). Each app's files become [ApkSourceFile]s whose split tags
 * are re-derived from the file name by [deriveTags] (BASE/CONFIG/LANGUAGE/FEATURE + abi/density/lang),
 * and whose opener streams the file at [InstalledApkFile.absolutePath] via [openFile]. The opener is
 * injected (defaulting to a real [java.io.FileInputStream]) so this function takes no Android types and
 * tests can supply byte fixtures.
 *
 * [ApkExportProvider]'s `capturedPermissions` is threaded from [InstalledApp.grantedRuntimePermissions]
 * (ADR-006 D5, Phase 5b), already protection-level-filtered at capture time. A provider whose set lacks a
 * BASE file, or carries an empty file, self-omits at staging time (the provider's `available()` gate), so
 * a partial/unreadable app never ships a broken half-container.
 */
fun installedAppApkProviders(
    apps: List<InstalledApp>,
    openFile: (InstalledApkFile) -> InputStream = { java.io.FileInputStream(it.absolutePath) },
): List<ApkExportProvider> =
    apps.map { app ->
        val sources = app.files.map { file ->
            val tags = deriveTags(file.name)
            ApkSourceFile(
                entry = ApkFileEntry(
                    name = baseOrSplitName(file.name, tags.role),
                    role = tags.role,
                    abi = tags.abi,
                    density = tags.density,
                    lang = tags.lang,
                    length = file.length,
                ),
            ) { openFile(file) }
        }
        ApkExportProvider(
            packageName = app.packageName,
            versionCode = app.versionCode,
            appLabel = app.label,
            files = sources,
            capturedPermissions = app.grantedRuntimePermissions,
        )
    }

/**
 * The wire [ApkFileEntry.name] for one file: a BASE file is always the literal [ApkContainerValidation.BASE_NAME]
 * (the codec + receiver require it exactly), while a split keeps its file name with the `.apk` suffix
 * stripped so the receiver re-derives the same tags. Pure string logic, no Android types.
 */
private fun baseOrSplitName(fileName: String, role: ApkFileRole): String =
    if (role == ApkFileRole.BASE) ApkContainerValidation.BASE_NAME else fileName.removeSuffix(".apk")

/**
 * Returns true when [flags] (from `ApplicationInfo.flags`) indicate a user-installed app — that is,
 * the app is NOT a system app and NOT an updated system app. Pure int logic so it is JVM-testable
 * without Android types; [AndroidInstalledAppSource] delegates its `isUserApp` check here.
 *
 * This is the security-relevant filter that gates which apps the sender can offer to carry:
 * a wrong mask would let a system app (e.g. a privileged framework package) appear in the carry
 * list, and could produce a broken/dangerous APK item. The invariant is verified in
 * `InstalledAppsTest.isUserAppFlags_*`.
 */
fun isUserAppFlags(flags: Int): Boolean =
    flags and (FLAG_SYSTEM or FLAG_UPDATED_SYSTEM_APP) == 0

// Plain int copies of ApplicationInfo.FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP — these are stable
// ABI constants (unchanged since API 1 / API 3 respectively) and carry no Android runtime dep,
// keeping this file (and its tests) JVM-only. The values are cross-checked at call sites.
private const val FLAG_SYSTEM = 0x00000001
private const val FLAG_UPDATED_SYSTEM_APP = 0x00000080
