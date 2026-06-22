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

import com.ventouxlabs.portage.model.PairingPayload
import com.ventouxlabs.portage.model.ProtocolMessage
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * ADR-002 §Follow-ups: per-session PSK single-use ([PskRegistry]), a hard handshake
 * deadline, a bounded total listener lifetime, and [PairingPayload.wipe] of the QR PSK
 * once the handshake has consumed it.
 */
class NoiseSecureChannelFactory(
    private val pskRegistry: PskRegistry = PskRegistry(),
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    private val acceptDeadlineMs: Long = ACCEPT_DEADLINE_MS,
    private val dataTimeoutMs: Long = DATA_TIMEOUT_MS,
) : SecureChannel.Factory {

    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = withContext(Dispatchers.IO) {
        val socket = connectWithRetry(payload)
        val transport = SocketFrameTransport(socket)
        try {
            val prologue = NoiseChannel.prologue(payload.version, payload.sid)
            val keys = handshakeWithDeadline(transport, socket, HandshakeState.INITIATOR, payload, prologue)
            // Handshake done: the peer is PSK-authenticated, so lift the tight handshake deadline
            // to the generous data-phase budget. Post-handshake reads wait on the OTHER side's
            // human-paced actions (manifest review, the "Modify system settings" grant round-trip,
            // item acks); the 10s handshake soTimeout would abandon a live transfer mid-review.
            socket.soTimeout = dataTimeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
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
        // One cumulative wall-clock budget for the whole listener (THREAT_MODEL #7/#11):
        // failed/stalled suitors cannot reset it, so the listener can't be held forever.
        val deadlineNanos = System.nanoTime() + acceptDeadlineMs * 1_000_000L
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(payload.port))
            val prologue = NoiseChannel.prologue(payload.version, payload.sid)

            // Accept until ONE handshake completes, within the total budget. A bad first
            // suitor closes and the next is accepted (anti-lockout), but every accept()
            // draws from the SAME shrinking budget.
            while (true) {
                val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) throw TransportException("no peer completed the handshake within the deadline")
                server.soTimeout = remainingMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    throw TransportException("no peer completed the handshake within the deadline", e)
                } catch (e: IOException) {
                    throw TransportException("listener accept failed", e)
                }
                socket.soTimeout = handshakeTimeoutMs.toInt() // before wrapping the streams
                val transport = SocketFrameTransport(socket)

                val keys: CipherStatePair? = try {
                    handshakeWithDeadline(transport, socket, HandshakeState.RESPONDER, payload, prologue)
                } catch (e: TransportException) {
                    transport.close(); null // failed suitor — keep listening within budget
                }
                if (keys != null) {
                    if (pskRegistry.tryConsume(payload.sid)) {
                        // Authenticated: lift the handshake deadline to the data-phase budget. The
                        // sender's next read blocks until the receiver's human-paced SELECT (manifest
                        // review + the "Modify system settings" grant round-trip) — the 10s handshake
                        // soTimeout would fail the transfer the moment review outlasts it.
                        socket.soTimeout = dataTimeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
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

    /**
     * Runs the blocking Noise handshake with a HARD deadline. `withTimeout` cannot interrupt
     * a thread parked in a native socket read, so a watchdog closes the socket at the
     * deadline — that unblocks the read, which surfaces as a fail-closed [TransportException].
     */
    private suspend fun handshakeWithDeadline(
        transport: SocketFrameTransport,
        socket: Socket,
        role: Int,
        payload: PairingPayload,
        prologue: ByteArray,
    ): CipherStatePair = coroutineScope {
        val watchdog = launch {
            delay(handshakeTimeoutMs)
            runCatching { socket.close() }
        }
        try {
            NoiseChannel.handshake(transport, role, payload.psk, prologue)
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun connectWithRetry(payload: PairingPayload): Socket {
        val host = payload.ip.firstOrNull() ?: throw TransportException("no address in pairing payload")
        var last: Exception? = null
        for (attempt in 0 until CONNECT_RETRIES) {
            try {
                return Socket().apply {
                    connect(InetSocketAddress(host, payload.port), CONNECT_TIMEOUT_MS)
                    soTimeout = handshakeTimeoutMs.toInt()
                }
            } catch (e: IOException) {
                last = e
                delay(CONNECT_RETRY_DELAY_MS)
            }
        }
        throw TransportException("could not connect to $host:${payload.port}", last)
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val ACCEPT_DEADLINE_MS = 120_000L

        /**
         * Per-read idle budget for the AUTHENTICATED data phase (after a successful handshake).
         * Generous on purpose: the sender blocks here waiting for the receiver's SELECT, which is
         * gated on a human reviewing the manifest and completing the "Modify system settings"
         * grant round-trip. This is NOT a pre-auth control — the bounded listener lifetime
         * (ACCEPT_DEADLINE_MS) and handshake deadline (HANDSHAKE_TIMEOUT_MS) still apply to
         * unauthenticated peers; this budget only ever applies once the PSK handshake has passed.
         */
        const val DATA_TIMEOUT_MS = 600_000L // 10 min
        const val CONNECT_TIMEOUT_MS = 3_000
        const val CONNECT_RETRIES = 15
        const val CONNECT_RETRY_DELAY_MS = 200L
    }
}
