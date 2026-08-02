/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.roles

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.adbbridge.AdbBridge
import com.ventouxlabs.portage.providers.roles.RestorableRole
import com.ventouxlabs.portage.providers.roles.RoleRestorer
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [AdbRoleRestorer] over a fake [AdbBridge] (#122).
 *
 * The load-bearing property here is that **a zero exit code is not treated as proof the role
 * moved**. `cmd role add-role-holder` was only ever exercised on the SUCCESS path during the A17
 * spike — role-qualification failure is recorded there as untested — so if the platform exits 0
 * while silently refusing a non-qualifying package, a restorer that trusts the exit code reports a
 * default it never set AND drops the row, leaving the user unable to retry. That is the specific
 * dishonesty this feature exists to prevent, so the write is verified by reading the role back.
 */
class AdbRoleRestorerTest {

    /**
     * A fake bridge scripting the write result and what the subsequent readback reports, so a test
     * can drive the exact disagreement between the two that hardware might produce.
     */
    private class FakeBridge(
        private var connected: Boolean = true,
        private val connectResult: AdbBridge.ConnectionResult = AdbBridge.ConnectionResult.Connected,
        private val writeResult: AdbBridge.OpResult = AdbBridge.OpResult.Ok,
        /** null ⇒ the readback itself fails (bridge died / command error). */
        private val holders: Set<String>? = emptySet(),
        private val writeBlocksForever: Boolean = false,
        private val writeThrows: Boolean = false,
    ) : AdbBridge {
        var disconnectCalls = 0
        var readbackCalls = 0
        val writesSeen = mutableListOf<Pair<AdbBridge.RoleTarget, String>>()

        override suspend fun pair(pairingPort: Int, pairingCode: String) = error("not used")

        // Raw shell is NOT how this class reaches the bridge — it goes through the typed
        // setRoleHolder / roleHolders ops, and erroring here proves it: a future refactor that
        // dropped to shell() would fail these tests rather than silently widening the surface.
        override suspend fun shell(command: String) = error("raw shell is not this class's path")

        override suspend fun selfGrant(permission: String) = error("not used")

        override suspend fun installApk(staged: List<AdbBridge.StagedApk>) = error("not used")

        override suspend fun probeCapabilities() = error("not used")

        override suspend fun connect(): AdbBridge.ConnectionResult {
            if (connectResult is AdbBridge.ConnectionResult.Connected) connected = true
            return connectResult
        }

        override fun isConnected(): Boolean = connected

        override fun disconnect() {
            disconnectCalls++
            connected = false
        }

        override suspend fun setRoleHolder(
            role: AdbBridge.RoleTarget,
            packageName: String,
        ): AdbBridge.OpResult {
            if (writeBlocksForever) delay(Long.MAX_VALUE)
            if (writeThrows) throw java.io.IOException("bridge exploded")
            writesSeen += role to packageName
            return writeResult
        }

        override suspend fun roleHolders(role: AdbBridge.RoleTarget): Set<String>? {
            readbackCalls++
            return holders
        }
    }

    @Test
    fun `exit 0 plus a readback confirming the package is RESTORED`() = runTest {
        val bridge = FakeBridge(holders = setOf("com.example.browser"))
        val outcome = AdbRoleRestorer(bridge).restore(RestorableRole.BROWSER, "com.example.browser")

        assertThat(outcome).isEqualTo(RoleRestorer.Outcome.RESTORED)
        assertThat(bridge.writesSeen)
            .containsExactly(AdbBridge.RoleTarget.BROWSER to "com.example.browser")
    }

    @Test
    fun `exit 0 but the role did NOT move is REJECTED, never RESTORED`() = runTest {
        // THE regression pin for the false-"DEFAULT" path. The write reports success and the role
        // is unchanged — exactly what a platform that accepts the call but declines a non-qualifying
        // package would produce. Trusting the exit code here shows the user "Browser — DEFAULT" for
        // a default that was never set, and removes the row so they cannot retry.
        val bridge = FakeBridge(
            writeResult = AdbBridge.OpResult.Ok,
            holders = setOf("com.android.chrome"), // someone else still holds it
        )
        val outcome = AdbRoleRestorer(bridge).restore(RestorableRole.BROWSER, "com.example.browser")

        assertThat(outcome).isEqualTo(RoleRestorer.Outcome.REJECTED)
        assertThat(bridge.readbackCalls).isEqualTo(1)
    }

    @Test
    fun `an empty readback after a successful write is REJECTED`() = runTest {
        // Nothing holds the role: the write plainly did not land.
        val bridge = FakeBridge(holders = emptySet())
        assertThat(AdbRoleRestorer(bridge).restore(RestorableRole.HOME, "com.example.home"))
            .isEqualTo(RoleRestorer.Outcome.REJECTED)
    }

    @Test
    fun `an UNVERIFIABLE readback is UNAVAILABLE, not success — the check fails CLOSED`() = runTest {
        // "I could not check" must never collapse into "it worked", or the verification is
        // decorative. UNAVAILABLE leaves the row offered; a retry is harmless because
        // add-role-holder is idempotent.
        val bridge = FakeBridge(holders = null)
        assertThat(AdbRoleRestorer(bridge).restore(RestorableRole.DIALER, "com.example.dialer"))
            .isEqualTo(RoleRestorer.Outcome.UNAVAILABLE)
    }

    @Test
    fun `the readback runs exactly once per restore`() = runTest {
        // Guards against a branch-per-call implementation: two reads double the round-trip and can
        // disagree with each other if the role changes between them.
        val bridge = FakeBridge(holders = setOf("com.example.browser"))
        AdbRoleRestorer(bridge).restore(RestorableRole.BROWSER, "com.example.browser")
        assertThat(bridge.readbackCalls).isEqualTo(1)
    }

    @Test
    fun `a failed write is REJECTED without any readback`() = runTest {
        val bridge = FakeBridge(writeResult = AdbBridge.OpResult.Failed("exit 1: nope"))
        assertThat(AdbRoleRestorer(bridge).restore(RestorableRole.BROWSER, "com.example.browser"))
            .isEqualTo(RoleRestorer.Outcome.REJECTED)
        assertThat(bridge.readbackCalls).isEqualTo(0)
    }

    @Test
    fun `an unreachable bridge is UNAVAILABLE and never writes`() = runTest {
        val bridge = FakeBridge(
            connected = false,
            connectResult = AdbBridge.ConnectionResult.NoEndpoint,
        )
        assertThat(AdbRoleRestorer(bridge).restore(RestorableRole.BROWSER, "com.example.browser"))
            .isEqualTo(RoleRestorer.Outcome.UNAVAILABLE)
        assertThat(bridge.writesSeen).isEmpty()
    }

    @Test
    fun `a throwing bridge degrades to UNAVAILABLE instead of propagating`() = runTest {
        val bridge = FakeBridge(writeThrows = true)
        assertThat(AdbRoleRestorer(bridge).restore(RestorableRole.BROWSER, "com.example.browser"))
            .isEqualTo(RoleRestorer.Outcome.UNAVAILABLE)
    }

    @Test
    fun `the bridge is ALWAYS disconnected — success, refusal, throw, and timeout alike`() = runTest {
        // ADR-003: never hold shell uid open.
        val confirmed = FakeBridge(holders = setOf("com.example.browser"))
        AdbRoleRestorer(confirmed).restore(RestorableRole.BROWSER, "com.example.browser")
        assertThat(confirmed.disconnectCalls).isEqualTo(1)

        val refused = FakeBridge(holders = emptySet())
        AdbRoleRestorer(refused).restore(RestorableRole.BROWSER, "com.example.browser")
        assertThat(refused.disconnectCalls).isEqualTo(1)

        val threw = FakeBridge(writeThrows = true)
        AdbRoleRestorer(threw).restore(RestorableRole.BROWSER, "com.example.browser")
        assertThat(threw.disconnectCalls).isEqualTo(1)

        val wedged = FakeBridge(writeBlocksForever = true)
        val outcome = AdbRoleRestorer(wedged, attemptTimeoutMs = 50).restore(
            RestorableRole.BROWSER,
            "com.example.browser",
        )
        assertThat(outcome).isEqualTo(RoleRestorer.Outcome.UNAVAILABLE)
        assertThat(wedged.disconnectCalls).isEqualTo(1)
    }
}
