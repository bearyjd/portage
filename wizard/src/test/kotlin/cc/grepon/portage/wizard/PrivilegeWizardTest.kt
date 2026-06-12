/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.wizard

import cc.grepon.portage.adbbridge.AdbBridge
import cc.grepon.portage.adbbridge.PairingPortDetector
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PrivilegeWizardTest {

    private class FakeBridge : AdbBridge {
        var connected = false
        var disconnectCalls = 0
        var pairResult: AdbBridge.PairingResult = AdbBridge.PairingResult.Paired
        var connectResult: () -> AdbBridge.ConnectionResult =
            { AdbBridge.ConnectionResult.NoEndpoint }
        var capabilities: Set<AdbBridge.PrivilegedCapability> = emptySet()
        var probeThrows = false
        val pairCalls = mutableListOf<Pair<Int, String>>()

        override suspend fun pair(pairingPort: Int, pairingCode: String): AdbBridge.PairingResult {
            pairCalls += pairingPort to pairingCode
            return pairResult
        }

        override suspend fun connect(): AdbBridge.ConnectionResult {
            val result = connectResult()
            if (result is AdbBridge.ConnectionResult.Connected) connected = true
            return result
        }

        override fun isConnected(): Boolean = connected

        override fun disconnect() {
            disconnectCalls++
            connected = false
        }

        override suspend fun shell(command: String): AdbBridge.ShellResult =
            AdbBridge.ShellResult.NotConnected

        override suspend fun selfGrant(permission: String): AdbBridge.GrantResult =
            AdbBridge.GrantResult.BRIDGE_UNAVAILABLE

        override suspend fun installApk(stagedApkPath: String): AdbBridge.InstallResult =
            AdbBridge.InstallResult.BridgeUnavailable

        override suspend fun probeCapabilities(): Set<AdbBridge.PrivilegedCapability> {
            check(isConnected()) { "probe must only run on a live bridge" }
            if (probeThrows) error("probe blew up")
            return capabilities
        }
    }

    private class FakeEnvironment(
        var devOptions: Boolean = true,
        var wirelessDebug: Boolean = true,
    ) : WizardEnvironment {
        override fun developerOptionsEnabled() = devOptions
        override fun wirelessDebuggingEnabled() = wirelessDebug
    }

    private fun TestScope.wizard(
        bridge: FakeBridge,
        environment: FakeEnvironment = FakeEnvironment(),
        detector: PairingPortDetector = PairingPortDetector { 37123 },
    ) = PrivilegeWizard(bridge, environment, detector, scope = this, detectTimeoutMs = 5_000)

    // ── step 1: the skip-everything checks ───────────────────────────────────────────────────

    @Test
    fun `an already-connected bridge goes straight to Ready and disconnects after the probe`() =
        runTest {
            val bridge = FakeBridge().apply {
                connected = true
                capabilities = setOf(
                    AdbBridge.PrivilegedCapability.SHELL,
                    AdbBridge.PrivilegedCapability.SETTINGS_SECURE,
                )
            }
            val w = wizard(bridge)
            w.start()
            advanceUntilIdle()

            assertThat(w.step.value).isEqualTo(PrivilegeWizard.Step.Ready(bridge.capabilities))
            assertThat(bridge.disconnectCalls).isEqualTo(1)
            assertThat(bridge.connected).isFalse()
        }

    @Test
    fun `a silent reconnect with the persisted pairing key skips the pairing steps`() = runTest {
        // Post-reboot path (devils-advocate Q1): toggle re-enabled, pairing key persisted.
        val bridge = FakeBridge().apply {
            connectResult = { AdbBridge.ConnectionResult.Connected }
            capabilities = setOf(AdbBridge.PrivilegedCapability.SHELL)
        }
        val w = wizard(bridge)
        w.start()
        advanceUntilIdle()

        assertThat(w.step.value).isInstanceOf(PrivilegeWizard.Step.Ready::class.java)
        assertThat(bridge.pairCalls).isEmpty()
    }

    // ── steps 2-3: environment gating + recheck on return from Settings ─────────────────────

    @Test
    fun `progresses dev-options then wireless-debug as the user enables them`() = runTest {
        val bridge = FakeBridge()
        val environment = FakeEnvironment(devOptions = false, wirelessDebug = false)
        val w = wizard(bridge, environment)

        w.start()
        advanceUntilIdle()
        assertThat(w.step.value).isEqualTo(PrivilegeWizard.Step.EnableDevOptions)

        environment.devOptions = true
        w.recheck()
        advanceUntilIdle()
        assertThat(w.step.value).isEqualTo(PrivilegeWizard.Step.EnableWirelessDebug)

        environment.wirelessDebug = true
        w.recheck()
        advanceUntilIdle()
        assertThat(w.step.value).isInstanceOf(PrivilegeWizard.Step.EnterPairingCode::class.java)
    }

    // ── step 4: pairing-port detection ───────────────────────────────────────────────────────

    @Test
    fun `mDNS detection fills the pairing port`() = runTest {
        val w = wizard(FakeBridge(), detector = PairingPortDetector { 40555 })
        w.start()
        advanceUntilIdle()

        val step = w.step.value as PrivilegeWizard.Step.EnterPairingCode
        assertThat(step.detectedPort).isEqualTo(40555)
        assertThat(step.detecting).isFalse()
    }

    @Test
    fun `detection failure falls back to manual entry`() = runTest {
        val w = wizard(FakeBridge(), detector = PairingPortDetector { null })
        w.start()
        advanceUntilIdle()

        val step = w.step.value as PrivilegeWizard.Step.EnterPairingCode
        assertThat(step.detectedPort).isNull()
        assertThat(step.detecting).isFalse()
    }

    @Test
    fun `detection is in progress while mDNS looks for the service`() = runTest {
        val slowDetector = PairingPortDetector { delay(2_000); 41000 }
        val w = wizard(FakeBridge(), detector = slowDetector)
        w.start()
        advanceTimeBy(1_000)

        assertThat((w.step.value as PrivilegeWizard.Step.EnterPairingCode).detecting).isTrue()
        advanceUntilIdle()
        assertThat((w.step.value as PrivilegeWizard.Step.EnterPairingCode).detectedPort)
            .isEqualTo(41000)
    }

    // ── step 4: code submission ──────────────────────────────────────────────────────────────

    @Test
    fun `a malformed code or missing port is rejected before touching the bridge`() = runTest {
        val bridge = FakeBridge()
        val w = wizard(bridge, detector = PairingPortDetector { null })
        w.start()
        advanceUntilIdle()

        w.submitPairingCode("12345") // five digits, and no port detected
        advanceUntilIdle()

        val step = w.step.value as PrivilegeWizard.Step.EnterPairingCode
        assertThat(step.error).isEqualTo(PrivilegeWizard.PairingError.BAD_INPUT)
        assertThat(bridge.pairCalls).isEmpty()
    }

    @Test
    fun `a wrong code surfaces WRONG_CODE and stays on the entry step`() = runTest {
        val bridge = FakeBridge().apply { pairResult = AdbBridge.PairingResult.WrongCode }
        val w = wizard(bridge)
        w.start()
        advanceUntilIdle()

        w.submitPairingCode("123456")
        advanceUntilIdle()

        val step = w.step.value as PrivilegeWizard.Step.EnterPairingCode
        assertThat(step.error).isEqualTo(PrivilegeWizard.PairingError.WRONG_CODE)
        assertThat(step.detectedPort).isEqualTo(37123) // the port survives a retry
    }

    @Test
    fun `a stale pairing endpoint restarts port discovery`() = runTest {
        val bridge = FakeBridge().apply {
            pairResult = AdbBridge.PairingResult.Unavailable("dialog closed")
        }
        val ports = ArrayDeque(listOf(40001, 40002))
        val w = wizard(bridge, detector = PairingPortDetector { ports.removeFirst() })
        w.start()
        advanceUntilIdle()

        w.submitPairingCode("123456")
        advanceUntilIdle()

        val step = w.step.value as PrivilegeWizard.Step.EnterPairingCode
        assertThat(step.error).isEqualTo(PrivilegeWizard.PairingError.ENDPOINT_DOWN)
        assertThat(step.detectedPort).isEqualTo(40002) // a fresh detection ran
    }

    @Test
    fun `pairing success with a failed connect surfaces CONNECT_FAILED`() = runTest {
        val bridge = FakeBridge().apply {
            pairResult = AdbBridge.PairingResult.Paired
            connectResult = { AdbBridge.ConnectionResult.Rejected("nope") }
        }
        val w = wizard(bridge)
        w.start()
        advanceUntilIdle()

        w.submitPairingCode("123456")
        advanceUntilIdle()

        val step = w.step.value as PrivilegeWizard.Step.EnterPairingCode
        assertThat(step.error).isEqualTo(PrivilegeWizard.PairingError.CONNECT_FAILED)
    }

    // ── step 5 + completion ──────────────────────────────────────────────────────────────────

    @Test
    fun `the happy path pairs with the entered code then probes then disconnects`() = runTest {
        val bridge = FakeBridge().apply {
            pairResult = AdbBridge.PairingResult.Paired
            capabilities = AdbBridge.PrivilegedCapability.entries.toSet()
        }
        // First connect attempt (pre-pair) finds nothing; post-pair connect succeeds.
        var paired = false
        bridge.connectResult = {
            if (paired) AdbBridge.ConnectionResult.Connected else AdbBridge.ConnectionResult.NoEndpoint
        }
        val w = wizard(bridge)
        w.start()
        advanceUntilIdle()
        paired = true

        w.submitPairingCode(" 847291 ") // whitespace tolerated
        advanceUntilIdle()

        assertThat(bridge.pairCalls).containsExactly(37123 to "847291")
        assertThat(w.step.value)
            .isEqualTo(PrivilegeWizard.Step.Ready(AdbBridge.PrivilegedCapability.entries.toSet()))
        assertThat(bridge.disconnectCalls).isEqualTo(1)
        assertThat(bridge.connected).isFalse()
    }

    @Test
    fun `a broken probe still disconnects and lands on Ready with no capabilities`() = runTest {
        val bridge = FakeBridge().apply {
            connected = true
            probeThrows = true
        }
        val w = wizard(bridge)
        w.start()
        advanceUntilIdle()

        assertThat(w.step.value).isEqualTo(PrivilegeWizard.Step.Ready(emptySet()))
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    // ── skip + dismiss + re-run ──────────────────────────────────────────────────────────────

    @Test
    fun `skip is available at every pre-completion step`() = runTest {
        val environment = FakeEnvironment(devOptions = false)
        val w = wizard(FakeBridge(), environment)
        w.start()
        advanceUntilIdle()
        assertThat(w.step.value).isEqualTo(PrivilegeWizard.Step.EnableDevOptions)

        w.skip()
        assertThat(w.step.value).isEqualTo(PrivilegeWizard.Step.Skipped)
    }

    @Test
    fun `the wizard can re-run from Ready or Skipped`() = runTest {
        val bridge = FakeBridge()
        val w = wizard(bridge)
        w.skip() // from Idle: records Skipped
        assertThat(w.step.value).isEqualTo(PrivilegeWizard.Step.Skipped)

        bridge.connectResult = { AdbBridge.ConnectionResult.Connected }
        w.start()
        advanceUntilIdle()
        assertThat(w.step.value).isInstanceOf(PrivilegeWizard.Step.Ready::class.java)

        w.start() // re-run from Ready (settings-screen entry point)
        advanceUntilIdle()
        assertThat(w.step.value).isInstanceOf(PrivilegeWizard.Step.Ready::class.java)
    }

    @Test
    fun `dismiss returns to Idle without recording a decision`() = runTest {
        val w = wizard(FakeBridge(), FakeEnvironment(devOptions = false))
        w.start()
        advanceUntilIdle()
        w.dismiss()
        assertThat(w.step.value).isEqualTo(PrivilegeWizard.Step.Idle)
    }
}
