/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import cc.grepon.portage.providers.apk.ApkInstallAction
import cc.grepon.portage.providers.apk.ApkInstallFile
import cc.grepon.portage.providers.apk.ApkInstallResult
import cc.grepon.portage.providers.apk.ApkSilentInstaller
import cc.grepon.portage.providers.apk.ApkTargetConfig
import cc.grepon.portage.providers.apk.InstalledPackageVersions
import java.io.OutputStream

/**
 * app-recv install adapters for the APK keystone (ADR-006 D6). All Android- and privilege-specific
 * concerns the `:providers` [cc.grepon.portage.providers.apk.ApkApplyProvider] is wired through live
 * here, never in `:providers` (C1/D2 module discipline).
 *
 *  - [deferredSilentInstaller]: the P6-deferred silent path — returns [ApkInstallResult.Deferred] so
 *    every install routes to the Tier-0 `PackageInstaller` fallback.
 *  - [PackageInstallerApkInstaller]: the REAL Tier-0 fallback — turns an [ApkInstallAction] into a
 *    `PackageInstaller` multi-split session and surfaces an [ApkInstallPrompt] to commit on the Done
 *    screen (the system install-confirm UI).
 *  - [androidInstalledPackageVersions] / [androidApkTargetConfig]: the AC-18 version lookup and the
 *    AC-15 target config, read from `PackageManager` / `Build` / `Resources`.
 */

/**
 * The DEFERRED silent installer (ADR-006 D3/D6/LOW-1). Returns [ApkInstallResult.Deferred] for every
 * call: stdin-streaming over the ADB gate (`pm install-write -S <size> .. -`) is the deferred P6 silent
 * path — the receiver's app-private staging is not shell-uid-readable, so the bridge cannot read a
 * staged path and silent install is not yet wired. Until then every install routes through the Tier-0
 * `PackageInstaller` fallback. Do NOT implement stdin streaming here; that is a P6 hardware-session task.
 */
val deferredSilentInstaller: ApkSilentInstaller = ApkSilentInstaller { _, _ -> ApkInstallResult.Deferred }

/**
 * The Tier-0 `PackageInstaller` adapter (ADR-006 D3/D6). On [install] it creates a `MODE_FULL_INSTALL`
 * session, writes base + every reconciled split into it (synchronously, before the apply provider wipes
 * the staged files), seals it, and returns an [ApkInstallPrompt] carrying the session id. The Done-screen
 * tap calls [commit], which fires the system install-confirm UI via an [IntentSender] — our own app
 * committing a session over our own bytes, NO shell uid. A failed write abandons the session so no
 * half-install is left behind.
 *
 * [resultAction] is the broadcast action the committed session reports back to (the receiver registers a
 * receiver for it); it is package-scoped so only this app sees it.
 */
