/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.wire

import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** Decoded JSON-lines payload: parsed records plus how many lines were unparseable. */
data class JsonLinesResult<T>(val records: List<T>, val malformed: Int)

/**
 * JSON Lines codec for record-stream payloads (call log, SMS). One record per line gives
 * the per-record resilience PROTOCOL.md §5 demands: a corrupt line is counted and skipped,
 * never fatal to the item. Unknown keys are ignored for forward compat (PROTOCOL.md §3).
 *
 * Streams are NOT closed here — the staging layer owns their lifecycle.
 */
object JsonLines {

    val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    inline fun <reified T> writeTo(sink: OutputStream, records: List<T>) {
        val writer = sink.bufferedWriter(Charsets.UTF_8)
        for (record in records) {
            writer.write(format.encodeToString(record))
            writer.write("\n")
        }
        writer.flush()
    }

    /** Upper bound on one record line; longer lines are counted malformed and skipped unbuffered. */
    const val MAX_LINE_CHARS = 1024 * 1024

    inline fun <reified T> readFrom(source: InputStream): JsonLinesResult<T> {
        val records = mutableListOf<T>()
        var malformed = 0
        forEachBoundedLine(source) { line ->
            if (line == null) {
                malformed++
            } else if (line.isNotBlank()) {
                runCatching { format.decodeFromString<T>(line) }
                    .onSuccess { records += it }
                    .onFailure { malformed++ }
            }
        }
        return JsonLinesResult(records, malformed)
    }

    /**
     * Reads [source] line by line, buffering at most [MAX_LINE_CHARS] per line. An oversize line is
     * discarded as it streams past (never held in memory) and reported to [onLine] as null.
     */
    @PublishedApi
    internal fun forEachBoundedLine(source: InputStream, onLine: (String?) -> Unit) {
        val reader = source.bufferedReader(Charsets.UTF_8)
        val line = StringBuilder()
        var oversize = false
        var sawChars = false
        while (true) {
            val c = reader.read()
            if (c == -1) break
            sawChars = true
            if (c == '\n'.code) {
                onLine(if (oversize) null else line.toString().removeSuffix("\r"))
                line.setLength(0)
                oversize = false
                sawChars = false
            } else if (!oversize) {
                if (line.length >= MAX_LINE_CHARS) {
                    oversize = true
                    line.setLength(0)
                } else {
                    line.append(c.toChar())
                }
            }
        }
        if (sawChars) onLine(if (oversize) null else line.toString().removeSuffix("\r"))
    }
}
