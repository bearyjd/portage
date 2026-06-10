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
    fun `the first completed handshake consumes the sid; a later same-sid handshake is rejected`() = runBlocking {
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
}
