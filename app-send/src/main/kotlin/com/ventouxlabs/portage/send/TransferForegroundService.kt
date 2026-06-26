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
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the sender process at foreground importance and the CPU awake for the whole transfer data
 * phase, so a screen-off (GrapheneOS Doze / Wi-Fi power-save) can't suspend the app and let the
 * peer read an RST mid-frame (#85). The foreground importance is the primary keep-alive; the
 * PARTIAL_WAKE_LOCK additionally keeps the CPU running the socket I/O. Released in [onDestroy].
 *
 * Not exported, not bound, takes no commands: it is started/stopped ONLY in-process by
 * [ForegroundServiceKeepAlive] around the data phase. POST_NOTIFICATIONS is denied-by-default on
 * GrapheneOS, so the ongoing notification may not render — the process-priority + wakelock benefit
 * is independent of whether the notification is shown.
 */
class TransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote within the platform's ~5 s window. If promotion throws (a null NotificationManager,
        // or a future OS restriction), degrade to NO keep-alive — the SAFE direction, exactly the
        // pre-fix behaviour — instead of crashing the process or holding a wakelock with no FGS.
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
        // Acquire only AFTER a successful promotion, so the wakelock is bound to the foreground
        // window — never held by a bare, un-promoted service instance.
        acquireWakeLock()
        // Not sticky: if the system kills us mid-transfer the socket is already gone — never relaunch
        // a bare keep-alive service with no transfer behind it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        // Guard against a double-acquire leaking the first lock if onStartCommand is delivered twice.
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                // Belt against a leaked hold; the real release is onDestroy (and a process kill
                // releases it regardless). Sits above the data-phase effective ceiling (the 60 min
                // aggregate cap plus one ~10 min per-read soTimeout), so it only fires on a genuinely
                // leaked hold, never on a slow-but-live transfer.
                acquire(WAKELOCK_TIMEOUT_MS)
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
        const val WAKELOCK_TIMEOUT_MS = 90L * 60L * 1000L // 90 min leak-belt (> the data-phase ceiling)
    }
}

/**
 * Android [TransferKeepAlive] backed by [TransferForegroundService]: [start] launches the foreground
 * service (priority + wakelock), [stop] tears it down. Both are idempotent and never throw — a
 * failed start degrades to no keep-alive (today's behaviour) and a stop with no running service is a
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
