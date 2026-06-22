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

    inline fun <reified T> readFrom(source: InputStream): JsonLinesResult<T> {
        val records = mutableListOf<T>()
        var malformed = 0
        source.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching { format.decodeFromString<T>(line) }
                .onSuccess { records += it }
                .onFailure { malformed++ }
        }
        return JsonLinesResult(records, malformed)
    }
}
