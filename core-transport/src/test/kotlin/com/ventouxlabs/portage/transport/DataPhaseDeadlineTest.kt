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

import com.ventouxlabs.portage.model.ProtocolMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The #56 deadline watchdog. The headline test is the PARKED-READ FIDELITY case #55 deferred:
 * a `receive()` that parks a REAL thread which only `close()` releases — cooperative
 * cancellation provably cannot end it, so the test completes only if the watchdog actually
 * closes the channel at the deadline (the `soTimeout` escape hatch the bare cap leaned on
 * does not exist in this fake at all; a regressed watchdog turns this test into a hang,
 * failed by the JUnit timeout).
 */
class DataPhaseDeadlineTest {

    /**
     * Blocking-read fake with NO cancellation escape: `receive()` parks its thread on a latch
     * that ONLY `close()` releases, then surfaces the close as an IO error — the same shape as
     * a native socket read unblocked by `Socket.close()`.
     */
    private class ParkedReadChannel : SecureChannel {
        private val parked = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        override suspend fun send(message: ProtocolMessage) = Unit
        override suspend fun receive(): ProtocolMessage? = withContext(Dispatchers.IO) {
            parked.await() // parks a REAL IO thread; coroutine cancellation cannot interrupt it
            throw IOException("read failed: socket closed")
        }
        override fun close() {
            closed.set(true)
            parked.countDown()
        }
    }

    /** Cooperative fake: `receive()` suspends until cancelled; `close()` only records. */
    private class SuspendingChannel : SecureChannel {
        val closed = AtomicBoolean(false)
        override suspend fun send(message: ProtocolMessage) = Unit
        override suspend fun receive(): ProtocolMessage? = awaitCancellation()
        override fun close() {
            closed.set(true)
        }
    }

    @Test(timeout = 10_000L)
    fun `deadline closes the channel and unblocks a parked read at ~budget`() = runBlocking {
        val channel = ParkedReadChannel()
        val startedNanos = System.nanoTime()

        val result = withDataPhaseDeadline(channel, timeoutMs = 200) {
            channel.receive()
            "unreachable"
        }

        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
        assertThat(result).isNull() // deterministically the deadline, not the surfaced IO error
        assertThat(channel.closed.get()).isTrue()
        // Fires at ~budget: this fake HAS no soTimeout — close is the only release — and the
        // margin stays far under the old +10 min slack. Generous bound for slow CI runners.
        assertThat(elapsedMs).isLessThan(5_000)
    }

    @Test(timeout = 10_000L)
    fun `a block that finishes in time passes its result through untouched`() = runBlocking {
        val channel = SuspendingChannel()

        val result = withDataPhaseDeadline(channel, timeoutMs = 60_000) { "results" }

        assertThat(result).isEqualTo("results")
        assertThat(channel.closed.get()).isFalse() // no timeout ⇒ the CALLER owns the close
    }

    @Test(timeout = 10_000L)
    fun `a transport error before the deadline rethrows untouched`() = runBlocking {
        val channel = SuspendingChannel()

        val thrown = runCatching {
            withDataPhaseDeadline(channel, timeoutMs = 60_000) {
                throw TransportException("frame body read failed")
            }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(TransportException::class.java)
        assertThat(thrown).hasMessageThat().isEqualTo("frame body read failed")
        assertThat(channel.closed.get()).isFalse() // error teardown stays the caller's job
    }

    @Test(timeout = 10_000L)
    fun `a cooperative stall times out and the channel is closed either way`() = runBlocking {
        val channel = SuspendingChannel()

        val result = withDataPhaseDeadline(channel, timeoutMs = 100) {
            channel.receive()
            "unreachable"
        }

        assertThat(result).isNull()
        // Even when the cooperative timer wins the deadline race, "timed out ⇒ closed" holds.
        assertThat(channel.closed.get()).isTrue()
    }

    @Test(timeout = 10_000L)
    fun `external cancellation propagates and is never converted to a timeout`() = runBlocking {
        val channel = SuspendingChannel()
        var completedNormally = false
        val job = launch {
            withDataPhaseDeadline(channel, timeoutMs = 60_000) {
                channel.receive()
                "unreachable"
            }
            completedNormally = true
        }
        delay(100)

        job.cancel()
        job.join()

        assertThat(completedNormally).isFalse()
        assertThat(channel.closed.get()).isFalse() // reset()-style teardown belongs to the caller
    }
}