class PackageInstallerApkInstaller(
    private val context: Context,
    private val resultAction: String = "${PACKAGE_INSTALL_RESULT_ACTION_PREFIX}.APK_INSTALL_RESULT",
) {

    private val installer: PackageInstaller get() = context.packageManager.packageInstaller

    /**
     * Stage one app's reconciled splits into a fresh sealed session and return the prompt to commit.
     * Returns null if the session can't be created or a split write fails (the session is abandoned).
     * Runs synchronously inside the apply provider's `onApkInstall` so it reads the staged bytes before
     * stage→act→wipe drops them.
     */
    fun install(action: ApkInstallAction): ApkInstallPrompt? {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(action.packageName) }
        val sessionId = runCatching { installer.createSession(params) }.getOrElse { return null }
        val session = runCatching { installer.openSession(sessionId) }.getOrElse {
            runCatching { installer.abandonSession(sessionId) }
            return null
        }
        val ok = runCatching {
            PackageInstallerApk.writeSplits(SessionWriterAdapter(session), action.files)
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { session.abandon() }
            return null
        }
        runCatching { session.close() }
        return ApkInstallPrompt(action.packageName, action.label, sessionId)
    }

    /**
     * Commit a previously-sealed session (the Done-screen tap), firing the system install-confirm UI.
     * Returns true when the commit was dispatched, false when the session was missing/expired (the
     * caller should surface a brief "couldn't start install — please retry" message on false).
     */
    fun commit(sessionId: Int): Boolean {
        val session = runCatching { installer.openSession(sessionId) }.getOrNull() ?: return false
        val intent = Intent(resultAction).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
        runCatching { session.commit(pending.intentSender) }
        runCatching { session.close() }
        return true
    }

    /**
     * Abandon all uncommitted sessions this app created via [PackageInstaller] (fix 5). Called on
     * reset / return-home and at launch to clean up sealed sessions the user never tapped to commit
     * (e.g. abandoned after seeing the Done screen, or after a process death). Only ever touches this
     * app's OWN sessions ([PackageInstaller.mySessions] is app-scoped). Best-effort: never throws.
     */
    fun abandonUncommittedSessions() {
        runCatching {
            installer.mySessions.forEach { info ->
                runCatching { installer.abandonSession(info.sessionId) }
            }
        }
    }

    /** Bridge a live [PackageInstaller.Session] to the JVM-testable [PackageInstallerApk.SessionWriter]. */
    private class SessionWriterAdapter(private val session: PackageInstaller.Session) :
        PackageInstallerApk.SessionWriter {
        override fun openWrite(name: String, length: Long): OutputStream =
            session.openWrite(name, 0, length)

        override fun fsync(stream: OutputStream) = session.fsync(stream)
    }

    companion object {
        const val PACKAGE_INSTALL_RESULT_ACTION_PREFIX = "cc.grepon.portage.recv.install"
    }
}

/** AC-18 seam (ADR-006 D3): the installed `longVersionCode` for a package, or null when not installed. */
fun androidInstalledPackageVersions(context: Context): InstalledPackageVersions =
    InstalledPackageVersions { packageName ->
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).longVersionCode
        }.getOrNull()
    }

/**
 * AC-15 seam (ADR-006 D3): the target device's install-relevant config, read live at transfer time from
 * `Build.SUPPORTED_ABIS`, the display density bucket, and the configured locales. The ABI names are
 * normalized to the split-tag form (`-` → `_`, e.g. `arm64-v8a` → `arm64_v8a`) so they compare equal to
 * the wire `abi` tags `ApkExportProvider.deriveTags` produces.
 */
fun androidApkTargetConfig(context: Context): () -> ApkTargetConfig = {
    val abis = android.os.Build.SUPPORTED_ABIS.orEmpty().map { it.replace('-', '_') }
    val resources = context.resources
    val density = densityBucket(resources.configuration.densityDpi)
    @Suppress("DEPRECATION")
    val locales = (0 until resources.configuration.locales.size())
        .map { resources.configuration.locales.get(it).language }
        .filter { it.isNotEmpty() }
        .distinct()
    ApkTargetConfig(supportedAbis = abis, densityBucket = density, locales = locales)
}

/**
 * Map a raw densityDpi to the Android config-split density suffix (`deriveTags`' KNOWN_DENSITIES form).
 * Bucket names must align EXACTLY with the sender's KNOWN_DENSITIES in [ApkExportProvider]:
 * ldpi, mdpi, tvdpi, hdpi, xhdpi, xxhdpi, xxxhdpi, nodpi, anydpi.
 * The tvdpi bucket (~213 dpi, used on some tablets) is ordered BEFORE hdpi (<=240) so a 213-dpi
 * device gets "tvdpi" and can receive its tvdpi split rather than falling back to "hdpi".
 * nodpi and anydpi are density-INDEPENDENT and always kept by [ApkReconcile] regardless of bucket;
 * a device never self-reports those as its own density, so they are not emitted here.
 */
private fun densityBucket(densityDpi: Int): String = when {
    densityDpi <= 120 -> "ldpi"
    densityDpi <= 160 -> "mdpi"
    densityDpi <= 213 -> "tvdpi"
    densityDpi <= 240 -> "hdpi"
    densityDpi <= 320 -> "xhdpi"
    densityDpi <= 480 -> "xxhdpi"
    else -> "xxxhdpi"
}
