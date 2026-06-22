/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.install

import com.ventouxlabs.portage.adbbridge.AdbBridge
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [AdbRuntimePermissionGranter] over a fake [AdbBridge] — the FIRST production
 * `AdbBridge.grantRuntimePermission` call site (ADR-006 D5). Pins: each requested permission is granted
 * and the OpResult.Ok subset is reported; a per-permission failure (or an unexpected throw) is isolated
 * and never aborts the rest; the AC-11 disconnect-in-`finally` invariant holds on every path (success,
 * partial, connect failure, exception, timeout); an empty request does no bridge work; and the outer
 * attempt-timeout ceiling degrades a blocked connect to "granted nothing" rather than hanging the apply.
 */
class AdbRuntimePermissionGranterTest {

    /** A fake bridge scripting connect + per-permission grant results, recording every call. */
    private class FakeBridge(
        private var connected: Boolean = false,
        private val connectResult: AdbBridge.ConnectionResult = AdbBridge.ConnectionResult.Connected,
        private val connectBlocksForever: Boolean = false,
        /** When true, each grant call parks forever (simulating a cancellable in-flight `pm grant`). */
        private val grantSuspendsForever: Boolean = false,
        /** permission → scripted OpResult; absent ⇒ OpResult.Ok. */
        private val results: Map<String, AdbBridge.OpResult> = emptyMap(),
        /** permissions whose grant call throws (simulating an unexpected mid-loop failure). */
        private val throwOn: Set<String> = emptySet(),
    ) : AdbBridge {
        var connectCalls = 0
        var disconnectCalls = 0
        val grantedSeen = mutableListOf<Pair<String, String>>()

        override suspend fun pair(pairingPort: Int, pairingCode: String) = error("not used")

        override suspend fun connect(): AdbBridge.ConnectionResult {
            connectCalls++
            if (connectBlocksForever) delay(Long.MAX_VALUE) // never returns; the outer timeout must win
            if (connectResult is AdbBridge.ConnectionResult.Connected) connected = true
            return connectResult
        }

        override fun isConnected(): Boolean = connected

        override fun disconnect() {
            disconnectCalls++
            connected = false
        }

        override suspend fun shell(command: String): AdbBridge.ShellResult = error("not used")

        override suspend fun selfGrant(permission: String): AdbBridge.GrantResult = error("not used")

        override suspend fun grantRuntimePermission(
            packageName: String,
            permission: String,
        ): AdbBridge.OpResult {
            grantedSeen += packageName to permission
            if (grantSuspendsForever) delay(Long.MAX_VALUE) // parks; only an (outer) cancellation unwinds it
            if (permission in throwOn) throw java.io.IOException("socket died")
            return results[permission] ?: AdbBridge.OpResult.Ok
        }

        override suspend fun installApk(staged: List<AdbBridge.StagedApk>): AdbBridge.InstallResult =
            error("not used")

        override suspend fun probeCapabilities(): Set<AdbBridge.PrivilegedCapability> = emptySet()
    }

    private fun granter(bridge: FakeBridge, attemptTimeoutMs: Long = 90_000L) =
        AdbRuntimePermissionGranter(bridge = bridge, attemptTimeoutMs = attemptTimeoutMs)

    private val internet = "android.permission.INTERNET"
    private val otherSensors = "android.permission.OTHER_SENSORS"

