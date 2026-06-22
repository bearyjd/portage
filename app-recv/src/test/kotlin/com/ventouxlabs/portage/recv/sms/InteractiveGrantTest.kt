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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The bridge behind the config-change-hang fix: a role-dialog await must (a) resolve to the
 * delivered result and (b) give up after the timeout rather than block forever. These pin the
 * exact regression — the prior per-Activity coordinator awaited a deferred nothing completed.
 */
class InteractiveGrantTest {

    @Test
    fun `await returns the result delivered via complete`() = runTest {
        val grant = InteractiveGrant(timeoutMs = 60_000)
        var launched = false

        val outcome = async { grant.await { launched = true } }
        runCurrent() // run await up to its suspension so the pending slot is registered
        assertThat(launched).isTrue()

        grant.complete(true)
        assertThat(outcome.await()).isTrue()
    }

    @Test
    fun `await returns false when no result arrives before the timeout`() = runTest {
        val grant = InteractiveGrant(timeoutMs = 10_000)
        var launched = false

        // runTest auto-advances idle virtual time, so the timeout elapses with no complete().
        val outcome = grant.await { launched = true }

        assertThat(launched).isTrue()
        assertThat(outcome).isFalse()
    }

    @Test
    fun `a declined grant resolves to false`() = runTest {
        val grant = InteractiveGrant(timeoutMs = 60_000)

        val outcome = async { grant.await { } }
        runCurrent()
        grant.complete(false)

        assertThat(outcome.await()).isFalse()
    }

    @Test
    fun `a late complete after the timeout cannot resolve a fresh await`() = runTest {
        val grant = InteractiveGrant(timeoutMs = 10_000)

        // First await times out (no result), clearing its slot.
        assertThat(grant.await { }).isFalse()
        // A stale result arriving afterward must not complete a subsequent await.
        grant.complete(true)

        val second = async { grant.await { } }
        runCurrent()
        grant.complete(false) // the real result for the second await
        assertThat(second.await()).isFalse()
    }
}
