/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.sound

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.InputStream

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
    private val resolver = context.applicationContext.contentResolver

    override fun read(role: SoundRole): String? = runCatching {
        RingtoneManager.getActualDefaultRingtoneUri(context, frameworkType(role))?.toString()
    }.getOrNull()

    override fun titleOf(uri: String): String? = runCatching {
        RingtoneManager.getRingtone(context, Uri.parse(uri))
            ?.getTitle(context)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun customFile(role: SoundRole, uri: String): SoundFileCandidate? = runCatching {
        val parsed = Uri.parse(uri)
        val title = titleOf(uri)
        if (title != null && resolveBuiltin(role, title) != null) return@runCatching null
        val (name, queriedSize) = queryNameAndSize(parsed)
        val size = if (queriedSize >= 0L) queriedSize else countBytes(parsed)
        val mime = resolver.getType(parsed)
            ?.lowercase()
            ?.takeIf { SoundFileHeader(role, "x", it, 1).sanitizedOrNull() != null }
            ?: "audio/mpeg"
        val header = SoundFileHeader(
            role = role,
            displayName = name ?: title ?: "${role.name.lowercase()}.mp3",
            mimeType = mime,
            byteLength = size,
        ).sanitizedOrNull() ?: return@runCatching null
        SoundFileCandidate(
            role = role,
            displayName = header.displayName,
            mimeType = header.mimeType,
            byteLength = header.byteLength,
            openStream = {
                resolver.openInputStream(parsed)
                    ?: throw java.io.IOException("could not open custom sound")
            },
        )
    }.getOrNull()

    override fun resolveBuiltin(role: SoundRole, title: String): String? = runCatching {
        val manager = RingtoneManager(context).apply { setType(frameworkType(role)) }
        val cursor = manager.cursor ?: return@runCatching null
        cursor.use {
            while (it.moveToNext()) {
                val entryTitle = it.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                if (entryTitle != null && entryTitle.equals(title, ignoreCase = true)) {
                    // Use the cursor's own authoritative position — never a hand-maintained parallel
                    // counter, which would shift on any header/"Silent"/"Default" row reordering.
                    return@runCatching manager.getRingtoneUri(it.position)?.toString()
                }
            }
        }
        null
    }.getOrNull()

    override fun canWrite(): Boolean = Settings.System.canWrite(context)

    override fun setDefault(role: SoundRole, uri: String): Boolean = runCatching {
        RingtoneManager.setActualDefaultRingtoneUri(context, frameworkType(role), Uri.parse(uri))
        true
    }.getOrDefault(false)

    override fun registerSoundFile(header: SoundFileHeader, source: InputStream): String? {
        val safe = header.sanitizedOrNull() ?: return null
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, safe.displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, safe.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Ringtones/Portage")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_RINGTONE, if (safe.role == SoundRole.RINGTONE) 1 else 0)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, if (safe.role == SoundRole.NOTIFICATION) 1 else 0)
            put(MediaStore.Audio.Media.IS_ALARM, if (safe.role == SoundRole.ALARM) 1 else 0)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val complete = runCatching {
            val written = resolver.openOutputStream(uri, "w")?.use { sink ->
                SoundFileCodec.stream(source, sink, safe.byteLength)
            } ?: return@runCatching false
            written == safe.byteLength && source.read() == -1
        }.getOrDefault(false)
        if (!complete) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        val published = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        if (resolver.update(uri, published, null, null) != 1) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri.toString()
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return name to size
    }

    private fun countBytes(uri: Uri): Long =
        runCatching {
            resolver.openInputStream(uri)?.use { source ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > SoundFileHeader.MAX_PAYLOAD_BYTES) return@use total
                }
                total
            } ?: -1L
        }.getOrDefault(-1L)

    /** Map the typed role to the real `RingtoneManager.TYPE_*` (never a raw wire int). */
    private fun frameworkType(role: SoundRole): Int = when (role) {
        SoundRole.RINGTONE -> RingtoneManager.TYPE_RINGTONE
        SoundRole.NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
        SoundRole.ALARM -> RingtoneManager.TYPE_ALARM
    }
}
