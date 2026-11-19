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

import java.io.InputStream

/**
 * Read at most [maxBytes], or return null if the source has more to give.
 *
 * REJECTS rather than truncates. Truncation would hand a half-document to the parser, and a prefix
 * of a valid snapshot can itself be valid JSON — that would silently apply an attacker-chosen SUBSET
 * of a payload portage refused to read in full. Over-length input is not a snapshot portage will
 * act on, so the honest answer is "unreadable".
 */
internal fun InputStream.readBoundedUtf8(maxBytes: Int): String? {
    // One byte of headroom: filling it proves the source exceeded the ceiling.
    val buffer = ByteArray(maxBytes + 1)
    var filled = 0
    while (filled < buffer.size) {
        val n = read(buffer, filled, buffer.size - filled)
        if (n < 0) break
        filled += n
    }
    if (filled > maxBytes) return null
    return String(buffer, 0, filled, Charsets.UTF_8)
}

/** Physical lines that fit the per-line cap, plus how many longer lines were dropped unbuffered. */
internal data class BoundedLines(val lines: List<String>, val oversize: Int)

/** Per-line ceiling for text formats (vCard, iCalendar); RFC fold width is 75 octets. */
internal const val MAX_TEXT_LINE_CHARS = 1024 * 1024

internal fun InputStream.readBoundedLines(maxLineChars: Int = MAX_TEXT_LINE_CHARS): BoundedLines {
    val lines = mutableListOf<String>()
    var oversize = 0
    JsonLines.forEachBoundedLine(this, maxLineChars) { line ->
        if (line == null) oversize++ else lines += line
    }
    return BoundedLines(lines, oversize)
}
