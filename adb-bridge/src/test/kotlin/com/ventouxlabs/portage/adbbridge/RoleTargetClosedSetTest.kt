/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.adbbridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Closed-set tripwire for the PRIVILEGED side of default-app role restore (#122).
 *
 * `RestorableRole` (the wire-facing enum in `:providers`) has its own tripwire in
 * `RoleRestoreConsentTest`. This is its twin, and it is not redundant: the exhaustive `when` in
 * `AdbRoleRestorer` forces the two enums to agree on the MAPPING, but nothing stops a fourth entry
 * being added to [AdbBridge.RoleTarget] alone and invoked directly from inside `:adb-bridge` — where
 * the wire-facing enum would never see it and no `when` would fail to compile.
 *
 * This is the side that actually holds the privileged verb: [AdbBridge.setRoleHolder] hands a system
 * capability to a package with no platform confirm dialog. Widening it is a privilege-surface change
 * that must go through a security-review lane rather than riding along in an unrelated PR — which is
 * what this test makes impossible to do quietly.
 */
class RoleTargetClosedSetTest {

    @Test
    fun `RoleTarget stays closed to the three reviewed roles`() {
        assertThat(AdbBridge.RoleTarget.entries.map { it.name })
            .containsExactly("BROWSER", "DIALER", "HOME")
    }

    @Test
    fun `each RoleTarget maps to the platform role name it claims`() {
        // A rename of the underlying role string is the same privilege change as adding an entry —
        // it re-aims the verb at a different system capability while the Kotlin name still reads as
        // the reviewed one.
        assertThat(AdbBridge.RoleTarget.BROWSER.roleName).isEqualTo("android.app.role.BROWSER")
        assertThat(AdbBridge.RoleTarget.DIALER.roleName).isEqualTo("android.app.role.DIALER")
        assertThat(AdbBridge.RoleTarget.HOME.roleName).isEqualTo("android.app.role.HOME")
    }

    @Test
    fun `SMS is NOT reachable through this verb`() {
        // SMS ships separately via setSmsRoleHolder with its own transient acquire/write/relinquish
        // discipline and hard self-gate, and is currently broken on GOS (#61). Folding it in here
        // would entangle this verb with that bug and widen what one call can do.
        assertThat(AdbBridge.RoleTarget.entries.map { it.roleName })
            .doesNotContain("android.app.role.SMS")
    }
}
