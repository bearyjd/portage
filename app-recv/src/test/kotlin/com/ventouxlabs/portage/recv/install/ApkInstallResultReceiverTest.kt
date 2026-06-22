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

import android.content.pm.PackageInstaller
import com.ventouxlabs.portage.recv.install.ApkInstallResultReceiver.InstallResultAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pure broadcast→confirm routing decision (ADR-006 D3/D6): only STATUS_PENDING_USER_ACTION carrying a
 * confirm intent launches the system install dialog. The full broadcast→startActivity chain is
 * instrumented/hardware-only (a CLAUDE.md VERIFY_FIRST); this pins the decision logic itself.
 */
class ApkInstallResultReceiverTest {

    @Test
    fun `pending user action with a confirm intent launches the system confirm dialog`() {
        val action = ApkInstallResultReceiver.routeStatus(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            hasConfirmIntent = true,
        )
        assertThat(action).isEqualTo(InstallResultAction.LAUNCH_CONFIRM)
    }

    @Test
    fun `pending user action without a confirm intent is a no-op (nothing to launch)`() {
        val action = ApkInstallResultReceiver.routeStatus(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            hasConfirmIntent = false,
        )
        assertThat(action).isEqualTo(InstallResultAction.NONE)
    }

    @Test
    fun `a terminal SUCCESS status is a no-op`() {
        val action = ApkInstallResultReceiver.routeStatus(PackageInstaller.STATUS_SUCCESS, hasConfirmIntent = false)
        assertThat(action).isEqualTo(InstallResultAction.NONE)
    }

    @Test
    fun `a terminal FAILURE status is a no-op even if a confirm intent is somehow present`() {
        // Defensive: a confirm intent only matters under STATUS_PENDING_USER_ACTION; a FAILURE never
        // launches a dialog regardless of the extra.
        val action = ApkInstallResultReceiver.routeStatus(PackageInstaller.STATUS_FAILURE, hasConfirmIntent = true)
        assertThat(action).isEqualTo(InstallResultAction.NONE)
    }
}
