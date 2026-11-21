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

/** Endless-ish stream of [fill] bytes that records how much a decoder pulled from it. */
class CountingStream(private val total: Long, private val fill: Int = 'a'.code) : InputStream() {
    var consumed = 0L
        private set

    override fun read(): Int {
        if (consumed >= total) return -1
        consumed++
        return fill
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (consumed >= total) return -1
        val n = minOf(len.toLong(), total - consumed).toInt()
        b.fill(fill.toByte(), off, off + n)
        consumed += n
        return n
    }
}
