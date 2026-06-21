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

import cc.grepon.portage.adbbridge.AdbBridge.PrivilegedCapability
import cc.grepon.portage.wizard.PrivilegeWizard
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** D6 capability plumbing: read the probed set from the wizard step; choose the silent seam. */
class CapabilitySnapshotTest {

    @Test
    fun `a Ready step exposes its probed capabilities`() {
        val step = PrivilegeWizard.Step.Ready(
            setOf(PrivilegedCapability.SHELL, PrivilegedCapability.SILENT_INSTALL),
        )
        assertThat(capabilitiesOf(step))
            .containsExactly(PrivilegedCapability.SHELL, PrivilegedCapability.SILENT_INSTALL)
    }

    @Test
    fun `every non-Ready step yields the empty set (safe Tier-0 direction)`() {
        // Process death / not-yet-run / skipped all lose the set → emptySet → Tier-0 fallback.
        assertThat(capabilitiesOf(PrivilegeWizard.Step.Idle)).isEmpty()
        assertThat(capabilitiesOf(PrivilegeWizard.Step.Probing)).isEmpty()
        assertThat(capabilitiesOf(PrivilegeWizard.Step.Skipped)).isEmpty()
    }

    @Test
    fun `hasSilentInstall is true only when SILENT_INSTALL is in a Ready step`() {
        assertThat(hasSilentInstall(PrivilegeWizard.Step.Ready(setOf(PrivilegedCapability.SILENT_INSTALL))))
            .isTrue()
        assertThat(hasSilentInstall(PrivilegeWizard.Step.Ready(setOf(PrivilegedCapability.SHELL))))
            .isFalse()
        assertThat(hasSilentInstall(PrivilegeWizard.Step.Skipped)).isFalse()
    }
}
