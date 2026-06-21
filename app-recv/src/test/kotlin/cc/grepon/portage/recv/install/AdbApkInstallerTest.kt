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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * [AdbApkInstaller] over a fake [AdbBridge]: the InstallResult → ApkInstallResult mapping and the
 * AC-11 disconnect-in-`finally` invariant (the bridge is never left holding shell uid open, on every
 * path — success, failure, exception, and a connect failure).
 */
class AdbApkInstallerTest {

    /** A fake bridge that records connect/install/disconnect and returns a scripted install result. */
    private class FakeBridge(
        private var connected: Boolean = false,
        private val connectResult: AdbBridge.ConnectionResult = AdbBridge.ConnectionResult.Connected,
        private val installResult: AdbBridge.InstallResult = AdbBridge.InstallResult.Installed,
        private val installThrows: Throwable? = null,
    ) : AdbBridge {
        var connectCalls = 0
        var installCalls = 0
        var disconnectCalls = 0
        var stagedSeen: List<AdbBridge.StagedApk> = emptyList()

        override suspend fun pair(pairingPort: Int, pairingCode: String) =
            error("not used")

        override suspend fun connect(): AdbBridge.ConnectionResult {
            connectCalls++
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

    private fun files() = listOf(
        ApkInstallFile("base", 1024L) { ByteArrayInputStream(ByteArray(1024)) },
        ApkInstallFile("config.en", 512L) { ByteArrayInputStream(ByteArray(512)) },
    )

    @Test
    fun `installed maps and disconnects`() = runTest {
        val bridge = FakeBridge(connected = true, installResult = AdbBridge.InstallResult.Installed)
        val result = AdbApkInstaller(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.Installed)
        assertThat(bridge.installCalls).isEqualTo(1)
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `maps each ApkInstallFile to a StagedApk preserving name and size`() = runTest {
        val bridge = FakeBridge(connected = true)
        AdbApkInstaller(bridge).install("com.example.app", files())
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
        val result = AdbApkInstaller(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.Failed("INSTALL_FAILED_INVALID_APK"))
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `bridge-unavailable install result maps and disconnects`() = runTest {
        val bridge = FakeBridge(
            connected = true,
            installResult = AdbBridge.InstallResult.BridgeUnavailable,
        )
        val result = AdbApkInstaller(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.BridgeUnavailable)
        assertThat(bridge.disconnectCalls).isEqualTo(1)
    }

    @Test
    fun `connects when not connected then installs`() = runTest {
        val bridge = FakeBridge(connected = false) // starts disconnected → must connect first
        val result = AdbApkInstaller(bridge).install("com.example.app", files())
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
        val result = AdbApkInstaller(bridge).install("com.example.app", files())
        assertThat(result).isEqualTo(ApkInstallResult.BridgeUnavailable)
        assertThat(bridge.connectCalls).isEqualTo(1)
        assertThat(bridge.installCalls).isEqualTo(0) // never attempted the install
        assertThat(bridge.disconnectCalls).isEqualTo(1) // AC-11: disconnect still runs
    }

    @Test
    fun `an install exception propagates but disconnect still runs in finally`() = runTest {
        val boom = java.io.IOException("socket died")
        val bridge = FakeBridge(connected = true, installThrows = boom)
        val thrown = runCatching { AdbApkInstaller(bridge).install("com.example.app", files()) }
        assertThat(thrown.exceptionOrNull()).isEqualTo(boom)
        assertThat(bridge.disconnectCalls).isEqualTo(1) // AC-11: finally ran despite the throw
    }
}
