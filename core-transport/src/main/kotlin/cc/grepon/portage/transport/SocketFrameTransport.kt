/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket

/**
 * [FrameTransport] over a TCP socket. Wire framing is `u16 BE length || bytes`
 * (PROTOCOL.md §3) — the u16 length prefix is itself the frame-size cap (ADR-002 §3,
 * follow-up #3): a frame can never exceed 65535 bytes. I/O failures become fail-closed
 * [TransportException]; a clean stream end becomes [EOFException] so the session reads it
 * as end-of-stream. Read deadlines come from the socket's `soTimeout`.
 */
class SocketFrameTransport(private val socket: Socket) : FrameTransport {

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    override fun writeFrame(bytes: ByteArray) {
        require(bytes.size <= NoiseChannel.MAX_FRAME_BYTES) { "frame exceeds u16 cap" }
        try {
            output.writeShort(bytes.size) // u16 BE
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            throw TransportException("frame write failed", e)
        }
    }

    override fun readFrame(): ByteArray {
        val len = try {
            input.readUnsignedShort() // u16 BE; throws EOFException at a clean stream end
        } catch (e: EOFException) {
            throw e
        } catch (e: IOException) {
            throw TransportException("frame length read failed", e)
        }
        // readUnsignedShort is inherently <= 65535, but assert the contract explicitly.
        if (len > NoiseChannel.MAX_FRAME_BYTES) throw TransportException("frame exceeds u16 cap")
        val buf = ByteArray(len)
        try {
            input.readFully(buf)
        } catch (e: EOFException) {
            throw e
        } catch (e: IOException) {
            throw TransportException("frame body read failed", e)
        }
        return buf
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
