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
import java.io.EOFException
import java.security.GeneralSecurityException

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

    /** u16 framing cap (PROTOCOL.md §3); Noise max plaintext is 65519. */
    const val MAX_FRAME_BYTES = 65535

    /**
     * Binds version, mode, SID, direction and (for RESUME) locally stored lineage.
     * Exact byte encoding is specified in [ResumeKeyDerivation]. Both sides MUST match.
     */
    fun prologue(
        version: Int,
        sid: ByteArray,
        mode: PairingMode = PairingMode.NEW,
        lineageId: String? = null,
    ): ByteArray = ResumeKeyDerivation.context("portage-noise-prologue", version, mode, sid, lineageId)

    /** [role] is [HandshakeState.INITIATOR] (receiver) or [HandshakeState.RESPONDER] (sender). */
    fun handshake(transport: FrameTransport, role: Int, psk: ByteArray, prologue: ByteArray): CipherStatePair {
        require(psk.size == PairingPayload.PSK_BYTES) { "Noise PSK must be 32 bytes" }
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
                        if (frame.size > MAX_FRAME_BYTES) {
                            throw TransportException("handshake frame exceeds ${MAX_FRAME_BYTES}B cap")
                        }
                        hs.readMessage(frame, 0, frame.size, payloadBuf, 0)
                    }
                    HandshakeState.SPLIT -> return hs.split()
                    HandshakeState.FAILED -> throw TransportException("Noise handshake failed (bad PSK or tampered transcript)")
                    else -> throw TransportException("Unexpected handshake action: ${hs.action}")
                }
            }
        } catch (e: TransportException) {
            throw e
        } catch (e: GeneralSecurityException) {
            // BadPaddingException / ShortBufferException / NoSuchAlgorithmException — fail closed.
            throw TransportException("handshake authentication failed", e)
        } catch (e: EOFException) {
            throw TransportException("handshake aborted: peer closed connection", e)
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
        try {
            val out = ByteArray(plain.size + keys.sender.macLength)
            val n = keys.sender.encryptWithAd(null, plain, 0, out, 0, plain.size)
            transport.writeFrame(if (n == out.size) out else out.copyOf(n))
        } finally {
            // LINEAGE_INIT plaintext contains the long-lived resume credential.
            plain.fill(0)
        }
    }

    /** Returns the next message, or null at clean end-of-stream. */
    fun receive(): ProtocolMessage? {
        val frame = try {
            transport.readFrame()
        } catch (_: EOFException) {
            return null
        }
        if (frame.size > NoiseChannel.MAX_FRAME_BYTES) {
            throw TransportException("frame exceeds ${NoiseChannel.MAX_FRAME_BYTES}B cap")
        }
        val out = ByteArray(frame.size)
        var plain: ByteArray? = null
        // Fail-closed (SecureChannel contract): an authenticated-but-malicious peer can encrypt a
        // plaintext the codec rejects — empty, an unknown type byte, an over-cap body, or malformed
        // CBOR (THREAT_MODEL #10). Map any decode failure to TransportException so callers never see a
        // raw IllegalArgumentException/SerializationException, which they don't treat as fatal.
        return try {
            val n = try {
                keys.receiver.decryptWithAd(null, frame, 0, out, 0, frame.size)
            } catch (e: GeneralSecurityException) {
                throw TransportException("frame authentication failed", e)
            }
            plain = out.copyOf(n)
            codec.decode(plain)
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            throw TransportException("malformed application frame", e)
        } finally {
            plain?.fill(0)
            out.fill(0)
        }
    }

    override fun close() {
        keys.destroy()
        transport.close()
    }
}
