/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send.apk

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import cc.grepon.portage.providers.apk.InstalledApp
import cc.grepon.portage.providers.apk.InstalledApkFile
import cc.grepon.portage.providers.apk.InstalledAppSource
import cc.grepon.portage.providers.apk.isUserAppFlags
import java.io.File

/**
 * Thin `PackageManager` adapter behind [InstalledAppSource] (ADR-006 Phase 1b). "User app" = not a
 * system app and not an updated system app — the SAME definition the inventory seam uses
 * ([cc.grepon.portage.providers.inventory.AndroidInventorySource]). Enumerating apps and reading their
 * own `sourceDir` + `splitSourceDirs` APK files is a READ-ONLY operation requiring only the install-time
 * QUERY_ALL_PACKAGES (declared in app-send's manifest, normal-grant on GOS per ADR-001): NO runtime
 * permission, NO privilege, NO ADB bridge.
 *
 * A file whose path resolves to a non-existent/zero-length file is dropped here; an app left with no
 * BASE file then self-omits at staging time (the provider's `available()` gate), so a partial set never
 * ships a broken half-container. Lives in app-send (not `:providers`) because it touches
 * `android.content.pm`, keeping `:providers` Android-type-free.
 */
class AndroidInstalledAppSource(private val packageManager: PackageManager) : InstalledAppSource {

    override fun installedUserApps(): List<InstalledApp> =
        packageManager.getInstalledApplications(0)
            .filter { isUserApp(it) }
            .mapNotNull { app -> runCatching { toInstalledApp(app) }.getOrNull() }
            .filter { it.files.isNotEmpty() }
            .sortedBy { it.label.lowercase() }

    private fun isUserApp(app: ApplicationInfo): Boolean = isUserAppFlags(app.flags)

    private fun toInstalledApp(app: ApplicationInfo): InstalledApp {
        val packageInfo = packageManager.getPackageInfo(app.packageName, 0)
        val paths = buildList {
            app.sourceDir?.let { add(it) }
            app.splitSourceDirs?.forEach { split -> add(split) }
        }
        val files = paths.mapNotNull { path -> apkFileOrNull(path) }
        return InstalledApp(
            packageName = app.packageName,
            label = app.loadLabel(packageManager).toString(),
            versionCode = packageInfo.longVersionCode,
            files = files,
        )
    }

    /** Materialize one path into an [InstalledApkFile], or null if it is missing/empty/unreadable. */
    private fun apkFileOrNull(path: String): InstalledApkFile? {
        val file = File(path)
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return null
        return InstalledApkFile(name = file.name, absolutePath = file.absolutePath, length = length)
    }
}
