/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.files

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.ventouxlabs.portage.providers.userfile.UserFileCodec
import com.ventouxlabs.portage.providers.userfile.UserFileHeader
import java.io.InputStream

/** Writes an explicitly selected incoming file to the public Downloads/Portage collection. */
class AndroidUserFileStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    init {
        // A process death can interrupt between insert(IS_PENDING=1) and publish/delete. This store
        // is created before a transfer starts, so sweep only this app's stale Portage pending rows.
        runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.IS_PENDING} = 1 AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf("Download/Portage/"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0),
                    )
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
    }

    fun write(header: UserFileHeader, source: InputStream): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, header.displayName)
            put(MediaStore.Downloads.MIME_TYPE, header.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Portage")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        val complete = runCatching {
            val written = resolver.openOutputStream(uri, "w")?.use { sink ->
                UserFileCodec.stream(source, sink, header.byteLength)
            } ?: return@runCatching false
            written == header.byteLength && source.read() == -1
        }.getOrDefault(false)
        if (!complete) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        if (resolver.update(uri, published, null, null) != 1) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        return true
    }
}
