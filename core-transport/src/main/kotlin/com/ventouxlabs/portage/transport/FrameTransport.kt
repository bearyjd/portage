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

import java.io.EOFException

/**
 * Length-delimited byte-frame transport. The Noise layer (handshake + session) sits on top
 * of this and is agnostic to whether frames travel over a TCP socket (production) or an
 * in-memory pipe (tests). Production framing is `u16 BE length || bytes` per PROTOCOL.md §3.
 */
interface FrameTransport : AutoCloseable {
    /** Write one frame. */
    fun writeFrame(bytes: ByteArray)

    /** Read the next frame; throws [EOFException] at clean end-of-stream. */
    @Throws(EOFException::class)
    fun readFrame(): ByteArray
}
