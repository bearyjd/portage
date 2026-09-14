/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.ventouxlabs.portage.transport

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.model.PairingMode
import com.ventouxlabs.portage.model.PairingPayload
import com.ventouxlabs.portage.model.ProtocolMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.ServerSocket

class ResumeSocketSecureChannelTest {
    private val credential = ByteArray(32) { (it + 32).toByte() }
    private val lineage = "101112131415161718191a1b1c1d1e1f"
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    private fun payload(port: Int, session: Int = 0, mode: PairingMode = PairingMode.RESUME) = PairingPayload(
        psk = ByteArray(32) { (it + session).toByte() },
        sid = ByteArray(16) { (it + session).toByte() },
        ip = listOf("127.0.0.1"), port = port, expiresAtEpochSeconds = Long.MAX_VALUE / 2, mode = mode,
    )

    @Test(timeout = 20_000L)
    fun `resume authenticates both peers and fresh sessions can reuse the stored credential`() = runBlocking {
        val factory = NoiseSecureChannelFactory()
        repeat(2) { session ->
            val port = freePort()
            val senderPayload = payload(port, session)
            val receiverPayload = payload(port, session)
            val accept = async(Dispatchers.IO) { factory.acceptAsSender(senderPayload, credential, lineage) }
            factory.connectAsReceiver(receiverPayload, credential, lineage).use { receiver ->
                accept.await().use { sender ->
                    receiver.send(ProtocolMessage.LineageAck(lineage))
                    assertThat(sender.receive()).isEqualTo(ProtocolMessage.LineageAck(lineage))
                    sender.send(ProtocolMessage.Ping)
                    assertThat(receiver.receive()).isEqualTo(ProtocolMessage.Ping)
                }
            }
            assertThat(senderPayload.psk).isEqualTo(ByteArray(32))
            assertThat(receiverPayload.psk).isEqualTo(ByteArray(32))
        }
        assertThat(credential).isEqualTo(ByteArray(32) { (it + 32).toByte() })
    }

    @Test(timeout = 20_000L)
    fun `resume session consumption rejects a second completed handshake`() = runBlocking {
        val factory = NoiseSecureChannelFactory()
        repeat(2) { attempt ->
            val port = freePort()
            val senderPayload = payload(port)
            val receiverPayload = payload(port)
            val accept = async(Dispatchers.IO) {
                runCatching { factory.acceptAsSender(senderPayload, credential, lineage) }
            }
            runCatching { factory.connectAsReceiver(receiverPayload, credential, lineage) }.getOrNull()?.close()
            val result = accept.await()
            if (attempt == 0) {
                result.getOrThrow().close()
            } else {
                assertThat(result.exceptionOrNull()).isInstanceOf(TransportException::class.java)
                assertThat(result.exceptionOrNull()?.message).isEqualTo("session already consumed")
            }
            assertThat(senderPayload.psk).isEqualTo(ByteArray(32))
            assertThat(receiverPayload.psk).isEqualTo(ByteArray(32))
        }
    }

    @Test(timeout = 20_000L)
    fun `failed resume credential does not consume fresh QR or lock out the correct peer`() = runBlocking {
        val factory = NoiseSecureChannelFactory(handshakeTimeoutMs = 2_000, acceptDeadlineMs = 10_000)
        val port = freePort()
        val senderPayload = payload(port)
        val badPayload = payload(port)
        val accept = async(Dispatchers.IO) { factory.acceptAsSender(senderPayload, credential, lineage) }
        val failure = runCatching { factory.connectAsReceiver(badPayload, ByteArray(32), lineage) }
        assertThat(failure.exceptionOrNull()).isInstanceOf(TransportException::class.java)
        assertThat(badPayload.psk).isEqualTo(ByteArray(32))
        val goodPayload = payload(port)
        factory.connectAsReceiver(goodPayload, credential, lineage).use { receiver ->
            accept.await().use { sender ->
                receiver.send(ProtocolMessage.Ping)
                assertThat(sender.receive()).isEqualTo(ProtocolMessage.Ping)
            }
        }
        assertThat(senderPayload.psk).isEqualTo(ByteArray(32))
        assertThat(goodPayload.psk).isEqualTo(ByteArray(32))
    }

