/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.shizuku

import cc.grepon.portage.privileged.PrivilegedOps.Availability
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The pure (availability, canWrite) → strand mapping the unlock affordance reads from. */
class ShizukuAccessStrandTest {

    @Test
    fun `a held grant is UNLOCKED regardless of availability`() {
        // The grant outlives Shizuku (ADR-001 §1), so canWrite wins even if the bridge later dies.
        for (availability in Availability.entries) {
            assertThat(strandFor(availability, canWriteSecureSettings = true))
                .isEqualTo(ShizukuAccessStrand.UNLOCKED)
        }
    }

    @Test
    fun `no Shizuku, no grant maps to NOT_INSTALLED`() {
        assertThat(strandFor(Availability.NOT_INSTALLED, canWriteSecureSettings = false))
            .isEqualTo(ShizukuAccessStrand.NOT_INSTALLED)
    }

    @Test
    fun `a too-old server maps to OUTDATED`() {
        assertThat(strandFor(Availability.OUTDATED, canWriteSecureSettings = false))
            .isEqualTo(ShizukuAccessStrand.OUTDATED)
    }

    @Test
    fun `installed but not running maps to NOT_RUNNING`() {
        assertThat(strandFor(Availability.INSTALLED_NOT_RUNNING, canWriteSecureSettings = false))
            .isEqualTo(ShizukuAccessStrand.NOT_RUNNING)
    }

    @Test
    fun `reachable but unauthorized maps to LOCKED`() {
        assertThat(strandFor(Availability.PERMISSION_DENIED, canWriteSecureSettings = false))
            .isEqualTo(ShizukuAccessStrand.LOCKED)
    }

    @Test
    fun `authorized but not yet granted is still LOCKED — authorization is not the finish line`() {
        // LIVE means the Shizuku permission is held, but WRITE_SECURE_SETTINGS still needs the
        // one-shot grant; until canWrite flips, the unlock action remains the next step.
        assertThat(strandFor(Availability.LIVE, canWriteSecureSettings = false))
            .isEqualTo(ShizukuAccessStrand.LOCKED)
    }
}
