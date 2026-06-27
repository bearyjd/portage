/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.privilege

import com.ventouxlabs.portage.providers.apk.ApkInstallResult
import com.ventouxlabs.portage.providers.apk.ApkSilentInstaller
import com.ventouxlabs.portage.providers.apk.RuntimePermissionGranter
import com.ventouxlabs.portage.providers.apk.TargetDeclaredPermissions
import com.ventouxlabs.portage.providers.settings.TierOneGrant
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The play no-bridge invariant, made durable as a unit test (distribution-flavor split, ADR-003). The
 * play (Tier-0-only) flavor must compile and ship with ZERO privilege wired in: no advanced setup, no
 * silent installer, no runtime-permission granter, no Tier-1 secure-settings grant. If a privilege
 * adapter is ever wired into the play flavor's [providePrivilegeIntegration] / [playWiring], this test
 * fails — a regression belt complementing the CI bridge-free APK/manifest gate.
 *
 * The wiring is asserted via [playWiring] (Context-free by construction) — exactly the seams
 * [PlayPrivilegeIntegration.wiring] returns — because the JVM unit-test toolchain (no Robolectric /
 * mocking lib) cannot fabricate an Android Context.
 */
class PlayPrivilegeIntegrationTest {

    @Test
    fun `play offers no advanced setup`() {
        assertThat(PlayPrivilegeIntegration.offersAdvancedSetup).isFalse()
    }

    @Test
    fun `play wiring is the no-op seams - silent install deferred to Tier-0`() = runTest {
        val wiring = playWiring()
        assertThat(wiring.hasSilentInstall()).isFalse()
        assertThat(wiring.silentInstaller).isSameInstanceAs(ApkSilentInstaller.Deferred)
        // The Deferred installer routes every app to the Tier-0 PackageInstaller fallback.
        assertThat(wiring.silentInstaller.install("com.example.app", emptyList()))
            .isEqualTo(ApkInstallResult.Deferred)
    }

    @Test
    fun `play wiring grants no runtime permissions and never reaches Tier-1`() = runTest {
        val wiring = playWiring()
        assertThat(wiring.permissionGranter).isSameInstanceAs(RuntimePermissionGranter.NoOp)
        assertThat(wiring.optInPermissionGranter).isSameInstanceAs(RuntimePermissionGranter.NoOp)
        assertThat(wiring.permissionGranter.grant("com.example.app", listOf("android.permission.INTERNET")))
            .isEmpty()
        assertThat(wiring.targetDeclaredPermissions).isSameInstanceAs(TargetDeclaredPermissions.None)
        assertThat(wiring.targetDeclaredPermissions.declaredPermissions("com.example.app")).isEmpty()
        // The Tier-1 WRITE_SECURE_SETTINGS grant is permanently unavailable on play.
        assertThat(wiring.tierOneGrant.ensureWriteSecureSettingsGranted())
            .isEqualTo(TierOneGrant.Outcome.UNAVAILABLE)
    }
}
