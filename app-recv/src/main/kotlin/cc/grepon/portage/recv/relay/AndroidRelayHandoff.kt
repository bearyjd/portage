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
import cc.grepon.portage.providers.relay.RelayHeader
import java.io.File

/**
 * Hands the OPAQUE relayed app-backup bytes to a user-accessible location so the user can import them
 * into the target app with their passphrase (PRP-06 §5). portage NEVER imports the file itself and
 * NEVER interprets the bytes — this only writes them out.
 *
 * Scope/security:
 *  - The destination is the receiver app's OWN external files Downloads dir (no storage permission
 *    needed; app-scoped). The file is the USER's, in a location they can open from a file manager or
 *    hand to the app — the brief user-visible copy documented as an accepted residual (PRP-06 §7.2,
 *    §9).
 *  - The on-disk name is GENERATED from the validated package + item kind, NEVER the sender's
 *    display name (path-traversal defense, like every staged item — THREAT_MODEL §2 row 10). The
 *    advisory [RelayHeader.originalName] is display-only and is not used here.
 *  - The opaque bytes are written verbatim; this code never decodes, sniffs, or logs them.
 */
class AndroidRelayHandoff(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Write [opaqueBytes] for [header] to a user-visible, app-scoped location under a generated name.
     * Returns true on success, false on any IO/availability failure (the apply path maps false to a
     * per-item WRITE_ERROR — never a batch abort). [header] is the already-sanitized header.
     */
    fun write(header: RelayHeader, opaqueBytes: ByteArray): Boolean = runCatching {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: appContext.filesDir
        dir.mkdirs()
        // Generated filename: <package>-relay.bin — derived from the VALIDATED package (the regex in
        // RelayHeader.sanitizedOrNull already guarantees it is path-safe), never the display name.
        val file = File(dir, "${header.targetPackage}-relay.bin")
        file.outputStream().use { it.write(opaqueBytes) }
        true
    }.getOrDefault(false)
}
