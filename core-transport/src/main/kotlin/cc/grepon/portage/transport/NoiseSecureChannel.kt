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

import cc.grepon.portage.model.PairingPayload
import cc.grepon.portage.model.ProtocolMessage
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * [SecureChannel] over a [NoiseSession]. The session is blocking by design; the suspend
 * surface dispatches onto [Dispatchers.IO].
 */
class NoiseSecureChannel(private val session: NoiseSession) : SecureChannel {
    override suspend fun send(message: ProtocolMessage) = withContext(Dispatchers.IO) { session.send(message) }
    override suspend fun receive(): ProtocolMessage? = withContext(Dispatchers.IO) { session.receive() }
    override fun close() = session.close()
}

/**
 * Builds [NoiseSecureChannel]s over TCP. Enforces the listener-layer controls from
 * ADR-002 §Follow-ups: per-session PSK single-use ([PskRegistry]), a handshake timeout
 * (socket `soTimeout` + [withTimeout]), and [PairingPayload.wipe] of the QR PSK once the
 * handshake has consumed it.
 */
class NoiseSecureChannelFactory(
    private val pskRegistry: PskRegistry = PskRegistry(),
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) : SecureChannel.Factory {

    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = withContext(Dispatchers.IO) {
        val transport = SocketFrameTransport(connectWithRetry(payload))
        try {
            val prologue = NoiseChannel.prologue(payload.version, payload.sid)
            val keys = withTimeout(handshakeTimeoutMs) {
                NoiseChannel.handshake(transport, HandshakeState.INITIATOR, payload.psk, prologue)
            }
            NoiseSecureChannel(NoiseSession(transport, keys))
        } catch (t: Throwable) {
            transport.close()
            throw t
        } finally {
            payload.wipe()
        }
    }

    override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel = withContext(Dispatchers.IO) {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(payload.port))
            server.soTimeout = ACCEPT_DEADLINE_MS
            val prologue = NoiseChannel.prologue(payload.version, payload.sid)

            // Accept until ONE handshake completes. A failed (e.g. attacker, no-PSK) attempt
            // is closed and the next connection is accepted — a bad first suitor must not
            // lock out the real receiver. The first SUCCESS consumes the sid (lockout).
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    throw TransportException("no peer completed the handshake within the deadline", e)
                }
                val transport = SocketFrameTransport(socket)
                socket.soTimeout = handshakeTimeoutMs.toInt()
                val keys: CipherStatePair? = try {
                    withTimeout(handshakeTimeoutMs) {
                        NoiseChannel.handshake(transport, HandshakeState.RESPONDER, payload.psk, prologue)
                    }
                } catch (e: TransportException) {
                    transport.close(); null
                } catch (e: TimeoutCancellationException) {
                    transport.close(); null
                }
                if (keys != null) {
                    if (pskRegistry.tryConsume(payload.sid)) {
                        return@withContext NoiseSecureChannel(NoiseSession(transport, keys))
                    }
                    transport.close()
                    throw TransportException("session already consumed")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw TransportException("listener exited unexpectedly")
        } finally {
            runCatching { server.close() }
            payload.wipe()
        }
    }

    private fun connectWithRetry(payload: PairingPayload): Socket {
        val host = payload.ip.firstOrNull() ?: throw TransportException("no address in pairing payload")
        var last: Exception? = null
        repeat(CONNECT_RETRIES) {
            try {
                return Socket().apply {
                    connect(InetSocketAddress(host, payload.port), CONNECT_TIMEOUT_MS)
                    soTimeout = handshakeTimeoutMs.toInt()
                }
            } catch (e: IOException) {
                last = e
                Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
        }
        throw TransportException("could not connect to $host:${payload.port}", last)
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val ACCEPT_DEADLINE_MS = 120_000
        const val CONNECT_TIMEOUT_MS = 3_000
        const val CONNECT_RETRIES = 15
        const val CONNECT_RETRY_DELAY_MS = 200L
    }
}
