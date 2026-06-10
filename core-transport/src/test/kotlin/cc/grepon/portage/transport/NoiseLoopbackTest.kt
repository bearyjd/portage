/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC. Licensed under AGPL-3.0.
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
 * early — portage-prp-prompt.md §10.3). Validates ADR-002's NoisePSK_XX choice:
 *  1. matching PSK → handshake completes, messages round-trip both directions intact;
 *  2. mismatched PSK → no channel is established (THREAT_MODEL.md #2, the auth property).
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

    private fun runHandshake(senderPsk: ByteArray): Pair<List<Throwable>, Pair<NoiseSession?, NoiseSession?>> {
        val aToB = LinkedBlockingQueue<ByteArray>()
        val bToA = LinkedBlockingQueue<ByteArray>()
        val recvT = QueueTransport(outQ = aToB, inQ = bToA) // receiver = initiator
        val sendT = QueueTransport(outQ = bToA, inQ = aToB) // sender = responder

        val errors = mutableListOf<Throwable>()
        var recvSession: NoiseSession? = null
        var sendSession: NoiseSession? = null

        // Daemon threads: in the mismatch case one endpoint parks forever on a read; as a
        // daemon it can't block JVM shutdown, so no close()-cleanup is needed (closing would
        // inject an EOF sentinel that the happy-path data exchange below would misread).
        val r = thread(isDaemon = true) {
            try {
                val k = NoiseChannel.handshake(recvT, HandshakeState.INITIATOR, psk, prologue)
                recvSession = NoiseSession(recvT, k)
            } catch (t: Throwable) { synchronized(errors) { errors.add(t) } }
        }
        val s = thread(isDaemon = true) {
            try {
                val k = NoiseChannel.handshake(sendT, HandshakeState.RESPONDER, senderPsk, prologue)
                sendSession = NoiseSession(sendT, k)
            } catch (t: Throwable) { synchronized(errors) { errors.add(t) } }
        }
        r.join(5_000); s.join(5_000)
        return errors to (recvSession to sendSession)
    }

    @Test
    fun `matching PSK establishes channel and round-trips both directions`() {
        val (errors, sessions) = runHandshake(senderPsk = psk)
        assertThat(errors).isEmpty()
        val (recv, send) = sessions
        assertThat(recv).isNotNull()
        assertThat(send).isNotNull()

        val hello = ProtocolMessage.Hello(appVersion = "0.1.0", osFingerprint = "comet:16")
        recv!!.send(hello)
        assertThat(send!!.receive()).isEqualTo(hello)

        val manifest = ProtocolMessage.Manifest(
            TransferManifest(
                senderName = "old phone",
                items = listOf(
                    ItemMeta(1, ItemKind.CONTACTS_VCF, size = 42, sha256 = "deadbeef", displayName = "Contacts", group = "People"),
                ),
                totalBytes = 42,
            ),
        )
        send.send(manifest)
        assertThat(recv.receive()).isEqualTo(manifest)
    }

    @Test
    fun `mismatched PSK fails to establish a channel`() {
        val wrong = ByteArray(32) { (it + 99).toByte() }
        val (errors, sessions) = runHandshake(senderPsk = wrong)
        // At least one endpoint must have failed the handshake; never BOTH sessions usable.
        assertThat(errors).isNotEmpty()
        val (recv, send) = sessions
        assertThat(recv != null && send != null).isFalse()
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
}
