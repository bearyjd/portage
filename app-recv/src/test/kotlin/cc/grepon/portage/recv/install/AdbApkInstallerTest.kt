/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.install

import cc.grepon.portage.adbbridge.AdbBridge
import cc.grepon.portage.providers.apk.ApkInstallFile
import cc.grepon.portage.providers.apk.ApkInstallResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * [AdbApkInstaller] over a fake [AdbBridge]: the InstallResult → ApkInstallResult mapping, the
 * AC-11 disconnect-in-`finally` invariant (the bridge is never left holding shell uid open, on every
 * path — success, failure, exception, connect failure), the Wireless-Debugging-off hang guard (never
 * connect into a missing endpoint), and the outer attempt-timeout ceiling (a blocked connect can
 * never hang the apply — it degrades to Tier-0).
 */
class AdbApkInstallerTest {

    /** A fake bridge that records connect/install/disconnect and returns a scripted install result. */
    private class FakeBridge(
        private var connected: Boolean = false,
        private val connectResult: AdbBridge.ConnectionResult = AdbBridge.ConnectionResult.Connected,
        private val installResult: AdbBridge.InstallResult = AdbBridge.InstallResult.Installed,
        private val installThrows: Throwable? = null,
        /** When true, connect() parks (simulating libadb's uninterruptible NsdManager discovery). */
        private val connectBlocksForever: Boolean = false,
    ) : AdbBridge {
        var connectCalls = 0
        var installCalls = 0
        var disconnectCalls = 0
        var stagedSeen: List<AdbBridge.StagedApk> = emptyList()

        override suspend fun pair(pairingPort: Int, pairingCode: String) =
            error("not used")

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

        override suspend fun installApk(staged: List<AdbBridge.StagedApk>): AdbBridge.InstallResult {
            installCalls++
            stagedSeen = staged
            installThrows?.let { throw it }
            return installResult
        }

        override suspend fun probeCapabilities(): Set<AdbBridge.PrivilegedCapability> = emptySet()
    }

    private fun installer(
        bridge: FakeBridge,
        attemptTimeoutMs: Long = 90_000L,
    ) = AdbApkInstaller(bridge = bridge, attemptTimeoutMs = attemptTimeoutMs)

    private fun files() = listOf(
        ApkInstallFile("base", 1024L) { ByteArrayInputStream(ByteArray(1024)) },
        ApkInstallFile("config.en", 512L) { ByteArrayInputStream(ByteArray(512)) },
    )

    @Test
    fun `installed maps and disconnects`() = runTest {
        val bridge = FakeBridge(connected = true, installResult = AdbBridge.InstallResult.Installed)
        val result = installer(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.Installed)
        assertThat(bridge.installCalls).isEqualTo(1)
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `maps each ApkInstallFile to a StagedApk preserving name and size`() = runTest {
        val bridge = FakeBridge(connected = true)
        installer(bridge).install("com.example.app", files())
        assertThat(bridge.stagedSeen.map { it.name to it.size })
            .containsExactly("base" to 1024L, "config.en" to 512L)
            .inOrder()
    }

    @Test
    fun `failed maps the reason and disconnects`() = runTest {
        val bridge = FakeBridge(
            connected = true,
            installResult = AdbBridge.InstallResult.Failed("INSTALL_FAILED_INVALID_APK"),
        )
        val result = installer(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.Failed("INSTALL_FAILED_INVALID_APK"))
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `bridge-unavailable install result maps and disconnects`() = runTest {
        val bridge = FakeBridge(
            connected = true,
            installResult = AdbBridge.InstallResult.BridgeUnavailable,
        )
        val result = installer(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.BridgeUnavailable)
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `connects when not connected then installs`() = runTest {
        val bridge = FakeBridge(connected = false) // starts disconnected → must connect first
        val result = installer(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.Installed)
        assertThat(bridge.connectCalls).isEqualTo(1)
        assertThat(bridge.installCalls).isEqualTo(1)
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `a connect failure is BridgeUnavailable, no install, still disconnects`() = runTest {
        val bridge = FakeBridge(
            connected = false,
            connectResult = AdbBridge.ConnectionResult.NoEndpoint,
        )
        val result = installer(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.BridgeUnavailable)
        assertThat(bridge.connectCalls).isEqualTo(1)
        assertThat(bridge.installCalls).isEqualTo(0) // never attempted the install
        assertThat(bridge.disconnectCalls).isEqualTo(1) // AC-11: disconnect still runs
    }

    @Test
    fun `an install exception propagates but disconnect still runs in finally`() = runTest {
        val boom = java.io.IOException("socket died")
        val bridge = FakeBridge(connected = true, installThrows = boom)
        val thrown = runCatching { installer(bridge).install("com.example.app", files()) }
        // Asserts on type + message, not instance identity: kotlinx.coroutines' withTimeoutOrNull
        // copies the throwable across the coroutine boundary (stacktrace recovery), so it is an equal
        // class with the same message but a different instance. The point is that it PROPAGATES.
        val error = thrown.exceptionOrNull()
        assertThat(error).isInstanceOf(java.io.IOException::class.java)
        assertThat(error).hasMessageThat().isEqualTo("socket died")
        assertThat(bridge.disconnectCalls).isEqualTo(1) // AC-11: finally ran despite the throw
    }

    // ── hang guard (the merge-blocking bug) ──────────────────────────────────────────────────────

    @Test
    fun `a NoEndpoint connect (Wireless Debugging off) degrades to BridgeUnavailable, no install`() = runTest {
        // The WD-off hang is prevented INSIDE AdbBridge.connect() (it returns NoEndpoint without ever
        // driving libadb into the uninterruptible mDNS discovery — see LocalAdbBridgeTest). Here we pin
        // the adapter side: a NoEndpoint connect maps to BridgeUnavailable → Tier-0, install never runs.
        val bridge = FakeBridge(
            connected = false,
            connectResult = AdbBridge.ConnectionResult.NoEndpoint,
        )
        val result = installer(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.BridgeUnavailable)
        assertThat(bridge.installCalls).isEqualTo(0)
        assertThat(bridge.disconnectCalls).isEqualTo(1) // AC-11 still holds
    }

    @Test
    fun `a connect that blocks forever cannot hang the apply - the timeout ceiling degrades to Tier-0`() = runTest {
        // Belt-and-suspenders: even if connect() somehow parked on an uninterruptible libadb section
        // (e.g. a future regression of the bridge's WD gate), the outer withTimeoutOrNull must fire and
        // degrade to Tier-0. (runTest virtual time advances past attemptTimeoutMs, no wall-clock wait.)
        val bridge = FakeBridge(connected = false, connectBlocksForever = true)
        val result = installer(bridge, attemptTimeoutMs = 5_000L).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.BridgeUnavailable)
        assertThat(bridge.connectCalls).isEqualTo(1) // it was attempted…
        assertThat(bridge.installCalls).isEqualTo(0) // …but never got past the blocked connect
        assertThat(bridge.disconnectCalls).isEqualTo(1) // AC-11: teardown ran even on the timeout path
    }
}
