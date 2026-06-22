/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.permission

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ADR-006 D5 — pins the permission classification (the safety-critical default set). */
class PermissionAllowlistTest {

    @Test
    fun `bucket classifies the known permissions`() {
        assertThat(PermissionAllowlist.bucket(PermissionAllowlist.INTERNET))
            .isEqualTo(PermissionAllowlist.Bucket.DEFAULT)
        assertThat(PermissionAllowlist.bucket(PermissionAllowlist.OTHER_SENSORS))
            .isEqualTo(PermissionAllowlist.Bucket.DEFAULT)
        assertThat(PermissionAllowlist.bucket("android.permission.WRITE_SECURE_SETTINGS"))
            .isEqualTo(PermissionAllowlist.Bucket.NEVER)
        assertThat(PermissionAllowlist.bucket("android.permission.CAMERA"))
            .isEqualTo(PermissionAllowlist.Bucket.OPT_IN)
    }

    @Test
    fun `OTHER_SENSORS was promoted into the default-safe set after the Phase 5c re-verify`() {
        // ADR-006 D5: OTHER_SENSORS is now V7-PASS (2026-06-21 hardware re-verify) → in the default set.
        assertThat(PermissionAllowlist.DEFAULT_SAFE).contains(PermissionAllowlist.OTHER_SENSORS)
        assertThat(PermissionAllowlist.PROVISIONAL).isEmpty()
    }

    @Test
    fun `the default-safe set is exactly INTERNET and OTHER_SENSORS`() {
        assertThat(PermissionAllowlist.DEFAULT_SAFE)
            .containsExactly(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS)
    }
}
