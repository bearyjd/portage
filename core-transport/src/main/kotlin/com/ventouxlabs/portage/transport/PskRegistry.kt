/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.transport

import java.util.Collections

/**
 * Enforces "one completed handshake per session id" (THREAT_MODEL.md #4 replay, #7
 * second-suitor; ADR-002 follow-up #1). The sender consumes the `sid` on the FIRST
 * successful handshake; any later handshake for the same `sid` is rejected.
 *
 * Thread-safe: [tryConsume] is atomic, so concurrent connections race to exactly one winner.
 */
class PskRegistry {

    private val consumed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Returns true if this [sid] was not previously consumed (caller wins); false otherwise. */
    fun tryConsume(sid: ByteArray): Boolean = consumed.add(sid.toHex())

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
