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

import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.providers.apk.ApkExportProvider
import cc.grepon.portage.providers.apk.InstalledApp
import cc.grepon.portage.providers.apk.installedAppApkProviders

/**
 * CARRY THE APP ITSELF (ADR-006 Phase 1b). The sender-side staging path for user-SELECTED installed
 * apps. Unlike the relay path (where the USER exports an opaque backup file), the export here is a
 * READ-ONLY `PackageManager` enumeration + file read of the app's OWN split-APK set — NO privilege,
 * NO ADB bridge, NO new escalation surface. Selection is sender-side: only the apps the user picked
 * become providers, so only those get staged, hashed, and manifested.
 *
 * This is the direct analogue of [cc.grepon.portage.send.relay.relayExportProviders]: it turns the
 * user's picks into [ApkExportProvider]s ready to append to the sender's provider list, so
 * [cc.grepon.portage.send.transfer.ManifestBuilder] stages each as its own item (distinct id + distinct
 * staging file). The pure provider-building logic lives in `:providers`
 * ([installedAppApkProviders]) so it stays JVM-testable; this is the thin app-send seam over it.
 */
fun apkExportProviders(selectedApps: List<InstalledApp>): List<ExportProvider> =
    installedAppApkProviders(selectedApps)
