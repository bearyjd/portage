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

import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.userfile.UserFileExportProvider
import com.ventouxlabs.portage.providers.userfile.UserFileHeader
import java.io.InputStream

/** Android-free coordinates for one document explicitly selected through SAF. */
data class PickedUserFile(
    val pickId: Long,
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
    val openStream: () -> InputStream,
    val releaseGrant: () -> Unit = {},
)

fun userFileExportProviders(files: List<PickedUserFile>): List<ExportProvider> =
    files.map { file ->
        UserFileExportProvider(
            header = UserFileHeader(file.displayName, file.mimeType, file.byteLength),
            openFile = file.openStream,
        )
    }
