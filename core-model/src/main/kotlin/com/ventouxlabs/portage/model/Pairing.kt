/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.model

import kotlinx.serialization.Serializable

/**
 * Payload encoded into the pairing QR. Serialized as CBOR then base64url and prefixed
 * with the URI scheme [SCHEME]. The QR is the OUT-OF-BAND TRUST ANCHOR — see
 * docs/prp/PROTOCOL.md §1 and docs/prp/THREAT_MODEL.md (QR-interception row).
 *
 * @property psk one-time 32-byte pre-shared key (CSPRNG); the Noise XXpsk3 authenticator.
 * @property sid 16-byte session id; public, also matches the mDNS instance name.
 * @property ip best-effort address hints (untrusted; auth is end-to-end regardless).
 * @property port sender's listening TCP port.
 * @property expiresAtEpochSeconds QR validity deadline (default issue + 120 s).
 */
@Serializable
data class PairingPayload(
    val version: Int = PROTOCOL_VERSION,
    val psk: ByteArray,
    val sid: ByteArray,
    val ip: List<String>,
    val port: Int,
    val expiresAtEpochSeconds: Long,
) {
    init {
        require(psk.size == PSK_BYTES) { "psk must be $PSK_BYTES bytes" }
        require(sid.size == SID_BYTES) { "sid must be $SID_BYTES bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingPayload) return false
        return version == other.version &&
            psk.contentEquals(other.psk) &&
            sid.contentEquals(other.sid) &&
            ip == other.ip &&
            port == other.port &&
            expiresAtEpochSeconds == other.expiresAtEpochSeconds
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + psk.contentHashCode()
        result = 31 * result + sid.contentHashCode()
        result = 31 * result + ip.hashCode()
        result = 31 * result + port
        result = 31 * result + expiresAtEpochSeconds.hashCode()
        return result
    }

    /**
     * Best-effort zeroization of the secret [psk]. Call once the handshake has consumed it
     * (security review 2026-06-10, LOW). The app owns this copy; noise-java wipes its own
     * internal copy via Destroyable. Mind the resume feature if statics must outlive this.
     */
    fun wipe() {
        psk.fill(0)
    }

    companion object {
        // v3 adds SOUND_FILE. ItemKind is serialized as an enum, so an unknown kind cannot be
        // decoded by a v1 peer; fail during QR validation instead of failing after pairing.
        const val PROTOCOL_VERSION = 3
        const val PSK_BYTES = 32
        const val SID_BYTES = 16
        const val SCHEME = "portage1:"
        const val DEFAULT_TTL_SECONDS = 120L
    }
}
