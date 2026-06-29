/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.userfile

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.wire.JsonLines
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class UserFileHeader(
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
) {
    fun sanitizedOrNull(): UserFileHeader? {
        if (byteLength < 0L || byteLength > MAX_PAYLOAD_BYTES) return null
        val name = displayName
            .filter { !it.isISOControl() && it != '/' && it != '\\' }
            .trim()
            .take(MAX_NAME_LENGTH)
            .ifBlank { "Transferred file" }
        val mime = mimeType.trim().lowercase()
        if (!MIME.matches(mime)) return null
        return copy(displayName = name, mimeType = mime)
    }

    companion object {
        const val MAX_NAME_LENGTH = 160
        const val MAX_FILES_PER_TRANSFER = 64
        const val MAX_PAYLOAD_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_HEADER_FRAME_BYTES = 4L * 1024 + 1
        const val MAX_ITEM_BYTES = MAX_PAYLOAD_BYTES + MAX_HEADER_FRAME_BYTES
        private val MIME = Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")
    }
}

object UserFileCodec {
    private const val MAX_HEADER_BYTES = 4 * 1024
    private const val CHUNK = 16 * 1024

    fun writeHeader(sink: OutputStream, header: UserFileHeader) {
        sink.write(
            JsonLines.format.encodeToString(UserFileHeader.serializer(), header)
                .toByteArray(Charsets.UTF_8),
        )
        sink.write('\n'.code)
    }

    fun readHeader(source: InputStream): UserFileHeader? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < MAX_HEADER_BYTES) {
            val value = source.read()
            if (value == -1) return null
            if (value == '\n'.code) {
                if (bytes.isEmpty()) return null
                return runCatching {
                    JsonLines.format.decodeFromString(
                        UserFileHeader.serializer(),
                        String(bytes.toByteArray(), Charsets.UTF_8),
                    )
                }.getOrNull()
            }
            bytes += value.toByte()
        }
        return null
    }

    fun stream(source: InputStream, sink: OutputStream, expectedBytes: Long): Long {
        val buffer = ByteArray(CHUNK)
        var remaining = expectedBytes
        var written = 0L
        while (remaining > 0) {
            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count <= 0) break
            sink.write(buffer, 0, count)
            remaining -= count
            written += count
        }
        return written
    }
}

class UserFileExportProvider(
    private val header: UserFileHeader,
    private val openFile: () -> InputStream,
) : ExportProvider {
    override val kind = ItemKind.USER_FILE
    override val displayName = header.displayName
    override val group = "Files"

    override suspend fun available() = header.sanitizedOrNull() != null

    override suspend fun exportTo(sink: OutputStream) {
        val safe = header.sanitizedOrNull() ?: return
        UserFileCodec.writeHeader(sink, safe)
        openFile().use { source ->
            val copied = UserFileCodec.stream(source, sink, safe.byteLength)
            check(copied == safe.byteLength) { "picked file length changed before transfer" }
            check(source.read() == -1) { "picked file grew before transfer" }
        }
        sink.flush()
    }
}

data class UserFileReceipt(
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
)

class UserFileApplyProvider(
    private val writeFile: (UserFileHeader, InputStream) -> Boolean,
    private val onReceived: (UserFileReceipt) -> Unit = {},
) : ApplyProvider {
    override val kind = ItemKind.USER_FILE

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val header = UserFileCodec.readHeader(source)?.sanitizedOrNull()
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "invalid transferred-file header")
        if (!runCatching { writeFile(header, source) }.getOrDefault(false)) {
            return ApplyOutcome(ItemStatus.WRITE_ERROR, "could not save transferred file")
        }
        onReceived(UserFileReceipt(header.displayName, header.mimeType, header.byteLength))
        return ApplyOutcome(ItemStatus.OK, "saved ${header.displayName} to Downloads/Portage")
    }
}
