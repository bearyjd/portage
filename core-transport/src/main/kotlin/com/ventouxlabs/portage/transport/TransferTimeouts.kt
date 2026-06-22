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

/**
 * Aggregate wall-clock ceiling on the AUTHENTICATED data phase. The CONSUMERS
 * (`ReceiverViewModel` / `SenderViewModel`) apply it with `withTimeoutOrNull` around the whole
 * item stream; the single authoritative value lives here so the two apps can never drift.
 *
 * Complements — does NOT replace — the transport's per-read idle budget (the factory's private
 * `DATA_TIMEOUT_MS`, 10 min, applied as the post-handshake socket `soTimeout`). That budget bounds ONE stalled frame; an
 * authenticated-but-malicious peer (it holds the QR-scanned PSK) can still stall just under it on
 * every frame, frame after frame, for an unbounded TOTAL — a resource-exhaustion / availability
 * vector (#53 review, MEDIUM-1). This cap bounds the whole phase, closing that vector.
 *
 * Effective ceiling caveat: `receive()` is `withContext(Dispatchers.IO) { blocking socket read }`,
 * and coroutine cancellation cannot interrupt a thread parked in a native read (the same reason
 * the handshake uses a socket-closing watchdog — see `NoiseSecureChannel.handshakeWithDeadline`).
 * So a peer parked silent in a read is unblocked not by this timer but by the socket `soTimeout`
 * (`DATA_TIMEOUT_MS`); the elapsed budget then converts the resumed read to a `null` timeout. The
 * real ceiling is therefore this value PLUS at most one per-read `soTimeout` (~10 min), not a crisp
 * 60 min. Still finite — which is the whole point — just not razor-tight. A future tightening would
 * add a deadline watchdog that closes the channel (deferred; the bound is already finite and the
 * phase is single-channel + user-cancellable, so ±one soTimeout on an hour-long backstop is moot).
 *
 * Generous on purpose so it never false-trips a legitimate transfer: the relay path raises the
 * per-item cap to 2 GiB (`MAX_RELAY_ITEM_BYTES`), so a real multi-GiB app-backup over a slow LAN
 * can run tens of minutes; the sender side also spends part of the budget waiting for the
 * receiver's human review (SELECT). 60 min clears both with headroom while still bounding abuse.
 * Injectable per ViewModel so tests pin it on virtual time.
 */
const val DATA_PHASE_TIMEOUT_MS: Long = 60L * 60 * 1000 // 60 min
