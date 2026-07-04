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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Aggregate wall-clock ceiling on the AUTHENTICATED data phase. The CONSUMERS
 * (`ReceiverViewModel` / `SenderViewModel`) apply it with [withDataPhaseDeadline] around the
 * whole item stream; the single authoritative value AND mechanism live here so the two apps
 * can never drift.
 *
 * Complements — does NOT replace — the transport's per-read idle budget (the factory's private
 * `DATA_TIMEOUT_MS`, 10 min, applied as the post-handshake socket `soTimeout`). That budget bounds ONE stalled frame; an
 * authenticated-but-malicious peer (it holds the QR-scanned PSK) can still stall just under it on
 * every frame, frame after frame, for an unbounded TOTAL — a resource-exhaustion / availability
 * vector (#53 review, MEDIUM-1). This cap bounds the whole phase, closing that vector.
 *
 * Generous on purpose so it never false-trips a legitimate transfer: the relay path raises the
 * per-item cap to 2 GiB (`MAX_RELAY_ITEM_BYTES`), so a real multi-GiB app-backup over a slow LAN
 * can run tens of minutes; the sender side also spends part of the budget waiting for the
 * receiver's human review (SELECT). 60 min clears both with headroom while still bounding abuse.
 * Injectable per ViewModel so tests pin it on virtual time.
 */
const val DATA_PHASE_TIMEOUT_MS: Long = 60L * 60 * 1000 // 60 min

/**
 * Runs [block] under a HARD aggregate deadline of [timeoutMs], returning null iff the budget
 * elapsed (#56, the tightening deferred from #55).
 *
 * Why not plain `withTimeoutOrNull`: `receive()` is `withContext(Dispatchers.IO) { blocking
 * socket read }`, and coroutine cancellation cannot interrupt a thread parked in a native read —
 * the cooperative timer alone fires only once the per-read socket `soTimeout` (10 min) unblocks
 * the read, making the effective ceiling budget + one `soTimeout`. So, mirroring
 * `NoiseSecureChannelFactory.handshakeWithDeadline`, a watchdog CLOSES [channel] at the
 * deadline: the close unblocks the parked read immediately and the cap fires at ~budget.
 *
 * Deterministic at the deadline, whichever timer wins the race and however the unblocked read
 * surfaces (a null frame, an IO error, or a swallowed close):
 *  - the watchdog flags the timeout BEFORE closing, and any block failure after the flag
 *    reports null — the caller's timeout message stays stable;
 *  - a block failure BEFORE the deadline rethrows untouched (a real transport error must keep
 *    its own message);
 *  - external cancellation (a `reset()`) always propagates as [CancellationException], never
 *    converts to null — the callers' state machines distinguish user-cancel from timeout;
 *  - null ⇒ [channel] is closed (guaranteed here even when the cooperative timer wins).
 */
suspend fun <T : Any> withDataPhaseDeadline(
    channel: SecureChannel,
    timeoutMs: Long,
    block: suspend () -> T,
): T? = coroutineScope {
    val timedOut = AtomicBoolean(false)
    val watchdog = launch {
        delay(timeoutMs)
        timedOut.set(true) // BEFORE close: the unblocked read must observe "deadline", not "error"
        runCatching { channel.close() }
    }
    val result = try {
        // Cooperative belt: bounds suspending implementations (and test fakes) on its own; the
        // watchdog above covers the parked-native-read path this timer cannot reach.
        withTimeoutOrNull(timeoutMs) { block() }
    } catch (c: CancellationException) {
        throw c // an external reset()/scope teardown is never a timeout
    } catch (t: Throwable) {
        if (timedOut.get()) null else throw t
    } finally {
        watchdog.cancel()
    }
    if (result == null) runCatching { channel.close() } // timed out ⇒ closed, on EITHER timer
    result
}
