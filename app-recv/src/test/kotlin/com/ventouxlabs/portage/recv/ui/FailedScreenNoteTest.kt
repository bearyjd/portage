/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression for portage #157: the failure screen told the user "Nothing was changed on this phone"
 * after a mid-stream failure that had already written 250 call-log rows. Transfers are not atomic, so
 * the note must not claim a clean rollback and must warn that a retry can duplicate applied items.
 */
class FailedScreenNoteTest {

    @Test
    fun `failure note does not claim nothing was changed`() {
        assertThat(FAILED_PARTIAL_NOTE.lowercase()).doesNotContain("nothing was changed")
    }

    @Test
    fun `failure note says items may already have been applied`() {
        assertThat(FAILED_PARTIAL_NOTE).contains("may already have been applied")
        assertThat(FAILED_PARTIAL_NOTE).contains("duplicates")
    }
}
