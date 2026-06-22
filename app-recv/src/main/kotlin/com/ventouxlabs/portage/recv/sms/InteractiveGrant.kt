/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.sms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridges a one-shot interactive result (here, the system ROLE_SMS dialog) into a suspend call.
 * [await] fires the UI via [launch], then suspends until [complete] delivers the outcome or
 * [timeoutMs] elapses — so a never-answered dialog can NEVER hang the caller forever
 * (DEVILS_ADVOCATE.md Q4: a timed-out grant returns false → the transfer proceeds and SMS
 * self-skips, nobody is stranded by a hang).
 *
 * Deliberately free of android.* types so the await/complete/timeout race — the exact regression
 * behind the config-change hang — is unit-testable on the JVM.
 *
 * Contract: single in-flight request, main-thread bound. [await] and [complete] run on the same
 * (main) dispatcher and the coordinator services exactly one grant at a time, so the plain `var`
 * [pending] needs no synchronization.
 */
internal class InteractiveGrant(private val timeoutMs: Long) {

    private var pending: CompletableDeferred<Boolean>? = null

    /** Fire [launch] (show the dialog), then suspend for the result or the timeout. */
    suspend fun await(launch: () -> Unit): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        launch()
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            // Drop the slot only if it's still ours, so a late result can't complete a newer await.
            if (pending === deferred) pending = null
        }
    }

    /** Deliver the interactive outcome to a waiting [await]; a no-op if none is outstanding. */
    fun complete(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }
}
