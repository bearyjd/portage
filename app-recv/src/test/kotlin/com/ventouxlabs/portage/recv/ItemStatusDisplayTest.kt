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

import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.recv.ui.isTerminal
import com.ventouxlabs.portage.recv.ui.statusReason
import com.ventouxlabs.portage.recv.ui.statusWord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ItemStatusDisplayTest {

    @Test fun `statusWord covers all ItemStatus values`() {
        assertThat(statusWord(ItemStatus.OK)).isEqualTo("MOVED")
        assertThat(statusWord(ItemStatus.SKIPPED)).isEqualTo("SKIPPED")
        assertThat(statusWord(ItemStatus.HASH_MISMATCH)).isEqualTo("DAMAGED")
        assertThat(statusWord(ItemStatus.WRITE_ERROR)).isEqualTo("NOT SAVED")
        assertThat(statusWord(ItemStatus.UNKNOWN_KIND)).isEqualTo("UNKNOWN")
        assertThat(statusWord(ItemStatus.OVERSIZE)).isEqualTo("TOO BIG")
    }

    @Test fun `statusReason is null for OK and non-null for every failure status`() {
        assertThat(statusReason(ItemStatus.OK)).isNull()
        assertThat(statusReason(ItemStatus.SKIPPED)).isNotNull()
        assertThat(statusReason(ItemStatus.HASH_MISMATCH)).isNotNull()
        assertThat(statusReason(ItemStatus.WRITE_ERROR)).isNotNull()
        assertThat(statusReason(ItemStatus.UNKNOWN_KIND)).isNotNull()
        assertThat(statusReason(ItemStatus.OVERSIZE)).isNotNull()
    }

    @Test fun `isTerminal correctly partitions transient vs terminal statuses`() {
        // OK is filtered upstream; classified non-terminal to keep the `when` exhaustive
        assertThat(isTerminal(ItemStatus.OK)).isFalse()
        // transient — sending again can succeed
        assertThat(isTerminal(ItemStatus.HASH_MISMATCH)).isFalse()
        assertThat(isTerminal(ItemStatus.WRITE_ERROR)).isFalse()
        // terminal — same verdict on re-send
        assertThat(isTerminal(ItemStatus.SKIPPED)).isTrue()
        assertThat(isTerminal(ItemStatus.UNKNOWN_KIND)).isTrue()
        assertThat(isTerminal(ItemStatus.OVERSIZE)).isTrue()
    }
}