    @Test
    fun `grants each requested permission and reports the granted set, then disconnects`() = runTest {
        val bridge = FakeBridge(connected = true)
        val granted = granter(bridge).grant("com.example.app", listOf(internet, otherSensors))
        assertThat(granted).containsExactly(internet, otherSensors)
        assertThat(bridge.grantedSeen)
            .containsExactly("com.example.app" to internet, "com.example.app" to otherSensors)
            .inOrder()
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `a per-permission failure is omitted from the granted set but the rest still run`() = runTest {
        val bridge = FakeBridge(
            connected = true,
            results = mapOf(otherSensors to AdbBridge.OpResult.Failed("exit 1")),
        )
        val granted = granter(bridge).grant("com.example.app", listOf(internet, otherSensors))
        assertThat(granted).containsExactly(internet) // OTHER_SENSORS failed → omitted
        assertThat(bridge.grantedSeen.map { it.second }).containsExactly(internet, otherSensors) // both attempted
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `a bridge-unavailable result is omitted from the granted set`() = runTest {
        val bridge = FakeBridge(
            connected = true,
            results = mapOf(internet to AdbBridge.OpResult.BridgeUnavailable),
        )
        val granted = granter(bridge).grant("com.example.app", listOf(internet))
        assertThat(granted).isEmpty()
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `connects when not connected then grants and disconnects`() = runTest {
        val bridge = FakeBridge(connected = false) // starts disconnected → must connect first
        val granted = granter(bridge).grant("com.example.app", listOf(internet))
        assertThat(granted).containsExactly(internet)
        assertThat(bridge.connectCalls).isEqualTo(1)
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `a connect failure grants nothing, attempts no grant, and still disconnects`() = runTest {
        val bridge = FakeBridge(
            connected = false,
            connectResult = AdbBridge.ConnectionResult.NoEndpoint,
        )
        val granted = granter(bridge).grant("com.example.app", listOf(internet))
        assertThat(granted).isEmpty()
        assertThat(bridge.connectCalls).isEqualTo(1)
        assertThat(bridge.grantedSeen).isEmpty() // never attempted a grant
        assertThat(bridge.disconnectCalls).isEqualTo(1) // AC-11 still holds
    }

    @Test
    fun `an empty permission list does no bridge work`() = runTest {
        val bridge = FakeBridge(connected = false)
        val granted = granter(bridge).grant("com.example.app", emptyList())
        assertThat(granted).isEmpty()
        assertThat(bridge.connectCalls).isEqualTo(0)
        assertThat(bridge.disconnectCalls).isEqualTo(0) // short-circuits before any session
    }

    @Test
    fun `a grant that throws is isolated, the rest still run, and disconnect runs in finally`() = runTest {
        val bridge = FakeBridge(connected = true, throwOn = setOf(internet))
        val granted = granter(bridge).grant("com.example.app", listOf(internet, otherSensors))
        assertThat(granted).containsExactly(otherSensors) // INTERNET threw → omitted, OTHER_SENSORS ok
        assertThat(bridge.grantedSeen.map { it.second }).containsExactly(internet, otherSensors) // both attempted
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `a connect that blocks forever cannot hang - the timeout degrades to granting nothing`() = runTest {
        // Belt-and-suspenders: even if connect() parked on an uninterruptible libadb section, the outer
        // withTimeoutOrNull must fire and degrade to "granted nothing". (runTest virtual time advances
        // past attemptTimeoutMs with no wall-clock wait.)
        val bridge = FakeBridge(connected = false, connectBlocksForever = true)
        val granted = granter(bridge, attemptTimeoutMs = 5_000L).grant("com.example.app", listOf(internet))
        assertThat(granted).isEmpty()
        assertThat(bridge.connectCalls).isEqualTo(1) // attempted…
        assertThat(bridge.grantedSeen).isEmpty() // …but never got past the blocked connect
        assertThat(bridge.disconnectCalls).isEqualTo(1) // AC-11: teardown ran even on the timeout path
    }

    @Test
    fun `outer cancellation unwinds the grant and still disconnects (AC-11)`() = runTest {
        // The per-permission catch rethrows CancellationException, so cancelling the apply job while a
        // `pm grant` is in flight must propagate the cancellation AND still run disconnect in `finally` —
        // shell uid is never stranded open. (Distinct from the timeout path, which returns null.)
        val bridge = FakeBridge(connected = true, grantSuspendsForever = true)
        // UNDISPATCHED runs the grant synchronously up to the parked pm grant call — no virtual time is
        // advanced, so the 90s timeout never fires and we isolate the cancellation path.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            granter(bridge).grant("com.example.app", listOf(internet))
        }
        assertThat(bridge.grantedSeen).isNotEmpty() // it entered the grant loop and parked
        job.cancelAndJoin()
        assertThat(bridge.disconnectCalls).isEqualTo(1) // AC-11: teardown ran on cancellation
    }
}
