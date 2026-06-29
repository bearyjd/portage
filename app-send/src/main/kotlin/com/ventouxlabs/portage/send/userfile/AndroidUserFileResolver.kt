/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.userfile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.ventouxlabs.portage.providers.userfile.UserFileHeader
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Resolves an explicit SAF selection without retaining or buffering its contents. */
class AndroidUserFileResolver(context: Context) {
    private val appContext = context.applicationContext

    fun resolve(uri: Uri): PickedUserFile? {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val (rawName, queriedSize) = queryNameAndSize(uri)
        val size = if (queriedSize >= 0) queriedSize else countBytes(uri)
        val mimeType = appContext.contentResolver.getType(uri)
            ?.lowercase()
            ?.takeIf { UserFileHeader("x", it, 1).sanitizedOrNull() != null }
            ?: "application/octet-stream"
        val header = UserFileHeader(
            displayName = rawName ?: "Transferred file",
            mimeType = mimeType,
            byteLength = size,
        ).sanitizedOrNull() ?: run {
            release(uri)
            return null
        }

        return PickedUserFile(
            pickId = NEXT_PICK_ID.getAndIncrement(),
            displayName = header.displayName,
            mimeType = header.mimeType,
            byteLength = header.byteLength,
            openStream = {
                appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("could not open picked file")
            },
            releaseGrant = { release(uri) },
        )
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
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
            appContext.contentResolver.openInputStream(uri)?.use { source ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > UserFileHeader.MAX_PAYLOAD_BYTES) return@use total
                }
                total
            } ?: -1L
        }.getOrDefault(-1L)

    private fun release(uri: Uri) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        val NEXT_PICK_ID = AtomicLong(1L)
    }
}
