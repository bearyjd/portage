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
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import cc.grepon.portage.providers.apk.InstalledApp
import cc.grepon.portage.providers.apk.InstalledApkFile
import cc.grepon.portage.providers.apk.InstalledAppSource
import cc.grepon.portage.providers.apk.isUserAppFlags
import cc.grepon.portage.providers.permission.PermissionCapture
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
        val packageInfo = packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
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
            grantedRuntimePermissions = capturedPermissions(packageInfo),
        )
    }

    /**
     * The source app's granted, parity-relevant permissions (ADR-006 D5, Phase 5b). Reads the
     * requested-permission list + per-index GRANTED flags from [PackageInfo], tags each with whether its
     * `protectionLevel` is `dangerous`, then lets the PURE [PermissionCapture] filter decide what to keep
     * (granted AND (dangerous OR a GOS special); signature/system/normal dropped). READ-ONLY — a
     * `PackageManager` query, NO runtime grant, NO privilege, NO ADB bridge.
     */
    private fun capturedPermissions(info: PackageInfo): List<String> {
        val requested = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: return emptyList()
        val tuples = requested.mapIndexed { i, name ->
            val granted = i < flags.size &&
                (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            // A GOS special is captured regardless of protection level → skip its per-perm IPC.
            val dangerous = name !in PermissionCapture.NETWORK_SENSOR_SPECIALS && isDangerous(name)
            PermissionCapture.RequestedPermission(name = name, granted = granted, dangerous = dangerous)
        }
        return PermissionCapture.capturable(tuples)
    }

    /** Per-permission `protectionLevel == dangerous` memo (stable values; see [isDangerous]). */
    private val dangerousCache = HashMap<String, Boolean>()

    /**
     * True iff [permission]'s declared `protectionLevel` is `dangerous`. Memoized across the inventory
     * pass — the same platform perms recur across many apps and a protection level is stable — so each
     * distinct permission costs at most one `getPermissionInfo` IPC. Unknown/unreadable → false (drop).
     */
    private fun isDangerous(permission: String): Boolean = dangerousCache.getOrPut(permission) {
        runCatching {
            packageManager.getPermissionInfo(permission, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
        }.getOrDefault(false)
    }

    /** Materialize one path into an [InstalledApkFile], or null if it is missing/empty/unreadable. */
    private fun apkFileOrNull(path: String): InstalledApkFile? {
        val file = File(path)
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return null
        return InstalledApkFile(name = file.name, absolutePath = file.absolutePath, length = length)
    }
}
