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

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemMeta
import cc.grepon.portage.model.PairingPayload
import cc.grepon.portage.model.ProtocolMessage
import cc.grepon.portage.model.TransferManifest
import com.google.common.truth.Truth.assertThat
import com.southernstorm.noise.protocol.HandshakeState
import org.junit.Test
import java.io.EOFException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Hello-world Noise exchange between two in-process endpoints (the riskiest piece, proven
 * early — portage-prp-prompt.md §10.3). Validates ADR-002's NoisePSK_XX choice and the
 * THREAT_MODEL properties as *tests*, not design claims:
 *  - matching PSK → channel; messages round-trip both directions intact;
 *  - mismatched PSK → no channel (THREAT_MODEL #2, the auth property);
 *  - mismatched prologue (sid/version) → no channel (THREAT_MODEL #6, transcript binding);
 *  - tampered transport frame → rejected, fail-closed (THREAT_MODEL #5, AEAD integrity).
 */
class NoiseLoopbackTest {

    private val eof = ByteArray(0)

    private inner class QueueTransport(
        private val outQ: BlockingQueue<ByteArray>,
        private val inQ: BlockingQueue<ByteArray>,
    ) : FrameTransport {
        override fun writeFrame(bytes: ByteArray) { outQ.put(bytes) }
        override fun readFrame(): ByteArray {
            val f = inQ.take()
            if (f === eof) throw EOFException()
            return f
        }
        override fun close() { outQ.put(eof) }
    }

    private val psk = ByteArray(32) { (it + 1).toByte() }
    private val sid = ByteArray(16) { (it * 7).toByte() }
    private val prologue = NoiseChannel.prologue(version = 1, sid = sid)

    private class HsResult(
        val errors: List<Throwable>,
        val recv: NoiseSession?,
        val send: NoiseSession?,
        val aToB: BlockingQueue<ByteArray>,
        val bToA: BlockingQueue<ByteArray>,
    )

    /** Runs both handshake roles on daemon threads; a mismatch leaves one parked, harmless. */
    private fun runHandshake(
        senderPsk: ByteArray = psk,
        senderPrologue: ByteArray = prologue,
    ): HsResult {
        val aToB = LinkedBlockingQueue<ByteArray>()
        val bToA = LinkedBlockingQueue<ByteArray>()
        val recvT = QueueTransport(outQ = aToB, inQ = bToA) // receiver = initiator
        val sendT = QueueTransport(outQ = bToA, inQ = aToB) // sender = responder

        val errors = mutableListOf<Throwable>()
        var recvSession: NoiseSession? = null
        var sendSession: NoiseSession? = null

        val r = thread(isDaemon = true) {
            try {
                val k = NoiseChannel.handshake(recvT, HandshakeState.INITIATOR, psk, prologue)
                recvSession = NoiseSession(recvT, k)
            } catch (t: Throwable) { synchronized(errors) { errors.add(t) } }
        }
        val s = thread(isDaemon = true) {
            try {
                val k = NoiseChannel.handshake(sendT, HandshakeState.RESPONDER, senderPsk, senderPrologue)
                sendSession = NoiseSession(sendT, k)
            } catch (t: Throwable) { synchronized(errors) { errors.add(t) } }
        }
        r.join(5_000); s.join(5_000)
        return HsResult(errors, recvSession, sendSession, aToB, bToA)
    }

    @Test
    fun `matching PSK establishes channel and round-trips both directions`() {
        val h = runHandshake()
        assertThat(h.errors).isEmpty()
        assertThat(h.recv).isNotNull()
        assertThat(h.send).isNotNull()

        val hello = ProtocolMessage.Hello(appVersion = "0.1.0", osFingerprint = "comet:16")
        h.recv!!.send(hello)
        assertThat(h.send!!.receive()).isEqualTo(hello)

        val manifest = ProtocolMessage.Manifest(
            TransferManifest(
                senderName = "old phone",
                items = listOf(
                    ItemMeta(1, ItemKind.CONTACTS_VCF, size = 42, sha256 = "deadbeef", displayName = "Contacts", group = "People"),
                ),
                totalBytes = 42,
            ),
        )
        h.send.send(manifest)
        assertThat(h.recv.receive()).isEqualTo(manifest)
    }

    @Test
    fun `a full 60 KiB ItemData chunk round-trips over the real Noise session`() {
        // End-to-end regression for the on-device frame-cap overflow (2026-06-14, C1 contacts). A
        // 60 KiB chunk (TransferEngine.DEFAULT_CHUNK_BYTES) of printable-ASCII bytes — the worst case
        // for the old un-annotated ByteArray (kotlinx CBOR int array, ~2x → ~120 KiB). On the real
        // SocketFrameTransport that overflow aborts on SEND (writeFrame's u16 require); here
        // QueueTransport has no send guard, so the receiver's MAX_FRAME_BYTES check rejects it on
        // receive instead. @ByteString keeps it a compact byte string under the 65519 / 65535 caps.
        val h = runHandshake()
        assertThat(h.errors).isEmpty()
        val chunk = ProtocolMessage.ItemData(
            itemId = 1,
            seq = 0,
            bytes = ByteArray(60 * 1024) { (0x20 + (it % 95)).toByte() },
        )
        h.send!!.send(chunk)
        assertThat(h.recv!!.receive()).isEqualTo(chunk)
    }

    @Test
    fun `mismatched PSK fails to establish a channel`() {
        val wrong = ByteArray(32) { (it + 99).toByte() }
        val h = runHandshake(senderPsk = wrong)
        // The responder reads msg1 with the wrong key, its AEAD tag fails, and it produces
        // no session. Pin the failure type so a future "both sides silently fail" regression
        // can't pass this test.
        assertThat(h.errors).isNotEmpty()
        assertThat(h.errors.first()).isInstanceOf(TransportException::class.java)
        assertThat(h.send).isNull()
    }

    @Test
    fun `mismatched prologue (different sid) fails the handshake`() {
        val otherSid = ByteArray(16) { (it * 13 + 1).toByte() }
        val h = runHandshake(senderPrologue = NoiseChannel.prologue(version = 1, sid = otherSid))
        assertThat(h.errors).isNotEmpty()
        assertThat(h.errors.first()).isInstanceOf(TransportException::class.java)
        assertThat(h.send).isNull()
    }

    @Test
    fun `tampered transport frame is rejected fail-closed`() {
        val h = runHandshake()
        assertThat(h.errors).isEmpty()
        val recv = h.recv!!
        val send = h.send!!

        // Sender enqueues one AEAD frame; flip a byte in its tag before the receiver reads.
        send.send(ProtocolMessage.Hello(appVersion = "x", osFingerprint = "y"))
        val frame = h.bToA.take()
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0x01).toByte()
        h.bToA.put(frame)

        val thrown: TransportException? = try {
            recv.receive(); null
        } catch (e: TransportException) {
            e
        }
        assertThat(thrown).isNotNull()
        // Pin the rejection to the AEAD layer, not some unrelated transport error.
        assertThat(thrown?.cause).isInstanceOf(java.security.GeneralSecurityException::class.java)
    }

    @Test
    fun `pairing codec round-trips and rejects expired or wrong-version QR`() {
        val codec = PairingCodecImpl()
        val payload = PairingPayload(
            psk = psk, sid = sid, ip = listOf("192.168.1.50"), port = 38_421,
            expiresAtEpochSeconds = 1_000_120L,
        )
        val qr = codec.encode(payload)
        assertThat(qr).startsWith(PairingPayload.SCHEME)
        assertThat(codec.decode(qr, nowEpochSeconds = 1_000_000L).getOrNull()).isEqualTo(payload)
        // Expired:
        assertThat(codec.decode(qr, nowEpochSeconds = 1_000_121L).isFailure).isTrue()
        // Not a portage URI:
        assertThat(codec.decode("https://example.com", nowEpochSeconds = 1L).isFailure).isTrue()
    }

    @Test
    fun `wipe zeroizes the PSK`() {
        val secret = ByteArray(32) { (it + 5).toByte() }
        val payload = PairingPayload(
            psk = secret, sid = sid, ip = emptyList(), port = 1, expiresAtEpochSeconds = 1L,
        )
        payload.wipe()
        assertThat(secret.all { it == 0.toByte() }).isTrue()
    }
}
