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

/**
 * Keeps the transfer's process at foreground importance + the CPU awake for the duration of the
 * authenticated data phase, so a screen-off (GrapheneOS Doze / Wi-Fi power-save) can't tear down
 * the streaming TCP socket mid-frame (#85). [start] raises priority and holds a partial wakelock;
 * [stop] releases both.
 *
 * The ViewModel drives this around the data phase — [start] before listening/streaming, [stop] in
 * a `finally` — so it can never outlive a transfer. Both calls MUST be idempotent and MUST NOT
 * throw: a [stop] with no prior [start] (an early prepare failure) is a no-op, and a failed
 * [start] degrades to "no keep-alive" rather than failing the transfer (the SAFE direction —
 * exactly today's behaviour). The real Android implementation is [ForegroundServiceKeepAlive];
 * [NoOp] is the test/preview default.
 */
interface TransferKeepAlive {
    fun start()
    fun stop()

    /** No keep-alive — the default for unit tests and previews (no Android service). */
    object NoOp : TransferKeepAlive {
        override fun start() = Unit
        override fun stop() = Unit
    }
}
