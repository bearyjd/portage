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

import com.ventouxlabs.portage.model.MessageType
import com.ventouxlabs.portage.model.ProtocolMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Frame-size regression for the wire codec. A 60 KiB `ItemData` chunk (the size `TransferEngine`
 * actually sends) MUST serialize under the Noise plaintext budget so the +16-byte-MAC ciphertext
 * stays within the 65535-byte u16 frame cap. Before `@ByteString` on `ItemData.bytes`, kotlinx CBOR
 * encoded the ByteArray as a CBOR array of integers (~2x for text bytes), so a full chunk encoded to
 * ~120 KiB and `SocketFrameTransport.writeFrame` aborted with "frame exceeds u16 cap" — found
 * on-device 2026-06-14 the first time a large item (C1 contacts) ever went over the wire.
 */
class CborMessageCodecTest {

    private val codec = CborMessageCodec()

    @Test
    fun `a full 60 KiB ItemData chunk encodes under the message cap and round-trips`() {
        // Worst case for the old encoding: every byte >= 0x20 (printable ASCII / vCard text), so each
        // would have cost 2 bytes as a CBOR integer. 60 KiB = TransferEngine.DEFAULT_CHUNK_BYTES.
        val payload = ByteArray(60 * 1024) { (0x20 + (it % 95)).toByte() }
        val msg = ProtocolMessage.ItemData(itemId = 7, seq = 3, bytes = payload)

        val encoded = codec.encode(msg)

        // 65519 = Noise max plaintext; +16 MAC keeps the on-wire frame within the 65535 u16 cap.
        assertThat(encoded.size).isAtMost(65519)
        assertThat(codec.decode(encoded)).isEqualTo(msg)
    }

    @Test
    fun `ItemData round-trips byte-exact across the full byte range`() {
        // Content-agnostic: arbitrary binary, not just text, survives the byte-string path intact.
        val payload = ByteArray(4096) { (it % 256).toByte() }
        val msg = ProtocolMessage.ItemData(itemId = 1, seq = 0, bytes = payload)
        assertThat(codec.decode(codec.encode(msg))).isEqualTo(msg)
    }

    // ── decode rejection paths (the trust-boundary validators; NoiseSession maps these to a
    //    fail-closed TransportException — see NoiseLoopbackTest 'hostile plaintext fails closed') ──

    private fun assertDecodeRejects(bytes: ByteArray) {
        val thrown: Throwable? = try {
            codec.decode(bytes); null
        } catch (e: Exception) {
            e
        }
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `decode rejects an empty frame`() = assertDecodeRejects(ByteArray(0))

    @Test
    fun `decode rejects an unknown message type byte`() = assertDecodeRejects(byteArrayOf(0x7F))

    @Test
    fun `decode rejects a body over the message cap`() {
        // The size cap (65519) is checked before the type byte is even parsed; content is irrelevant.
        assertDecodeRejects(ByteArray(70_000))
    }

    @Test
    fun `decode rejects a valid type byte over a malformed CBOR body`() {
        // MANIFEST discriminator followed by non-CBOR garbage → kotlinx SerializationException,
        // which is an IllegalArgumentException subtype.
        assertDecodeRejects(byteArrayOf(MessageType.MANIFEST.t.toByte(), 0xFF.toByte()))
    }
}