    @Test(timeout = 10_000L)
    fun `missing resume inputs and NEW mode resume arguments fail before network use and wipe QR`() = runBlocking {
        val factory = NoiseSecureChannelFactory()
        val cases = listOf(
            Triple(PairingMode.RESUME, null, null),
            Triple(PairingMode.RESUME, credential, null),
            Triple(PairingMode.RESUME, null, lineage),
            Triple(PairingMode.RESUME, ByteArray(31), lineage),
            Triple(PairingMode.RESUME, credential, "A".repeat(32)),
            Triple(PairingMode.NEW, credential, null),
            Triple(PairingMode.NEW, null, lineage),
        )
        for ((mode, secret, id) in cases) {
            for (asSender in listOf(false, true)) {
                // Invalid port makes an accidental attempt to use the network visible in the cause.
                val qr = payload(0, mode = mode)
                val failure = runCatching {
                    if (asSender) factory.acceptAsSender(qr, secret, id)
                    else factory.connectAsReceiver(qr, secret, id)
                }
                assertThat(failure.exceptionOrNull()).isInstanceOf(TransportException::class.java)
                assertThat(failure.exceptionOrNull()?.message).isEqualTo("invalid handshake context")
                assertThat(qr.psk).isEqualTo(ByteArray(32))
            }
        }
        assertThat(credential).isEqualTo(ByteArray(32) { (it + 32).toByte() })
    }

    @Test(timeout = 10_000L)
    fun `v5 pairing is rejected by either factory entry before network use`() = runBlocking {
        val factory = NoiseSecureChannelFactory()
        for (asSender in listOf(false, true)) {
            val qr = payload(0, mode = PairingMode.NEW).copy(version = 5)
            val failure = runCatching {
                if (asSender) factory.acceptAsSender(qr) else factory.connectAsReceiver(qr)
            }
            assertThat(failure.exceptionOrNull()).isInstanceOf(TransportException::class.java)
            // Coroutines may wrap a recovered exception with a copy carrying the original as cause.
            val rootCause = generateSequence(failure.exceptionOrNull()) { it.cause }.last()
            assertThat(rootCause).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(rootCause.message).isEqualTo("unsupported protocol version")
            assertThat(qr.psk).isEqualTo(ByteArray(32))
        }
    }

    @Test(timeout = 10_000L)
    fun `failed receiver connection wipes QR while preserving caller resume credential`() = runBlocking {
        val qr = payload(freePort())
        val failure = runCatching { NoiseSecureChannelFactory().connectAsReceiver(qr, credential, lineage) }
        assertThat(failure.exceptionOrNull()).isInstanceOf(TransportException::class.java)
        assertThat(qr.psk).isEqualTo(ByteArray(32))
        assertThat(credential).isEqualTo(ByteArray(32) { (it + 32).toByte() })
    }

    @Test(timeout = 10_000L)
    fun `cancelling receiver connect wipes QR and preserves caller credential`() = runBlocking {
        val qr = payload(freePort())
        val failure = runCatching {
            withTimeout(100) { NoiseSecureChannelFactory().connectAsReceiver(qr, credential, lineage) }
        }
        assertThat(failure.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(qr.psk).isEqualTo(ByteArray(32))
        assertThat(credential).isEqualTo(ByteArray(32) { (it + 32).toByte() })
    }

    @Test(timeout = 10_000L)
    fun `legacy one argument factory cannot accept resume through extended API`() = runBlocking {
        var invoked = false
        val legacy = object : SecureChannel.Factory {
            override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel {
                invoked = true
                error("legacy receiver must not be reached")
            }
            override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel {
                invoked = true
                error("legacy sender must not be reached")
            }
        }
        for (asSender in listOf(false, true)) {
            for ((mode, secret, id) in listOf(
                Triple(PairingMode.RESUME, credential, lineage),
                Triple(PairingMode.RESUME, null, null),
                Triple(PairingMode.NEW, credential, null),
                Triple(PairingMode.NEW, null, lineage),
            )) {
                val qr = payload(0, mode = mode)
                val failure = runCatching {
                    if (asSender) legacy.acceptAsSender(qr, secret, id)
                    else legacy.connectAsReceiver(qr, secret, id)
                }
                assertThat(failure.exceptionOrNull()).isInstanceOf(TransportException::class.java)
                assertThat(qr.psk).isEqualTo(ByteArray(32))
                assertThat(invoked).isFalse()
            }
        }
    }
}
