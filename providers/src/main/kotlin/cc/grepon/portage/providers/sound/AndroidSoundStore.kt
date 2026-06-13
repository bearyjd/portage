/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.sound

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings

/**
 * Thin `RingtoneManager` / `Settings.System` adapter behind [SoundStore] (Tier 0, PRP-04 §3).
 *
 * READ ([read]) uses `getActualDefaultRingtoneUri`, which reads the same `Settings.System` keys
 * (RINGTONE/NOTIFICATION_SOUND/ALARM_ALERT) under the hood — no permission needed. [titleOf]
 * resolves a URI to a human title via `RingtoneManager.getRingtone(...).getTitle(...)`.
 *
 * [resolveBuiltin] is the cross-device safety primitive (PRP-04 §3, §7): it enumerates THIS
 * device's built-in sounds for the role and returns the LOCAL URI whose title matches the carried
 * one (case-insensitively), or null when the target has no equivalent. The receiver therefore
 * never writes a sender-supplied URI verbatim — only a URI resolved from its own catalog.
 *
 * WRITE ([setDefault]) uses `setActualDefaultRingtoneUri`, which needs the user-granted "Modify
 * system settings" special access ([canWrite], `Settings.System.canWrite`). Tier 0 — this is the
 * normal WRITE_SETTINGS special access only; no secure-settings grant and no ADB bridge.
 */
class AndroidSoundStore(private val context: Context) : SoundStore {

    override fun read(role: SoundRole): String? = runCatching {
        RingtoneManager.getActualDefaultRingtoneUri(context, frameworkType(role))?.toString()
    }.getOrNull()

    override fun titleOf(uri: String): String? = runCatching {
        RingtoneManager.getRingtone(context, Uri.parse(uri))
            ?.getTitle(context)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun resolveBuiltin(role: SoundRole, title: String): String? = runCatching {
        val manager = RingtoneManager(context).apply { setType(frameworkType(role)) }
        val cursor = manager.cursor ?: return@runCatching null
        cursor.use {
            var position = 0
            while (it.moveToNext()) {
                val entryTitle = it.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                if (entryTitle != null && entryTitle.equals(title, ignoreCase = true)) {
                    return@runCatching manager.getRingtoneUri(position)?.toString()
                }
                position++
            }
        }
        null
    }.getOrNull()

    override fun canWrite(): Boolean = Settings.System.canWrite(context)

    override fun setDefault(role: SoundRole, uri: String): Boolean = runCatching {
        RingtoneManager.setActualDefaultRingtoneUri(context, frameworkType(role), Uri.parse(uri))
        true
    }.getOrDefault(false)

    /** Map the typed role to the real `RingtoneManager.TYPE_*` (never a raw wire int). */
    private fun frameworkType(role: SoundRole): Int = when (role) {
        SoundRole.RINGTONE -> RingtoneManager.TYPE_RINGTONE
        SoundRole.NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
        SoundRole.ALARM -> RingtoneManager.TYPE_ALARM
    }
}
