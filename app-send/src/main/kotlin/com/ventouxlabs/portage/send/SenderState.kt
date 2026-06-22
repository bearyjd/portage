/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send

/** Where one requested item is in its stream→ack lifecycle on the sender. */
enum class SendPhase { QUEUED, SENDING, ACKED, FAILED }

/** Per-item progress row while sending. */
data class SendProgress(
    val itemId: Int,
    val displayName: String,
    val totalBytes: Long,
    val bytesSent: Long = 0,
    val phase: SendPhase = SendPhase.QUEUED,
    val detail: String? = null,
)

/** The sender's single screen state (portage-prp-prompt.md §7: "Transfer to new phone"). */
sealed interface SenderState {
    /** Landing: device summary + permissions + "Start transfer". */
    data object Home : SenderState

    /** Exporting available domains into staging and building the manifest. */
    data object Preparing : SenderState

    /** QR up (the trust anchor), TCP listener armed, waiting for the receiver to scan. */
    data class ShowingQr(val qrText: String, val itemCount: Int, val totalBytes: Long) : SenderState

    /** Handshake complete; manifest sent; waiting for the receiver's SELECT picks. */
    data object Linked : SenderState

    /** Streaming the receiver's picks, tracked per item. */
    data class Sending(val items: List<SendProgress>) : SenderState

    /** Done summary from the receiver's acks. */
    data class Done(val sent: Int, val failed: Int) : SenderState

    /** Fail-closed terminal state with a user-facing reason. */
    data class Failed(val reason: String) : SenderState
}
