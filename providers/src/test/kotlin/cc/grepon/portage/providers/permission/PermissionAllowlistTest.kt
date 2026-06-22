/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.permission

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ADR-006 D5 — pins the permission classification (the safety-critical default set). */
class PermissionAllowlistTest {

    @Test
    fun `bucket classifies the known permissions`() {
        assertThat(PermissionAllowlist.bucket(PermissionAllowlist.INTERNET))
            .isEqualTo(PermissionAllowlist.Bucket.DEFAULT)
        assertThat(PermissionAllowlist.bucket(PermissionAllowlist.OTHER_SENSORS))
            .isEqualTo(PermissionAllowlist.Bucket.OPT_IN)
        assertThat(PermissionAllowlist.bucket("android.permission.WRITE_SECURE_SETTINGS"))
            .isEqualTo(PermissionAllowlist.Bucket.NEVER)
        assertThat(PermissionAllowlist.bucket("android.permission.CAMERA"))
            .isEqualTo(PermissionAllowlist.Bucket.OPT_IN)
    }

    @Test
    fun `OTHER_SENSORS is provisional and not yet in the default-safe set`() {
        // Locks ADR-006 D5: OTHER_SENSORS is V7-TENTATIVE and must NOT be auto-granted until Phase 5c.
        assertThat(PermissionAllowlist.DEFAULT_SAFE).doesNotContain(PermissionAllowlist.OTHER_SENSORS)
        assertThat(PermissionAllowlist.PROVISIONAL).contains(PermissionAllowlist.OTHER_SENSORS)
    }

    @Test
    fun `the default-safe set contains only INTERNET today`() {
        assertThat(PermissionAllowlist.DEFAULT_SAFE).containsExactly(PermissionAllowlist.INTERNET)
    }
}
