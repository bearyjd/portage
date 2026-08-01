/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.providers.roles.RestorableRole
import com.ventouxlabs.portage.providers.roles.RoleRestorer
import org.junit.Test

/**
 * The consent gate for default-app role restore (#122).
 *
 * Restoring a role through the bridge shows NO system confirm dialog, so portage's own gating is
 * the only thing that exists. These tests pin the two properties that matter, at the level of
 * [RoleRestorer] and the closed role set:
 *
 *  1. Nothing restores without going through the seam — and the seam's DEFAULT is Unavailable, so a
 *     caller that forgets to wire it degrades to "cannot restore", never to an unguarded restore.
 *  2. Only outcomes the platform actually confirmed count as restored.
 *
 * The ViewModel-level belt (a (role, package) pair must already be an OFFERED candidate on the live
 * Done state) is exercised in `ReceiverViewModel.restoreRole`; these cover the seam contract it
 * depends on.
 */
class RoleRestoreConsentTest {

    @Test
    fun `the default RoleRestorer restores nothing`() = kotlinx.coroutines.test.runTest {
        // The wiring default. If a build forgets to supply a real restorer, every role must report
        // UNAVAILABLE rather than silently succeeding or throwing.
        RestorableRole.entries.forEach { role ->
            assertThat(RoleRestorer.Unavailable.restore(role, "com.example.app"))
                .isEqualTo(RoleRestorer.Outcome.UNAVAILABLE)
        }
    }

    // DELIBERATELY ABSENT: a test that builds `RoleRestorer { _, _ -> REJECTED }` and asserts the
    // result is not RESTORED. One used to live here. It exercised Kotlin's fun-interface dispatch,
    // not portage — no production control could be deleted to make it fail. The property it was
    // named for (a non-confirming restorer must not be treated as success) is held by
    // `RoleRestoreBeltTest.a restorer that does not confirm leaves the offer in place and claims
    // nothing`, which drives the real ViewModel and dies to a real mutation.

    // The BRIDGE-side twin of the tripwire below lives in `:adb-bridge`'s own test module
    // (`RoleTargetClosedSetTest`), NOT here. This file is in `src/test`, shared by both flavors, and
    // play links no `:adb-bridge` at all — importing AdbBridge here would break the play unit-test
    // compile and risk dragging the bridge into the play APK, which the CI no-bridge assert exists
    // to prevent.

    @Test
    fun `the restorable role set stays closed to the three reviewed roles`() {
        // A tripwire, deliberately. Adding a role here widens what one privileged verb can hand
        // over, so it is a security-surface change that must go through a review lane rather than
        // riding along in an unrelated PR. SMS is excluded on purpose: it ships separately with its
        // own transient acquire/write/relinquish discipline, and is broken on GOS (#61).
        assertThat(RestorableRole.entries.map { it.name })
            .containsExactly("BROWSER", "DIALER", "HOME")
    }
}
