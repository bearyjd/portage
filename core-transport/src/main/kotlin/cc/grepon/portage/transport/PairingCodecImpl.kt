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
import kotlinx.serialization.cbor.Cbor
import java.util.Base64

/**
 * `portage1:<base64url(CBOR(PairingPayload))>` (PROTOCOL.md §1). Decoding fails closed on
 * wrong scheme, wrong version, or an expired payload — before any network action.
 */
@OptIn(ExperimentalSerializationApi::class)
class PairingCodecImpl : PairingCodec {

    private val cbor = Cbor { ignoreUnknownKeys = true }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    override fun encode(payload: PairingPayload): String {
        val body = cbor.encodeToByteArray(PairingPayload.serializer(), payload)
        return PairingPayload.SCHEME + encoder.encodeToString(body)
    }

    override fun decode(qr: String, nowEpochSeconds: Long): Result<PairingPayload> = runCatching {
        require(qr.startsWith(PairingPayload.SCHEME)) { "not a portage pairing URI" }
        val body = decoder.decode(qr.removePrefix(PairingPayload.SCHEME))
        val payload = cbor.decodeFromByteArray(PairingPayload.serializer(), body)
        require(payload.version == PairingPayload.PROTOCOL_VERSION) {
            "unsupported protocol version ${payload.version}"
        }
        require(nowEpochSeconds <= payload.expiresAtEpochSeconds) { "pairing QR expired" }
        payload
    }
}
