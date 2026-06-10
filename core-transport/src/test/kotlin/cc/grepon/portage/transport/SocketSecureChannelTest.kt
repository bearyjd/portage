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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.ServerSocket

/**
 * End-to-end over real TCP loopback: the proven NoisePSK_XX core wrapped in the socket
 * transport + SecureChannel factory, exercising the listener-layer controls from
 * ADR-002 §Follow-ups (handshake over sockets, PSK wipe, second-suitor lockout).
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
    fun `a consumed session is rejected (second-suitor lockout)`() = runBlocking {
        val port = freePort()
        val registry = PskRegistry()
        registry.tryConsume(sidValue.copyOf()) // simulate a prior successful handshake
        val factory = NoiseSecureChannelFactory(pskRegistry = registry)
        val sendPayload = payload(port)
        val recvPayload = payload(port)

        val acceptDeferred = async(Dispatchers.IO) { runCatching { factory.acceptAsSender(sendPayload) } }
        // The receiver still completes its side of the handshake; the sender rejects after.
        runCatching { factory.connectAsReceiver(recvPayload) }.getOrNull()?.close()
        val acceptResult = acceptDeferred.await()

        assertThat(acceptResult.isFailure).isTrue()
        assertThat(acceptResult.exceptionOrNull()).isInstanceOf(TransportException::class.java)
    }
}
