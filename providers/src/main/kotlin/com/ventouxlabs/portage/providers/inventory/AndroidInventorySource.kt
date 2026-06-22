/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.inventory

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Thin PackageManager adapter behind [InventorySource]. "User app" = not a system app and
 * not an updated system app. Needs QUERY_ALL_PACKAGES (install-time; declared in
 * app-send's manifest, confirmed normal-grant on GOS per ADR-001).
 */
class AndroidInventorySource(private val packageManager: PackageManager) : InventorySource {

    override fun installedUserApps(): List<AppRecord> =
        packageManager.getInstalledApplications(0)
            .filter { isUserApp(it) }
            .mapNotNull { app -> runCatching { toRecord(app) }.getOrNull() }
            .sortedBy { it.label.lowercase() }

    // Intentionally the FULL package set (system apps included), unlike installedUserApps():
    // if an inventory entry exists on the new device as a system app, reinstall is moot.
    override fun installedPackageNames(): Set<String> =
        packageManager.getInstalledApplications(0).map { it.packageName }.toSet()

    private fun isUserApp(app: ApplicationInfo): Boolean =
        app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    private fun toRecord(app: ApplicationInfo): AppRecord {
        val packageInfo = packageManager.getPackageInfo(app.packageName, 0)
        val installer = runCatching {
            packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        }.getOrNull()
        return AppRecord(
            packageName = app.packageName,
            versionCode = packageInfo.longVersionCode,
            installer = installer,
            label = app.loadLabel(packageManager).toString(),
        )
    }
}
