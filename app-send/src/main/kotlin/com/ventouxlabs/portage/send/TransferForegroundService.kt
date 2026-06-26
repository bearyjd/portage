/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the sender process at foreground importance and the radio/CPU awake for the whole transfer
 * data phase, so a screen-off (GrapheneOS Doze / Wi-Fi power-save) can't suspend the app and let the
 * peer read an RST mid-frame, or throttle throughput to a crawl (#85). Three layers: the foreground
 * importance is the primary keep-alive; a PARTIAL_WAKE_LOCK keeps the CPU running the socket I/O; and
 * a WifiLock (HIGH_PERF) keeps the Wi-Fi radio out of power-save so a large transfer doesn't slow to
 * ~0 while the screen is off (hardware-observed on #85 — a 505 MB transfer trickled at ~0.8 MB/s on a
 * multi-Gbps link without it, risking the data-phase cap). All released in [onDestroy].
 *
 * Not exported, not bound, takes no commands: it is started/stopped ONLY in-process by
 * [ForegroundServiceKeepAlive] around the data phase. POST_NOTIFICATIONS is denied-by-default on
 * GrapheneOS, so the ongoing notification may not render — the keep-alive benefit is independent of
 * whether the notification is shown.
 */
class TransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote within the platform's ~5 s window. If promotion throws (a null NotificationManager,
        // or a future OS restriction), degrade to NO keep-alive — the SAFE direction, exactly the
        // pre-fix behaviour — instead of crashing the process or holding locks with no FGS.
        val promoted = runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }.onFailure { Log.w(TAG, "startForeground failed; transfer continues without keep-alive", it) }
            .isSuccess
        if (!promoted) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Acquire only AFTER a successful promotion, so the locks are bound to the foreground window —
        // never held by a bare, un-promoted service instance.
        acquireLocks()
        // Not sticky: if the system kills us mid-transfer the socket is already gone — never relaunch
        // a bare keep-alive service with no transfer behind it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Acquire the keep-alive locks after a successful foreground promotion. The PARTIAL_WAKE_LOCK keeps
     * the CPU running the socket I/O; the WifiLock keeps the radio at full power so throughput survives a
     * screen-off (#85). Both guard against a double-acquire (a second onStartCommand) and are released in
     * [onDestroy]; a process kill releases them regardless.
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                ?.apply {
                    setReferenceCounted(false)
                    // Belt against a leaked hold; the real release is onDestroy (a process kill releases
                    // it regardless). Above the data-phase effective ceiling (the 60 min aggregate cap
                    // plus one ~10 min per-read soTimeout), so it only fires on a genuinely leaked hold.
                    acquire(WAKELOCK_TIMEOUT_MS)
                }
        }
        if (wifiLock?.isHeld != true) {
            // WIFI_MODE_FULL_HIGH_PERF is deprecated (API 29) but is the mode that keeps the radio at FULL
            // power with the SCREEN OFF; the replacement FULL_LOW_LATENCY is documented as foreground-
            // Activity-only, which a screen-off FGS does not satisfy — so HIGH_PERF is the correct fit
            // here. WifiLock has no acquire-timeout overload; released in onDestroy and on process death.
            // Needs the manifest ACCESS_WIFI_STATE + WAKE_LOCK perms (both NORMAL, both declared); without
            // them acquire() is inert, so keep them if this lock stays. Wrapped so any unexpected throw
            // degrades to "no wifi lock" (the wakelock + FGS still hold) — SAFE — not a service crash.
            wifiLock = runCatching {
                @Suppress("DEPRECATION")
                applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFILOCK_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.onFailure { Log.w(TAG, "wifi lock acquire failed; transfer continues without it", it) }
                .getOrNull()
        }
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
            },
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sending with portage")
            .setContentText("Keep the phones close until it finishes.")
            .setOngoing(true)
            .setLocalOnly(true)
            .build()
    }

    private companion object {
        const val TAG = "PortageKeepAlive"
        const val CHANNEL_ID = "portage_transfer"
        const val CHANNEL_NAME = "Transfer in progress"
        const val CHANNEL_DESCRIPTION =
            "Keeps a device-to-device transfer running while the screen is off."
        const val NOTIFICATION_ID = 0x70 // 'p'
        const val WAKELOCK_TAG = "portage:transfer"
        const val WIFILOCK_TAG = "portage:transfer-wifi"
        const val WAKELOCK_TIMEOUT_MS = 90L * 60L * 1000L // 90 min leak-belt (> the data-phase ceiling)
    }
}

/**
 * Android [TransferKeepAlive] backed by [TransferForegroundService]: [start] launches the foreground
 * service (priority + wakelock + wifilock), [stop] tears it down. Both are idempotent and never throw —
 * a failed start degrades to no keep-alive (today's behaviour) and a stop with no running service is a
 * no-op. Started from a foreground Activity (the user tapped "send"), so the background-FGS-start
 * restrictions do not apply.
 */
class ForegroundServiceKeepAlive(context: Context) : TransferKeepAlive {

    private val appContext = context.applicationContext
    private val intent = Intent(appContext, TransferForegroundService::class.java)

    override fun start() {
        runCatching { appContext.startForegroundService(intent) }
            .onFailure { Log.w(TAG, "keep-alive start failed; transfer continues without it", it) }
    }

    override fun stop() {
        runCatching { appContext.stopService(intent) }
            .onFailure { Log.w(TAG, "keep-alive stop failed", it) }
    }

    private companion object {
        const val TAG = "PortageKeepAlive"
    }
}
