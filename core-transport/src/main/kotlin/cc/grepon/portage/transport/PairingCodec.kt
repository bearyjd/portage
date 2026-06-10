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

/**
 * Encodes/decodes the pairing QR string: `portage1:<base64url(CBOR(PairingPayload))>`.
 * See PROTOCOL.md §1 (QR payload). Decoding MUST reject expired payloads and wrong
 * version before any network action.
 */
interface PairingCodec {
    fun encode(payload: PairingPayload): String
    fun decode(qr: String, nowEpochSeconds: Long): Result<PairingPayload>
}
