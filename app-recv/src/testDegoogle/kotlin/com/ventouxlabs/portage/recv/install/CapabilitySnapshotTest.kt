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

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.adbbridge.AdbBridge.PrivilegedCapability
import org.junit.Test

/** A completed probe is the sole authority for selecting the silent-install seam (#86). */
class CapabilitySnapshotTest {

    @Test
    fun `only a positive SILENT_INSTALL probe selects the silent seam`() {
        assertThat(hasSilentInstall(setOf(PrivilegedCapability.SILENT_INSTALL))).isTrue()
        assertThat(hasSilentInstall(setOf(PrivilegedCapability.SHELL))).isFalse()
        assertThat(hasSilentInstall(emptySet())).isFalse()
    }
}
