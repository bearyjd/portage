/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.adbbridge

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** #156 follow-ups, on real threads: libadb's manager lock, the gate's own timeout, cancel cleanup. */
class AbandonedConnectTest {

    private val release = AtomicBoolean(false)

    @After
    fun letStuckWorkersExit() {
        release.set(true)
    }

    /** Models libadb 3.1.1: connect holds the manager lock through an interrupt-deaf wait. */
    private inner class LockingGate : AdbDeviceGate {
        val mLock = Object()
        override suspend fun pair(port: Int, pairingCode: String) = withContext(Dispatchers.IO) {
            synchronized(mLock) { }
        }
        override fun isWirelessDebuggingEnabled() = true
        override suspend fun connect(timeoutMs: Long): Boolean = withContext(Dispatchers.IO + NonCancellable) {
            synchronized(mLock) {
                while (!release.get()) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { }
                }
            }
            false
        }
        override fun isConnected(): Boolean = synchronized(mLock) { false }
        override fun closeQuietly() { synchronized(mLock) { } }
        override suspend fun exec(command: String) = ""
        override suspend fun execWithStdin(command: String, input: java.io.InputStream, size: Long) = ""
    }

    private class HealthyGate : AdbDeviceGate {
        override suspend fun pair(port: Int, pairingCode: String) {}
        override fun isWirelessDebuggingEnabled() = true
        override suspend fun connect(timeoutMs: Long) = false
        override fun isConnected() = false
        override fun closeQuietly() {}
        override suspend fun exec(command: String) = ""
        override suspend fun execWithStdin(command: String, input: java.io.InputStream, size: Long) = ""
    }

    private fun bridge(gate: AdbDeviceGate, factory: (() -> AdbDeviceGate)?) = LocalAdbBridge(
        selfPackage = "p",
        gate = gate,
        io = Dispatchers.IO,
        pairTimeoutMs = 300,
        connectTimeoutMs = 300,
        shellTimeoutMs = 300,
        gateFactory = factory,
    )

    private fun <T> boundedRun(block: suspend () -> T): T? {
        var out: T? = null
        val done = CountDownLatch(1)
        Thread { out = runBlocking { block() }; done.countDown() }.apply { isDaemon = true; start() }
        return if (done.await(5, TimeUnit.SECONDS)) out else null
    }

    @Test
    fun `connect Timeout then connect again then pair does not hang on the abandoned lock`() {
        val bridge = bridge(LockingGate()) { HealthyGate() }
        assertThat(boundedRun { bridge.connect() }).isEqualTo(AdbBridge.ConnectionResult.Timeout)

        // Healthy replacement gate: connect completes (not connected => Rejected), never blocks.
        assertThat(boundedRun { bridge.connect() }).isNotNull()
        assertThat(boundedRun { bridge.pair(1234, "000000") })
            .isEqualTo(AdbBridge.PairingResult.Paired)
        assertThat(boundedRun { bridge.disconnect() }).isNotNull()
    }

    @Test
    fun `cancelling the caller during a stuck connect abandons the gate so the next connect does not hang`() {
        val bridge = bridge(LockingGate()) { HealthyGate() }
        val started = CountDownLatch(1)
        val caller = CoroutineScope(Dispatchers.IO).launch {
            started.countDown()
            bridge.connect()
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        Thread.sleep(100) // let connect() reach the stuck gate.connect
        caller.cancel()

        // Healthy replacement gate: completes (not connected => Rejected), never blocks.
        assertThat(boundedRun { bridge.connect() }).isNotNull()
    }

    @Test
    fun `without a replacement gate the stuck lock does hang (guard against a vacuous test)`() {
        val bridge = bridge(LockingGate(), null)
        assertThat(boundedRun { bridge.connect() }).isEqualTo(AdbBridge.ConnectionResult.Timeout)
        assertThat(boundedRun { bridge.pair(1234, "000000") }).isNull()
    }

    @Test
    fun `repeated stuck connects do not accumulate workers past the cap`() {
        var created = 0
        val bridge = bridge(LockingGate()) { created++; LockingGate() }
        repeat(6) { assertThat(boundedRun { bridge.connect() }).isEqualTo(AdbBridge.ConnectionResult.Timeout) }
        assertThat(created).isAtMost(LocalAdbBridge.MAX_ABANDONED_CONNECTS)
    }

    @Test
    fun `gate discovery timeout through the real runInterruptible maps to NoEndpoint`() {
        val gate = object : AdbDeviceGate by HealthyGate() {
            override suspend fun connect(timeoutMs: Long): Boolean =
                connectMappingDiscoveryTimeout(Dispatchers.IO) {
                    throw InterruptedException("Timed out while trying to find a valid host address and port")
                }
        }
        assertThat(boundedRun { bridge(gate, null).connect() })
            .isEqualTo(AdbBridge.ConnectionResult.NoEndpoint)
    }

    @Test
    fun `a genuine cancellation still propagates as cancellation not as NoEndpoint`() = runBlocking {
        val started = CountDownLatch(1)
        var thrown: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                connectMappingDiscoveryTimeout(Dispatchers.IO) {
                    started.countDown()
                    Thread.sleep(10_000)
                    false
                }
            } catch (t: Throwable) {
                thrown = t
            }
        }
        started.await(5, TimeUnit.SECONDS)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `an abandoned connect attempt is cancelled when connect gives up`() = runBlocking {
        val cancelled = CountDownLatch(1)
        val gate = object : AdbDeviceGate by HealthyGate() {
            override suspend fun connect(timeoutMs: Long): Boolean {
                try {
                    delay(60_000)
                } finally {
                    cancelled.countDown()
                }
                return false
            }
        }
        assertThat(bridge(gate, null).connect()).isEqualTo(AdbBridge.ConnectionResult.Timeout)
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue()
    }
}
