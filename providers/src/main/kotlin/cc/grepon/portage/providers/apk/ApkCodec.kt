/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.apk

import cc.grepon.portage.providers.wire.JsonLines
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire codec for an APK container, modeled on [cc.grepon.portage.providers.relay.RelayCodec]'s
 * streaming shape (`UTF-8 JSON line + '\n' + bytes`) and applied recursively from one blob to N files
 * (ADR-006 D1). It is structurally similar to RelayCodec, NOT byte-identical: the container framing
 * recurses per file and the line-scan/empty-line handling differ from the single-blob relay path.
 *
 * ```
 * <JSON ApkContainerHeader> '\n'
 *   then, repeated fileCount times:
 *     <JSON ApkFileEntry> '\n'
 *     <entry.length bytes, streamed>
 * ```
 *
 * The production read path uses [readHeaderFrom] + repeated [readEntryFrom]/[streamBlob], copying each
 * file straight to its own staged file — the full container is NEVER materialized in memory (an APK
 * item can be up to 1 GiB, ADR-006 D4). As a defensive cross-check the streamed byte count is compared
 * against each [ApkFileEntry.length]; the caller rejects a mismatch (truncated/corrupt frame).
 * Validation of the header/entry fields is the caller's responsibility via [ApkContainerValidation].
 * Streams are NOT closed here — the staging layer owns their lifecycle.
 */
object ApkCodec {

    private const val NEWLINE = '\n'.code.toByte()
    private const val CHUNK = 8 * 1024

    /** Safety cap on per-line scanning: a header/entry line larger than this is rejected as malformed. */
    private const val MAX_HEADER_BYTES = 4 * 1024

    /**
     * Write the container: the header line, then for each file its entry line followed by exactly
     * `file.entry.length` bytes streamed verbatim from `file.open()`. The per-file source stream is
     * closed (it is opened here via [ApkSourceFile.open]); the caller-owned [sink] is flushed but NOT
     * closed (the same lifecycle [cc.grepon.portage.providers.relay.RelayCodec] uses).
     *
     * The streamed byte count is verified to equal the entry's declared [ApkFileEntry.length]: a
     * concurrent app update mid-export can shrink/grow a source file AFTER its length was captured, and
     * a length-desynced frame would corrupt every following file in the container. On mismatch this
     * throws so the caller's per-item try/catch (ManifestBuilder) cleanly DROPS that item rather than
     * shipping a desynced container.
     */
    fun writeContainer(sink: OutputStream, header: ApkContainerHeader, files: List<ApkSourceFile>) {
        writeLine(sink, JsonLines.format.encodeToString(ApkContainerHeader.serializer(), header))
        for (file in files) {
            writeLine(sink, JsonLines.format.encodeToString(ApkFileEntry.serializer(), file.entry))
            val n = file.open().use { it.copyTo(sink) }
            require(n == file.entry.length) {
                "apk ${file.entry.name}: declared ${file.entry.length}, streamed $n"
            }
        }
        sink.flush()
    }

    /**
     * Scan [source] byte-by-byte to the first '\n', decode that line as an [ApkContainerHeader], and
     * return it; the stream is left positioned at the first byte of the first entry line. Returns null
     * on EOF-before-newline, on exceeding the [MAX_HEADER_BYTES] runaway guard, or on unparseable JSON.
     * Mirrors [cc.grepon.portage.providers.relay.RelayCodec.readHeaderFrom]. No bytes beyond the line
     * are read.
     */
    fun readHeaderFrom(source: InputStream): ApkContainerHeader? =
        readLineBytes(source)?.let { line ->
            runCatching {
                JsonLines.format.decodeFromString(ApkContainerHeader.serializer(), line)
            }.getOrNull()
        }

    /**
     * Scan [source] byte-by-byte to the next '\n', decode that line as an [ApkFileEntry], and return
     * it; the stream is left positioned at the first byte of that file's blob. Same newline-scan
     * primitive and runaway guard as [readHeaderFrom]. Returns null on EOF-before-newline, oversize, or
     * unparseable JSON. The file's blob bytes are NOT read or buffered here — use [streamBlob].
     */
    fun readEntryFrom(source: InputStream): ApkFileEntry? =
        readLineBytes(source)?.let { line ->
            runCatching {
                JsonLines.format.decodeFromString(ApkFileEntry.serializer(), line)
            }.getOrNull()
        }

    /**
     * Stream exactly [expectedBytes] bytes from [source] to [sink] in bounded [CHUNK]-sized reads,
     * returning the total bytes written. The caller MUST verify this equals the entry's
     * [ApkFileEntry.length] — a mismatch means the frame was truncated or corrupt. Identical to
     * [cc.grepon.portage.providers.relay.RelayCodec.streamBlob]; the bytes are copied VERBATIM.
     */
    fun streamBlob(source: InputStream, sink: OutputStream, expectedBytes: Long): Long {
        val buf = ByteArray(CHUNK)
        var remaining = expectedBytes
        var written = 0L
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = source.read(buf, 0, toRead)
            if (n == -1) break
            if (n == 0) break // no progress (a legal non-EOF zero read): break rather than busy-spin
            sink.write(buf, 0, n)
            written += n
            remaining -= n
        }
        return written
    }

    /**
     * Test helper: fully materializes the header + every file's bytes into an [ApkContainer]. NOT used
     * on the production apply path — full materialization is unsafe at the 1 GiB APK item cap
     * (ADR-006 D1, Critic M1). Safe for small test fixtures to assert byte-exact round-trip equality
     * without duplicating the streaming scan logic. Returns null if any line is missing/oversize/
     * unparseable or any file's streamed length disagrees with its declared [ApkFileEntry.length].
     */
    fun readFrom(source: InputStream): ApkContainer? {
        val header = readHeaderFrom(source) ?: return null
        val files = ArrayList<DecodedApkFile>(header.fileCount)
        repeat(header.fileCount) {
            val entry = readEntryFrom(source) ?: return null
            val blob = ByteArrayOutputStream()
            val written = streamBlob(source, blob, entry.length)
            if (written != entry.length) return null
            files += DecodedApkFile(entry, blob.toByteArray())
        }
        return ApkContainer(header, files)
    }

    private fun writeLine(sink: OutputStream, line: String) {
        sink.write(line.toByteArray(Charsets.UTF_8))
        sink.write(NEWLINE.toInt())
    }

    /**
     * The shared newline-scan primitive: read bytes up to (and consuming) the first '\n', returning
     * the line as a UTF-8 string. Returns null on EOF-before-newline, on an empty line, or when the
     * line exceeds [MAX_HEADER_BYTES] (runaway guard). On success the stream sits immediately after the
     * consumed newline.
     *
     * This reads one byte at a time, so callers should pass a buffered [source] (the staging layer
     * already wraps the socket stream) to avoid a syscall per byte.
     */
    private fun readLineBytes(source: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = source.read()
            if (b == -1) return null
            if (b.toByte() == NEWLINE) break
            buf.write(b)
            if (buf.size() > MAX_HEADER_BYTES) return null
        }
        if (buf.size() == 0) return null
        return String(buf.toByteArray(), Charsets.UTF_8)
    }
}
