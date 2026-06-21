/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.adbbridge

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NoopAdbBridgeTest {

    @Test
    fun `every lifecycle op reports unavailability`() = runTest {
        assertThat(NoopAdbBridge.pair(12345, "123456"))
            .isEqualTo(AdbBridge.PairingResult.Unsupported)
        assertThat(NoopAdbBridge.connect()).isEqualTo(AdbBridge.ConnectionResult.Unsupported)
        assertThat(NoopAdbBridge.isConnected()).isFalse()
        NoopAdbBridge.disconnect() // must not throw
    }

    @Test
    fun `every privileged op degrades to a typed failure`() = runTest {
        assertThat(NoopAdbBridge.shell("id")).isEqualTo(AdbBridge.ShellResult.NotConnected)
        assertThat(NoopAdbBridge.selfGrant("android.permission.WRITE_SECURE_SETTINGS"))
            .isEqualTo(AdbBridge.GrantResult.BRIDGE_UNAVAILABLE)
        assertThat(
            NoopAdbBridge.installApk(
                listOf(AdbBridge.StagedApk("base", 1024L) { java.io.ByteArrayInputStream(ByteArray(1024)) }),
            ),
        ).isEqualTo(AdbBridge.InstallResult.BridgeUnavailable)
        assertThat(NoopAdbBridge.probeCapabilities()).isEmpty()
    }

    @Test
    fun `derived convenience ops degrade through the shell default impls`() = runTest {
        assertThat(NoopAdbBridge.writeSecureSetting("ui_night_mode", "2"))
            .isEqualTo(AdbBridge.OpResult.BridgeUnavailable)
        assertThat(NoopAdbBridge.writeGlobalSetting("animator_duration_scale", "0.5"))
            .isEqualTo(AdbBridge.OpResult.BridgeUnavailable)
        assertThat(NoopAdbBridge.grantRuntimePermission("a.pkg", "a.perm"))
            .isEqualTo(AdbBridge.OpResult.BridgeUnavailable)
        assertThat(NoopAdbBridge.revokeRuntimePermission("a.pkg", "a.perm"))
            .isEqualTo(AdbBridge.OpResult.BridgeUnavailable)
        assertThat(NoopAdbBridge.setNavigationMode(AdbBridge.NavigationMode.GESTURAL))
            .isEqualTo(AdbBridge.OpResult.BridgeUnavailable)
        assertThat(NoopAdbBridge.setSmsRoleHolder("com.example.sms"))
            .isEqualTo(AdbBridge.OpResult.BridgeUnavailable)
    }
}
