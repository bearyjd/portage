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
import com.ventouxlabs.portage.model.PairingMode
import com.ventouxlabs.portage.model.ProtocolMessage
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
    private val serverSocketFactory: () -> ServerSocket = { ServerSocket() },
) : SecureChannel.Factory {

    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel =
        connectAsReceiver(payload, null, null)

    override suspend fun connectAsReceiver(
        payload: PairingPayload,
        resumeCredential: ByteArray?,
        lineageId: String?,
    ): SecureChannel = withHandshakeMaterial(payload, resumeCredential, lineageId) { material ->
        val socket = connectWithRetry(payload)
        var keys: CipherStatePair? = null
        try {
            val transport = SocketFrameTransport(socket)
            keys = handshakeWithDeadline(transport, socket, HandshakeState.INITIATOR, material)
            // Human-paced review and apply happen only after peer authentication.
            socket.soTimeout = dataTimeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            NoiseSecureChannel(NoiseSession(transport, keys))
        } catch (t: Throwable) {
            keys?.destroy()
            runCatching { socket.close() }
            throw t
        }
    }

    override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel =
        acceptAsSender(payload, null, null)

    override suspend fun acceptAsSender(
        payload: PairingPayload,
        resumeCredential: ByteArray?,
        lineageId: String?,
    ): SecureChannel = withHandshakeMaterial(payload, resumeCredential, lineageId) { material ->
        val server = serverSocketFactory()
        // Start registration before accept can block. This child belongs to the IO
        // ownership scope, which cannot complete until the listener has been closed.
        val cancellationCloser = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { server.close() }
            }
        }
        // One cumulative wall-clock budget for the whole listener (THREAT_MODEL #7/#11):
        // failed/stalled suitors cannot reset it, so the listener can't be held forever.
        val deadlineNanos = System.nanoTime() + acceptDeadlineMs * 1_000_000L
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(payload.port))
            // Accept until ONE handshake completes, within the total budget. A bad first
            // suitor closes and the next is accepted (anti-lockout), but every accept()
            // draws from the SAME shrinking budget.
            while (true) {
                currentCoroutineContext().ensureActive()
                val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) throw TransportException("no peer completed the handshake within the deadline")
                server.soTimeout = remainingMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    throw TransportException("no peer completed the handshake within the deadline", e)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw TransportException("listener accept failed", e)
                }
                var keys: CipherStatePair? = null
                var transferred = false
                try {
                    val budgetMs = minOf(handshakeTimeoutMs, (deadlineNanos - System.nanoTime()) / 1_000_000L)
                    if (budgetMs <= 0) throw TransportException("listener deadline expired")
                    socket.soTimeout = budgetMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    val transport = SocketFrameTransport(socket)
                    keys = try {
                        handshakeWithDeadline(transport, socket, HandshakeState.RESPONDER, material, budgetMs)
                    } catch (_: TransportException) {
                        continue // failed suitor — keep listening within the original budget
                    }
                    if (pskRegistry.tryConsume(material.sid)) {
                        // Authenticated: lift the handshake deadline to the data-phase budget. The
                        // sender's next read blocks until the receiver's human-paced SELECT (manifest
                        // review + the "Modify system settings" grant round-trip) — the 10s handshake
                        // soTimeout would fail the transfer the moment review outlasts it.
                        socket.soTimeout = dataTimeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                        val channel = NoiseSecureChannel(NoiseSession(transport, keys))
                        transferred = true
                        return@withHandshakeMaterial channel
                    }
                    throw TransportException("session already consumed")
                } finally {
                    if (!transferred) {
                        keys?.destroy()
                        runCatching { socket.close() }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw TransportException("listener exited unexpectedly")
        } finally {
            cancellationCloser.cancel()
            runCatching { server.close() }
        }
    }

    /** Keeps cleanup outside withContext too: dispatch cancellation can precede entry or discard a result. */
    private suspend fun withHandshakeMaterial(
        payload: PairingPayload,
        resumeCredential: ByteArray?,
        lineageId: String?,
        action: suspend CoroutineScope.(HandshakeMaterial) -> SecureChannel,
    ): SecureChannel {
        var completed: SecureChannel? = null
        try {
            return withContext(Dispatchers.IO) {
                val material = prepareMaterial(payload, resumeCredential, lineageId)
                try {
                    action(material).also { completed = it }
                } finally {
                    material.close()
                }
            }
        } catch (t: Throwable) {
            runCatching { completed?.close() }
            currentCoroutineContext().ensureActive()
            throw t
        } finally {
            // Includes validation failure, dial failure and cancellation before entering IO.
            payload.wipe()
        }
    }

    private class HandshakeMaterial(val psk: ByteArray, val prologue: ByteArray, val sid: ByteArray) : AutoCloseable {
        override fun close() {
            psk.fill(0)
            prologue.fill(0)
            sid.fill(0)
        }
    }

    private fun prepareMaterial(
        payload: PairingPayload,
        resumeCredential: ByteArray?,
        lineageId: String?,
    ): HandshakeMaterial {
        var psk: ByteArray? = null
        var prologue: ByteArray? = null
        var sid: ByteArray? = null
        try {
            require(payload.version == PairingPayload.PROTOCOL_VERSION) { "unsupported protocol version" }
            require(payload.psk.size == PairingPayload.PSK_BYTES) { "QR PSK must be 32 bytes" }
            require(payload.sid.size == PairingPayload.SID_BYTES) { "sid must be 16 bytes" }
            when (payload.mode) {
                PairingMode.NEW -> require(resumeCredential == null && lineageId == null) {
                    "NEW cannot supply resume credentials or stored lineage"
                }
                PairingMode.RESUME -> require(resumeCredential?.size == ResumeKeyDerivation.CREDENTIAL_BYTES) {
                    "RESUME requires a stored 32-byte resume credential"
                }
            }
            sid = payload.sid.copyOf()
            prologue = NoiseChannel.prologue(payload.version, sid, payload.mode, lineageId)
            psk = when (payload.mode) {
                PairingMode.NEW -> payload.psk.copyOf()
                PairingMode.RESUME -> ResumeKeyDerivation.derive(
                    payload.psk, requireNotNull(resumeCredential), payload.version, sid, requireNotNull(lineageId),
                )
            }
            return HandshakeMaterial(psk, prologue, sid)
        } catch (t: Throwable) {
            psk?.fill(0)
            prologue?.fill(0)
            sid?.fill(0)
            if (t is IllegalArgumentException) throw TransportException("invalid handshake context", t)
            throw t
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
        material: HandshakeMaterial,
        timeoutMs: Long = handshakeTimeoutMs,
    ): CipherStatePair {
        var splitKeys: CipherStatePair? = null
        try {
            return coroutineScope {
                val owner = currentCoroutineContext()
                val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        delay(timeoutMs)
                        runCatching { socket.close() }
                    } finally {
                        // Cancelling the owner must unblock a native handshake read too.
                        // Normal watchdog cancellation after success leaves the socket open.
                        if (!owner.isActive) runCatching { socket.close() }
                    }
                }
                try {
                    NoiseChannel.handshake(transport, role, material.psk, material.prologue).also { splitKeys = it }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (t: Throwable) {
            // Cancellation at coroutineScope's exit must not discard an already-completed split.
            splitKeys?.destroy()
            currentCoroutineContext().ensureActive()
            throw t
        }
    }

    private suspend fun connectWithRetry(payload: PairingPayload): Socket {
        val host = payload.ip.firstOrNull() ?: throw TransportException("no address in pairing payload")
        var last: Exception? = null
        for (attempt in 0 until CONNECT_RETRIES) {
            val socket = Socket()
            var connected = false
            try {
                socket.apply {
                    connect(InetSocketAddress(host, payload.port), CONNECT_TIMEOUT_MS)
                    soTimeout = handshakeTimeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                }
                connected = true
                return socket
            } catch (e: IOException) {
                last = e
            } finally {
                if (!connected) runCatching { socket.close() }
            }
            delay(CONNECT_RETRY_DELAY_MS)
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
