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
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.Base64

/**
 * `portage1:<base64url(CBOR(PairingPayload))>` (PROTOCOL.md §1). Decoding fails closed on
 * wrong scheme, wrong version, or an expired payload — before any network action.
 */
@OptIn(ExperimentalSerializationApi::class)
class PairingCodecImpl : PairingCodec {

    private val cbor = PortageCbor.instance
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    override fun encode(payload: PairingPayload): String {
        val body = cbor.encodeToByteArray(PairingPayload.serializer(), payload)
        return PairingPayload.SCHEME + encoder.encodeToString(body)
    }

    override fun decode(qr: String, nowEpochSeconds: Long): Result<PairingPayload> = runCatching {
        require(qr.startsWith(PairingPayload.SCHEME)) { "not a portage pairing URI" }
        val encoded = qr.removePrefix(PairingPayload.SCHEME)
        require(encoded.length <= MAX_ENCODED_CHARS) { "pairing QR too large" }
        val body = decoder.decode(encoded)
        val payload = cbor.decodeFromByteArray(PairingPayload.serializer(), body)
        // Trust boundary: the QR is attacker-controllable in the malicious-peer scenarios.
        require(payload.version == PairingPayload.PROTOCOL_VERSION) {
            "unsupported protocol version ${payload.version}"
        }
        require(payload.port in 1..65535) { "port out of range" }
        require(payload.ip.size <= MAX_IP_HINTS) { "too many ip hints" }
        require(nowEpochSeconds <= payload.expiresAtEpochSeconds) { "pairing QR expired" }
        // Reject a far-future expiry that would defeat the short-TTL replay window.
        require(payload.expiresAtEpochSeconds <= nowEpochSeconds + MAX_REMAINING_TTL_SECONDS) {
            "pairing QR expiry implausibly far in the future"
        }
        payload
    }

    private companion object {
        const val MAX_ENCODED_CHARS = 1024
        const val MAX_IP_HINTS = 8
        const val MAX_REMAINING_TTL_SECONDS = PairingPayload.DEFAULT_TTL_SECONDS + 30L
    }
}
