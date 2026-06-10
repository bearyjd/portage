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
import java.io.EOFException

/**
 * Noise handshake + transport over a [FrameTransport], using vendored noise-java in the
 * `NoisePSK_XX_25519_ChaChaPoly_SHA256` pattern (ADR-002). The QR-carried PSK is the mutual
 * authenticator: an attacker without it cannot complete the handshake (THREAT_MODEL.md #2).
 *
 * This is the crypto core. The suspend `SecureChannel` + TCP/NSD wiring will wrap a
 * [NoiseSession] produced here; the spike proves the handshake and AEAD transport in
 * isolation (see NoiseLoopbackTest).
 */
object NoiseChannel {
    const val PROTOCOL_NAME = "NoisePSK_XX_25519_ChaChaPoly_SHA256"
    private const val HANDSHAKE_BUF = 4096

    /**
     * Prologue mixed into the transcript (PROTOCOL.md §2): binds protocol version + session
     * id so a spliced or cross-session handshake cannot complete. Both sides MUST match.
     */
    fun prologue(version: Int, sid: ByteArray): ByteArray =
        "portage".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(version.toByte()) +
            sid +
            "recv->send".toByteArray(Charsets.US_ASCII)

    /** [role] is [HandshakeState.INITIATOR] (receiver) or [HandshakeState.RESPONDER] (sender). */
    fun handshake(transport: FrameTransport, role: Int, psk: ByteArray, prologue: ByteArray): CipherStatePair {
        val hs = HandshakeState(PROTOCOL_NAME, role)
        try {
            hs.setPreSharedKey(psk, 0, psk.size)
            hs.setPrologue(prologue, 0, prologue.size)
            if (hs.needsLocalKeyPair()) hs.localKeyPair.generateKeyPair()
            hs.start()
            val msgBuf = ByteArray(HANDSHAKE_BUF)
            val payloadBuf = ByteArray(HANDSHAKE_BUF)
            while (true) {
                when (hs.action) {
                    HandshakeState.WRITE_MESSAGE -> {
                        val len = hs.writeMessage(msgBuf, 0, EMPTY, 0, 0)
                        transport.writeFrame(msgBuf.copyOf(len))
                    }
                    HandshakeState.READ_MESSAGE -> {
                        val frame = transport.readFrame()
                        hs.readMessage(frame, 0, frame.size, payloadBuf, 0)
                    }
                    HandshakeState.SPLIT -> return hs.split()
                    HandshakeState.FAILED -> throw TransportException("Noise handshake failed (bad PSK or tampered transcript)")
                    else -> throw TransportException("Unexpected handshake action: ${hs.action}")
                }
            }
        } finally {
            hs.destroy()
        }
    }

    private val EMPTY = ByteArray(0)
}

/**
 * An established Noise transport session. Blocking by design — the suspend `SecureChannel`
 * wrapper dispatches these onto IO. One [send]/[receive] = one AEAD frame.
 */
class NoiseSession(
    private val transport: FrameTransport,
    private val keys: CipherStatePair,
    private val codec: MessageCodec = CborMessageCodec(),
) : AutoCloseable {

    fun send(message: ProtocolMessage) {
        val plain = codec.encode(message)
        val out = ByteArray(plain.size + keys.sender.macLength)
        val n = keys.sender.encryptWithAd(null, plain, 0, out, 0, plain.size)
        transport.writeFrame(if (n == out.size) out else out.copyOf(n))
    }

    /** Returns the next message, or null at clean end-of-stream. */
    fun receive(): ProtocolMessage? {
        val frame = try {
            transport.readFrame()
        } catch (_: EOFException) {
            return null
        }
        val out = ByteArray(frame.size)
        val n = keys.receiver.decryptWithAd(null, frame, 0, out, 0, frame.size)
        return codec.decode(out.copyOf(n))
    }

    override fun close() {
        keys.destroy()
        transport.close()
    }
}
