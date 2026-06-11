/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.text

/**
 * TEXT value escaping + line unfolding shared by vCard 3.0 (RFC 2426 §2.4.2) and
 * iCalendar (RFC 5545 §3.3.11). Both specs escape the same four characters and fold
 * long lines by prefixing continuations with whitespace.
 */
object RfcText {

    /** Escape a TEXT value: `\` `;` `,` and newline become `\\` `\;` `\,` `\n`. */
    fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit // normalize CRLF/CR to the \n we emit for the matching \n char
                else -> append(c)
            }
        }
    }

    /** Invert [escape]. Unknown escapes and a trailing lone backslash pass through verbatim. */
    fun unescape(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '\\' -> { append('\\'); i += 2 }
                    ';' -> { append(';'); i += 2 }
                    ',' -> { append(','); i += 2 }
                    'n', 'N' -> { append('\n'); i += 2 }
                    else -> { append(c); append(next); i += 2 }
                }
            } else {
                append(c)
                i++
            }
        }
    }

    /**
     * Unfold physical lines into logical lines: a line starting with SPACE or HTAB is the
     * continuation of the previous one (with the single leading whitespace char removed).
     * A dangling leading continuation has nothing to attach to and is dropped.
     */
    fun unfold(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                if (out.isNotEmpty()) out[out.size - 1] = out.last() + line.substring(1)
            } else {
                out += line
            }
        }
        return out
    }
}
