/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cc.grepon.portage.providers.bluetooth.AndroidBluetoothStore
import cc.grepon.portage.providers.bluetooth.BtPairingsExportProvider
import cc.grepon.portage.providers.calendar.AndroidCalendarStore
import cc.grepon.portage.providers.calendar.CalendarExportProvider
import cc.grepon.portage.providers.calllog.AndroidCallLogStore
import cc.grepon.portage.providers.calllog.CallLogExportProvider
import cc.grepon.portage.providers.contacts.AndroidContactsStore
import cc.grepon.portage.providers.contacts.ContactsExportProvider
import cc.grepon.portage.providers.inventory.AndroidInventorySource
import cc.grepon.portage.providers.inventory.AppInventoryExportProvider
import cc.grepon.portage.providers.settings.AndroidSecureGlobalSettingsStore
import cc.grepon.portage.providers.settings.AndroidSystemSettingsStore
import cc.grepon.portage.providers.settings.SettingsExportProvider
import cc.grepon.portage.providers.sms.AndroidSmsStore
import cc.grepon.portage.providers.sms.SmsExportProvider
import cc.grepon.portage.providers.sound.AndroidSoundStore
import cc.grepon.portage.providers.sound.SoundSelectionExportProvider
import cc.grepon.portage.providers.wallpaper.AndroidWallpaperStore
import cc.grepon.portage.providers.wallpaper.WallpaperExportProvider
import cc.grepon.portage.providers.wallpaper.WallpaperSurface
import cc.grepon.portage.send.ui.DeviceSummary
import cc.grepon.portage.send.ui.SenderApp
import cc.grepon.portage.send.ui.formatBytes
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exporter entry point (portage-prp-prompt.md §7): "Transfer to new phone" → permissions →
 * pack → QR (trust anchor) → accept one receiver → stream picks → done summary.
 *
 * FLAG_SECURE is held for the WHOLE session, not just the QR screen: the QR carries the
 * one-time PSK and every other screen shows personal-data summaries — none of it belongs
 * in screenshots, the recents thumbnail, or a cast display (PROTOCOL.md §1).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SenderViewModel by viewModels {
        SenderViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        // Sweep staging orphaned by a mid-transfer process death — staged exports are
        // plaintext PII and must never outlive a single session (security review 2026-06-11).
        File(cacheDir, STAGING_DIR).deleteRecursively()
        sweepOrphanedRelayGrantsOnce()
        val summary = deviceSummary()
        setContent {
            SenderApp(viewModel = viewModel, summary = summary)
        }
    }

    private fun deviceSummary(): DeviceSummary {
        val battery = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val freeBytes = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        return DeviceSummary(
            deviceName = deviceName(this),
            batteryPercent = battery,
            freeStorage = formatBytes(freeBytes),
        )
    }

    /**
     * Release SAF read grants orphaned by a process death that struck a relay flow before any
     * release ran (THREAT_MODEL §3.8 — "release-on-start sweep of orphaned persisted grants").
     *
     * Runs exactly ONCE per process: on a cold start no relay pick exists in memory yet (the
     * ViewModel is being constructed fresh), so every persisted grant is necessarily stale and
     * safe to drop — a later pick re-takes its own grant in [AndroidRelayFileResolver]. The
     * cold-start guard is what makes this safe: an activity recreation (e.g. rotation) keeps the
     * ViewModel's live picks AND the grants they still depend on, so we must NOT sweep then.
     *
     * The only persisted grants app-send ever holds are relay READ grants, but the mode flags are
     * read back from each permission so the release matches exactly what was taken. Best-effort and
     * bounded regardless by Android's per-app persisted-grant cap.
     */
    private fun sweepOrphanedRelayGrantsOnce() {
        if (!relayGrantsSwept.compareAndSet(false, true)) return
        // Releasing EVERY persisted grant here is safe only because app-send's sole
        // takePersistableUriPermission site is AndroidRelayFileResolver (relay READ grants). A
        // future feature that persists a grant which must survive a cold start would need this
        // sweep to learn to exclude it.
        contentResolver.persistedUriPermissions.forEach { perm ->
            val modeFlags = (if (perm.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (perm.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            if (modeFlags != 0) {
                runCatching { contentResolver.releasePersistableUriPermission(perm.uri, modeFlags) }
            }
        }
    }

    private companion object {
        // Process-scoped cold-start latch: a config-change recreation keeps the ViewModel's live
        // picks AND the grants they depend on, so the sweep must fire only once, on a fresh process.
        // AtomicBoolean makes the one-way semantics explicit and stays correct off the main thread.
        val relayGrantsSwept = AtomicBoolean(false)
    }
}

/** The user-visible device name; falls back to the model when unset. */
private fun deviceName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

/** Builds the ViewModel with the compiled Tier-0 export set (one provider per kind). */
private class SenderViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val resolver = context.contentResolver
        val providers = listOf(
            ContactsExportProvider(AndroidContactsStore(resolver)),
            CalendarExportProvider(AndroidCalendarStore(resolver)),
            CallLogExportProvider(AndroidCallLogStore(resolver)),
            SmsExportProvider(AndroidSmsStore(resolver)),
            AppInventoryExportProvider(AndroidInventorySource(context.packageManager)),
            // Reads SAFE keys across both namespaces (reads need no grant on either seam).
            SettingsExportProvider(
                AndroidSystemSettingsStore(context),
                AndroidSecureGlobalSettingsStore(context),
            ),
            // Active wallpaper bytes: one provider per surface so ManifestBuilder assigns each
            // its own item id and the receiver applies them independently (PRP-02 §4-5). The LOCK
            // provider's available() returns false when lock mirrors home, so only one WALLPAPER
            // item is emitted in the mirror case.
            WallpaperExportProvider(AndroidWallpaperStore(context), WallpaperSurface.HOME),
            WallpaperExportProvider(AndroidWallpaperStore(context), WallpaperSurface.LOCK),
            // Default ringtone/notification/alarm selections as a tiny text snapshot (PRP-04).
            // Reads need no permission; Phase 1 carries built-in selections only (custom sound
            // FILES are deferred to a follow-up PR). The receiver re-resolves each built-in to a
            // local URI by title, so nothing dangles on a device that lacks the source's sound.
            SoundSelectionExportProvider(AndroidSoundStore(context)),
            // The bonded Bluetooth roster (name + MAC + type/class) via the PUBLIC, NON-PRIVILEGED
            // BluetoothAdapter.getBondedDevices() API, guarded by the normal BLUETOOTH_CONNECT
            // runtime permission (PRP-07 public-API approach — NO ADB bridge, NO escalation). Phase
            // 1 transfers the LIST ONLY; the receiver shows a "re-pair each here" checklist. No link
            // keys are carried (non-transferable) and the roster is never logged. available() is
            // false when BT is off or the permission was denied, so the item self-omits gracefully.
            BtPairingsExportProvider(AndroidBluetoothStore(context)),
        )
        @Suppress("UNCHECKED_CAST")
        return SenderViewModel(
            providers = providers,
            stagingDir = File(context.cacheDir, STAGING_DIR),
            senderName = deviceName(context),
            // The same inventory seam the app-list provider uses — here it detects which relay-capable
            // apps (Signal/Molly/Aegis) are installed so the Home screen can offer to ferry their
            // user-exported backups (PRP-06). No new permission: it reuses QUERY_ALL_PACKAGES.
            inventorySource = AndroidInventorySource(context.packageManager),
        ) as T
    }
}

private const val STAGING_DIR = "portage-staging"
