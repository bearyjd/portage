/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.relay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.ventouxlabs.portage.providers.relay.RelayApp
import java.util.concurrent.atomic.AtomicLong

/**
 * Resolves a SAF-picked `content://` [Uri] into a pure [RelayFile] the JVM-testable ViewModel can
 * carry (PRP-06 §3). No new permission is needed: SAF (`ACTION_OPEN_DOCUMENT`) grants per-Uri read
 * access mediated by the USER's pick. portage reads only the file's display name and length here —
 * NEVER the contents; the opaque bytes are opened lazily ([RelayFile.openStream]) and streamed
 * verbatim into the relay item at export time, never buffered or interpreted.
 *
 * The [openStream] lambda re-opens the Uri each time (the export provider opens it exactly once). The
 * read grant is taken PERSISTABLE here so the pick survives an activity recreation; if the grant has
 * still been revoked by transfer time, the open throws — the VM flags that pick "expired" and excludes
 * it (it never silently self-omits, and never crashes).
 *
 * [resolve] may stream the whole file to count bytes when SAF omits SIZE, so it MUST be called off the
 * main thread — the VM does that on [kotlinx.coroutines.Dispatchers.IO].
 */
class AndroidRelayFileResolver(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Build a [RelayFile] for [uri] targeting [app], or null if the file is unreadable/empty (the
     * caller drops a null pick). [targetPackage] is the candidate's canonical package; the receiver
     * re-validates it against [app]. The restore note defaults per app ([RelayRestoreNotes]). The
     * display name is sanitized + bounded to the receiver's name limit before it becomes the wire
     * [RelayFile.originalName]. Takes (and hands back a release seam for) a persistable read grant.
     */
    fun resolve(uri: Uri, app: RelayApp, targetPackage: String): RelayFile? {
        // Persist the read grant so the pick keeps working across an activity recreation (best-effort:
        // some providers refuse persistable grants — then the in-process grant still serves this run).
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val (displayName, byteLength) = queryNameAndSize(uri)
        if (byteLength <= 0L) return null
        return RelayFile(
            pickId = NEXT_PICK_ID.getAndIncrement(),
            app = app,
            targetPackage = targetPackage,
            originalName = sanitizeRelayDisplayName(displayName) ?: "App backup",
            restoreNote = RelayRestoreNotes.defaultFor(app),
            byteLength = byteLength,
            // Re-open lazily; the resolver never holds an open stream or the bytes.
            openStream = {
                appContext.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("could not open the picked relay file")
            },
            // Drop the persistable grant when the pick is removed / on reset / after a successful send.
            releaseGrant = {
                runCatching {
                    appContext.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            },
        )
    }

    /** Read SAF's display name + size columns; both degrade to safe defaults if absent. */
    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        // OpenableColumns.SIZE is OPTIONAL in the SAF contract — a document provider may return null
        // for it. The relay header MUST carry a real byteLength (the receiver cross-checks it against
        // the streamed bytes), so when the column is absent we count the length by streaming once. The
        // bytes are still never interpreted — only counted.
        if (size < 0L) size = runCatching { countBytes(uri) }.getOrDefault(0L)
        return name to size
    }

    private fun countBytes(uri: Uri): Long =
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            var total = 0L
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n: Int = stream.read(buf)
                if (n < 0) break
                total += n
            }
            total
        } ?: 0L

    private companion object {
        /** Process-wide monotonic pick id so two SAF picks never collide on a Compose list key. */
        val NEXT_PICK_ID = AtomicLong(1L)
    }
}
