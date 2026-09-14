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

/**
 * A mutually-authenticated, encrypted, framed channel between the two phones.
 * Implementations wrap a Noise XXpsk3 handshake (PROTOCOL.md §2) over TCP; ALL payload
 * rides inside AEAD frames. The PSK from [PairingPayload] is the authenticator.
 *
 * Fail-closed: any handshake/AEAD/framing anomaly throws and closes (THREAT_MODEL.md §4).
 */
interface SecureChannel : AutoCloseable {

    /** Send one application message as a single Noise transport frame. */
    suspend fun send(message: ProtocolMessage)

    /** Receive the next application message, or null at clean end-of-stream. */
    suspend fun receive(): ProtocolMessage?

    /**
     * Implementations MUST call [PairingPayload.wipe] once the handshake has consumed the
     * PSK (security review 2026-06-10), and enforce the listener-layer controls tracked in
     * ADR-002 §Follow-ups: PSK single-use consumption, 10 s handshake timeout, u16 wire cap.
     */
    interface Factory {
        /** Receiver side: dial [payload].ip/port and run the handshake as Noise initiator. */
        suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel

        /**
         * RESUME additionally requires the locally stored 32-byte credential and canonical lineage.
         * NEW requires both optional arguments to be absent. Implementations copy borrowed resume
         * credentials and wipe their copies plus [payload]'s QR PSK on every exit, including failure.
         * The caller retains ownership of its stored credential and must not mutate it during this call.
         * A legacy factory cannot accept a resume request through this overload.
         */
        suspend fun connectAsReceiver(
            payload: PairingPayload,
            resumeCredential: ByteArray?,
            lineageId: String? = null,
        ): SecureChannel {
            requireNewOnly(payload, resumeCredential, lineageId)
            return connectAsReceiver(payload)
        }

        /**
         * Sender side: listen, accept exactly ONE completed handshake for this session,
         * then mark the PSK consumed (replay/second-suitor lockout, THREAT_MODEL.md #4/#7).
         */
        suspend fun acceptAsSender(payload: PairingPayload): SecureChannel

        /** Sender equivalent of the credential-aware [connectAsReceiver] overload. */
        suspend fun acceptAsSender(
            payload: PairingPayload,
            resumeCredential: ByteArray?,
            lineageId: String? = null,
        ): SecureChannel {
            requireNewOnly(payload, resumeCredential, lineageId)
            return acceptAsSender(payload)
        }

        private fun requireNewOnly(payload: PairingPayload, credential: ByteArray?, lineageId: String?) {
            if (payload.mode != PairingMode.NEW || credential != null || lineageId != null) {
                payload.wipe()
                throw TransportException("factory does not support resume credentials")
            }
        }
    }
}

/** Thrown on any transport-layer failure; callers treat it as fatal to the session. */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
