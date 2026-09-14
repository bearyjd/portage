/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.ventouxlabs.portage.transport

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.ventouxlabs.portage.model.PairingMode
import com.ventouxlabs.portage.model.ProtocolMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ResumeHandshakeTest {
    private val qr = ByteArray(32) { it.toByte() }
    private val credential = ByteArray(32) { (it + 32).toByte() }
    private val sid = ByteArray(16) { it.toByte() }
    private val lineage = "101112131415161718191a1b1c1d1e1f"

    @Test
    fun `RFC 5869 SHA256 case 1 includes multiblock expansion`() {
        val result = HkdfSha256.derive(
            ByteArray(13) { it.toByte() }, ByteArray(22) { 0x0b },
            ByteArray(10) { (0xf0 + it).toByte() }, 42,
        )
        assertThat(result.hex()).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        )
    }

    @Test
    fun `RFC 5869 SHA256 case 3 supports empty salt and info`() {
        val result = HkdfSha256.derive(ByteArray(0), ByteArray(22) { 0x0b }, ByteArray(0), 42)
        assertThat(result.hex()).isEqualTo(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
        )
    }

    @Test
    fun `v6 resume KDF and prologues have fixed independent vectors`() {
        // Independently calculated using Python stdlib hmac+hashlib, not this implementation.
        assertThat(ResumeKeyDerivation.derive(qr, credential, 6, sid, lineage).hex()).isEqualTo(
            "849ecf7ef401830cd6dd084afec732fc57fa6ae4d7bcacb145d282686ade3120",
        )
        assertThat(ResumeKeyDerivation.context("portage-resume-psk", 6, PairingMode.RESUME, sid, lineage).hex())
            .isEqualTo("0012706f72746167652d726573756d652d70736b0001060006524553554d450010" +
                "000102030405060708090a0b0c0d0e0f000a726563762d3e73656e640010101112131415161718191a1b1c1d1e1f")
        assertThat(NoiseChannel.prologue(6, sid, PairingMode.RESUME, lineage).hex()).isEqualTo(
            "0016706f72746167652d6e6f6973652d70726f6c6f6775650001060006524553554d450010" +
                "000102030405060708090a0b0c0d0e0f000a726563762d3e73656e640010101112131415161718191a1b1c1d1e1f",
        )
        assertThat(NoiseChannel.prologue(6, sid).hex()).isEqualTo(
            "0016706f72746167652d6e6f6973652d70726f6c6f67756500010600034e45570010" +
                "000102030405060708090a0b0c0d0e0f000a726563762d3e73656e640000",
        )
        assertThat(qr).isEqualTo(ByteArray(32) { it.toByte() })
        assertThat(credential).isEqualTo(ByteArray(32) { (it + 32).toByte() })
    }

    @Test
    fun `all key and identifier lengths are validated before derivation`() {
        for (size in listOf(0, 15, 16, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) {
                ResumeKeyDerivation.derive(ByteArray(size), credential, 6, sid, lineage)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ResumeKeyDerivation.derive(qr, ByteArray(size), 6, sid, lineage)
            }
        }
        for (size in listOf(0, 15, 17, 32)) {
            assertThrows(IllegalArgumentException::class.java) {
                ResumeKeyDerivation.derive(qr, credential, 6, ByteArray(size), lineage)
            }
            assertThrows(IllegalArgumentException::class.java) { NoiseChannel.prologue(6, ByteArray(size)) }
        }
        for (invalid in listOf("", "a".repeat(31), "a".repeat(33), "A".repeat(32), "g".repeat(32))) {
            assertThrows(IllegalArgumentException::class.java) {
                ResumeKeyDerivation.derive(qr, credential, 6, sid, invalid)
            }
        }
        for (version in listOf(-1, 256, 262)) {
            assertThrows(IllegalArgumentException::class.java) {
                ResumeKeyDerivation.derive(qr, credential, version, sid, lineage)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { NoiseChannel.prologue(6, sid, PairingMode.RESUME) }
        assertThrows(IllegalArgumentException::class.java) { NoiseChannel.prologue(6, sid, PairingMode.NEW, lineage) }
        assertThrows(IllegalArgumentException::class.java) { HkdfSha256.derive(qr, credential, sid, 8161) }
    }

    @Test(timeout = 15_000L)
    fun `matching resume inputs establish a working channel`() {
        val key = ResumeKeyDerivation.derive(qr, credential, 6, sid, lineage)
        val prologue = NoiseChannel.prologue(6, sid, PairingMode.RESUME, lineage)
        handshake(key, prologue, key, prologue) { recv, send, failures ->
            assertThat(failures).isEmpty()
            assertThat(recv).isNotNull()
            assertThat(send).isNotNull()
            recv!!.send(ProtocolMessage.Ping)
            assertThat(send!!.receive()).isEqualTo(ProtocolMessage.Ping)
            send.send(ProtocolMessage.LineageAck(lineage))
            assertThat(recv.receive()).isEqualTo(ProtocolMessage.LineageAck(lineage))
        }
    }

    @Test(timeout = 30_000L)
    fun `resume handshake rejects QR-only and every mismatching key schedule input`() {
        val key = ResumeKeyDerivation.derive(qr, credential, 6, sid, lineage)
        val prologue = NoiseChannel.prologue(6, sid, PairingMode.RESUME, lineage)
        val wrongKeys = mapOf(
            "QR-only" to qr.copyOf(),
            "QR secret" to ResumeKeyDerivation.derive(qr.flipped(), credential, 6, sid, lineage),
            "resume credential" to ResumeKeyDerivation.derive(qr, credential.flipped(), 6, sid, lineage),
            "SID" to ResumeKeyDerivation.derive(qr, credential, 6, sid.flipped(), lineage),
            "version" to ResumeKeyDerivation.derive(qr, credential, 7, sid, lineage),
            "lineage" to ResumeKeyDerivation.derive(qr, credential, 6, sid, "00".repeat(16)),
        )
        for ((name, wrongKey) in wrongKeys) {
            handshake(key, prologue, wrongKey, prologue) { recv, send, failures ->
                assertWithMessage(name).that(failures).isNotEmpty()
                assertWithMessage(name).that(recv).isNull()
                assertWithMessage(name).that(send).isNull()
                assertThat(failures.all { it is TransportException }).isTrue()
            }
        }
    }

    @Test(timeout = 30_000L)
    fun `prologue independently authenticates mode SID version direction and stored lineage`() {
        val key = ResumeKeyDerivation.derive(qr, credential, 6, sid, lineage)
        val prologue = NoiseChannel.prologue(6, sid, PairingMode.RESUME, lineage)
        val directionChanged = prologue.copyOf().also {
            // Direction begins 28 bytes before the end: ten direction bytes + u16 + raw lineage.
            val offset = it.size - 28
            it[offset] = 's'.code.toByte()
        }
        val wrongPrologues = mapOf(
            "mode" to NoiseChannel.prologue(6, sid, PairingMode.NEW),
            "SID" to NoiseChannel.prologue(6, sid.flipped(), PairingMode.RESUME, lineage),
            "version" to NoiseChannel.prologue(7, sid, PairingMode.RESUME, lineage),
            "direction" to directionChanged,
            "lineage" to NoiseChannel.prologue(6, sid, PairingMode.RESUME, "00".repeat(16)),
        )
        for ((name, wrong) in wrongPrologues) {
            handshake(key, prologue, key, wrong) { recv, send, failures ->
                assertWithMessage(name).that(failures).isNotEmpty()
                assertWithMessage(name).that(recv).isNull()
                assertWithMessage(name).that(send).isNull()
            }
        }
    }

    private fun handshake(
        recvKey: ByteArray,
        recvPrologue: ByteArray,
        sendKey: ByteArray,
        sendPrologue: ByteArray,
        verify: (NoiseSession?, NoiseSession?, List<Throwable>) -> Unit,
    ) {
        val toRecv = LinkedBlockingQueue<ByteArray>()
        val toSend = LinkedBlockingQueue<ByteArray>()
        fun transport(incoming: LinkedBlockingQueue<ByteArray>, outgoing: LinkedBlockingQueue<ByteArray>) =
            object : FrameTransport {
                override fun writeFrame(bytes: ByteArray) { outgoing.put(bytes) }
                override fun readFrame(): ByteArray {
                    val frame = incoming.poll(5, TimeUnit.SECONDS) ?: throw EOFException()
                    if (frame.isEmpty()) throw EOFException()
                    return frame
                }
                override fun close() { outgoing.offer(ByteArray(0)) }
            }
        val recvTransport = transport(toRecv, toSend)
        val sendTransport = transport(toSend, toRecv)
        val recv = AtomicReference<CipherStatePair>()
        val send = AtomicReference<CipherStatePair>()
        val errors = LinkedBlockingQueue<Throwable>()
        val receiver = thread(isDaemon = true) {
            try {
                recv.set(NoiseChannel.handshake(recvTransport, HandshakeState.INITIATOR, recvKey, recvPrologue))
            } catch (t: Throwable) { errors.add(t); recvTransport.close() }
        }
        val sender = thread(isDaemon = true) {
            try {
                send.set(NoiseChannel.handshake(sendTransport, HandshakeState.RESPONDER, sendKey, sendPrologue))
            } catch (t: Throwable) { errors.add(t); sendTransport.close() }
        }
        try {
            receiver.join(6_000)
            sender.join(6_000)
            assertThat(receiver.isAlive).isFalse()
            assertThat(sender.isAlive).isFalse()
            verify(recv.get()?.let { NoiseSession(recvTransport, it) },
                send.get()?.let { NoiseSession(sendTransport, it) }, errors.toList())
        } finally {
            recvTransport.close()
            sendTransport.close()
            recv.get()?.destroy()
            send.get()?.destroy()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun ByteArray.flipped(): ByteArray = copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
}
