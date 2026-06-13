/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.relay

import android.content.Context
import android.os.Environment
import cc.grepon.portage.providers.relay.RelayCodec
import cc.grepon.portage.providers.relay.RelayHeader
import java.io.File
import java.io.InputStream

/**
 * Hands the OPAQUE relayed app-backup bytes to a user-accessible location so the user can import
 * them into the target app with their passphrase (PRP-06 §5). portage NEVER imports the file itself
 * and NEVER interprets the bytes — this only streams them out verbatim.
 *
 * Scope/security:
 *  - Destination: the receiver app's OWN external-files Downloads dir (no storage permission
 *    needed; app-scoped). The user can open it from a file manager or hand it to the app directly.
 *    This is the "brief user-visible copy" documented as an accepted residual (PRP-06 §7.2, §9).
 *  - Filename: generated as `<package>-<itemId>-relay.bin` — derived from the VALIDATED package
 *    (safe by [RelayHeader.sanitizedOrNull]'s regex) plus the manifest item id. Including [itemId]
 *    means two relay items for the SAME package (e.g. two Signal backups in one session) produce
 *    DISTINCT files rather than silently overwriting the first (data-loss fix, code review finding).
 *    The advisory [RelayHeader.originalName] is NEVER used as a path (path-traversal defence,
 *    THREAT_MODEL.md §2 row 10).
 *  - The opaque bytes are STREAMED verbatim via [RelayCodec.streamBlob]; never decoded, sniffed, or
 *    logged. A byte-count cross-check against [RelayHeader.byteLength] catches truncated frames; a
 *    partial file is deleted on mismatch so the user never sees a corrupt relay.
 */
class AndroidRelayHandoff(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Stream [blobSource] for [header] to a user-visible, app-scoped location under a generated
     * name that includes [itemId] to prevent same-app overwrite. Returns true on success, false on
     * any IO or byte-count mismatch (the apply path maps false to a per-item WRITE_ERROR — never a
     * batch abort). [header] is the already-sanitized header; [blobSource] is positioned at the
     * first blob byte; [declaredByteLength] is [header.byteLength] forwarded by the apply provider.
     */
    fun write(header: RelayHeader, blobSource: InputStream, declaredByteLength: Long, itemId: Int): Boolean =
        runCatching {
            val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: appContext.filesDir
            dir.mkdirs()
            // Generated filename: <package>-<itemId>-relay.bin
            //  - package is already validated path-safe by RelayHeader.sanitizedOrNull
            //  - itemId disambiguates multiple relay items for the same package in one session
            //  - never the display name (path-traversal defence)
            val file = File(dir, "${header.targetPackage}-${itemId}-relay.bin")
            val written = file.outputStream().use { out ->
                RelayCodec.streamBlob(blobSource, out, declaredByteLength)
            }
            if (written != declaredByteLength) {
                file.delete() // delete partial on mismatch — never leave a corrupt relay
                return@runCatching false
            }
            true
        }.getOrDefault(false)
}
