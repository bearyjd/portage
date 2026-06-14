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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * End-to-end over real TCP loopback: the proven NoisePSK_XX core wrapped in the socket
 * transport + SecureChannel factory, exercising the listener-layer controls from
 * ADR-002 §Follow-ups (handshake over sockets, PSK wipe, second-suitor lockout via two
 * real handshakes, bounded listener under a stalled peer).
 */
class SocketSecureChannelTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val pskValue = ByteArray(32) { (it + 1).toByte() }
    private val sidValue = ByteArray(16) { (it * 3).toByte() }

    /** Separate array instances with identical content (wipe() zeros psk per side). */
    private fun payload(port: Int) = PairingPayload(
        psk = pskValue.copyOf(),
        sid = sidValue.copyOf(),
        ip = listOf("127.0.0.1"),
        port = port,
        expiresAtEpochSeconds = Long.MAX_VALUE / 2,
    )

    /** Same sid/port as [payload] but a caller-chosen PSK — used to drive a fail-closed mismatch. */
    private fun payloadWithPsk(port: Int, psk: ByteArray) = PairingPayload(
        psk = psk.copyOf(),
        sid = sidValue.copyOf(),
        ip = listOf("127.0.0.1"),
        port = port,
        expiresAtEpochSeconds = Long.MAX_VALUE / 2,
    )

    @Test(timeout = 30_000L)
    fun `loopback establishes channel, round-trips, and wipes the PSK`() = runBlocking {
        val port = freePort()
        val factory = NoiseSecureChannelFactory()
        val sendPayload = payload(port)
        val recvPayload = payload(port)

        val acceptDeferred = async(Dispatchers.IO) { factory.acceptAsSender(sendPayload) }
        val recvChannel = factory.connectAsReceiver(recvPayload)
        val sendChannel = acceptDeferred.await()

        val hello = ProtocolMessage.Hello(appVersion = "0.1.0", osFingerprint = "comet:16")
        recvChannel.send(hello)
        assertThat(sendChannel.receive()).isEqualTo(hello)

        val manifest = ProtocolMessage.Manifest(
            TransferManifest(
                senderName = "old phone",
                items = listOf(ItemMeta(1, ItemKind.CONTACTS_VCF, 42, "deadbeef", "Contacts", "People")),
                totalBytes = 42,
            ),
        )
        sendChannel.send(manifest)
        assertThat(recvChannel.receive()).isEqualTo(manifest)

        // Both sides wiped their QR PSK after the handshake consumed it.
        assertThat(sendPayload.psk.all { it == 0.toByte() }).isTrue()
        assertThat(recvPayload.psk.all { it == 0.toByte() }).isTrue()

        recvChannel.close()
        sendChannel.close()
    }

    @Test(timeout = 30_000L)
    fun `first completed handshake consumes the sid and a later same-sid handshake is rejected`() = runBlocking {
        val registry = PskRegistry()
        val factory = NoiseSecureChannelFactory(pskRegistry = registry)

        // Round 1: a REAL handshake completes and consumes the sid (not pre-seeded).
        val port1 = freePort()
        val accept1 = async(Dispatchers.IO) { factory.acceptAsSender(payload(port1)) }
        val recvCh1 = factory.connectAsReceiver(payload(port1))
        val sendCh1 = accept1.await()

        // Round 2: same sid value, fresh ports — the sender must reject AFTER its handshake.
        val port2 = freePort()
        val accept2 = async(Dispatchers.IO) { runCatching { factory.acceptAsSender(payload(port2)) } }
        runCatching { factory.connectAsReceiver(payload(port2)) }.getOrNull()?.close()
        val accept2Result = accept2.await()

        assertThat(accept2Result.isFailure).isTrue()
        assertThat(accept2Result.exceptionOrNull()).isInstanceOf(TransportException::class.java)

        recvCh1.close()
        sendCh1.close()
    }

    @Test(timeout = 30_000L)
    fun `a stalled peer does not hold the listener past the deadline`() = runBlocking {
        val port = freePort()
        // Short bounds so the test is fast; proves the cumulative accept deadline (HIGH-1).
        val factory = NoiseSecureChannelFactory(handshakeTimeoutMs = 800L, acceptDeadlineMs = 4_000L)

        val accept = async(Dispatchers.IO) { runCatching { factory.acceptAsSender(payload(port)) } }

        // Raw client connects (once the server is bound) but never sends a byte.
        var stall: Socket? = null
        for (attempt in 0 until 30) {
            try {
                stall = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 1_000) }
                break
            } catch (e: IOException) {
                delay(100)
            }
        }

        val result = accept.await()
        stall?.close()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TransportException::class.java)
    }

    @Test(timeout = 30_000L)
    fun `a post-handshake read survives a pause longer than the handshake deadline`() = runBlocking {
        val port = freePort()
        // Tight handshake deadline, but a data-phase budget that outlasts the review pause below.
        // Proves the handshake soTimeout is LIFTED once the peer is authenticated, so a human-paced
        // SELECT (manifest review + the "Modify system settings" grant round-trip) can no longer
        // abandon a live transfer the way the leftover 10s handshake deadline did.
        val factory = NoiseSecureChannelFactory(handshakeTimeoutMs = 800L, dataTimeoutMs = 10_000L)
        val sendPayload = payload(port)
        val recvPayload = payload(port)

        val acceptDeferred = async(Dispatchers.IO) { factory.acceptAsSender(sendPayload) }
        val recvChannel = factory.connectAsReceiver(recvPayload)
        val sendChannel = acceptDeferred.await()

        // Sender parks in receive(); the receiver "reviews" far longer than the 800ms handshake
        // deadline before replying. Under the old code the sender's socket still carried that 800ms
        // soTimeout and would throw a TransportException here instead of receiving the message.
        val received = async(Dispatchers.IO) { sendChannel.receive() }
        delay(2_000L) // > handshakeTimeoutMs (800), well under dataTimeoutMs (10_000)
        val hello = ProtocolMessage.Hello(appVersion = "0.1.0", osFingerprint = "comet:16")
        recvChannel.send(hello)

        assertThat(received.await()).isEqualTo(hello)

        recvChannel.close()
        sendChannel.close()
    }

    @Test(timeout = 30_000L)
    fun `a wrong-PSK peer is bounded by the accept deadline, never the data budget`() = runBlocking {
        val port = freePort()
        // A LARGE data budget is configured, but a peer with the WRONG psk never completes the
        // handshake, so the lift (which sits behind tryConsume) is unreachable — it must still fail
        // within the pre-auth accept deadline. Regression guard: if the soTimeout lift were ever
        // hoisted above the auth gate, this peer would inherit the 600s budget and the @Test timeout
        // (30s, << 600s) would catch it.
        val factory = NoiseSecureChannelFactory(
            handshakeTimeoutMs = 800L,
            acceptDeadlineMs = 4_000L,
            dataTimeoutMs = 600_000L,
        )
        val accept = async(Dispatchers.IO) { runCatching { factory.acceptAsSender(payload(port)) } }

        // Receiver presents a non-matching PSK against the same sid/port; the handshake fails closed.
        val wrongPsk = ByteArray(32) { 0x5A }
        async(Dispatchers.IO) { runCatching { factory.connectAsReceiver(payloadWithPsk(port, wrongPsk)) } }

        val result = accept.await()
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TransportException::class.java)
    }
}
