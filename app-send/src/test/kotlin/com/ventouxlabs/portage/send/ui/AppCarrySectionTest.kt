/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM tests for the pure [carrySummary] function — guards the always-visible one-liner that tells
 * the user how many apps and how many bytes they have committed to carry. A wrong summary (e.g.
 * hiding a multi-GB total) would undermine the "can never surprise" promise.
 */
class AppCarrySectionTest {

    @Test
    fun `carrySummary with zero selected shows None selected branch and available count`() {
        assertThat(carrySummary(selectedCount = 0, availableCount = 42, selectedBytes = 0L))
            .isEqualTo("None selected · 42 apps available")
    }

    @Test
    fun `carrySummary with 2 of 5 selected shows count ratio and formatted byte total`() {
        // formatBytes uses decimal (1 000-based): 1 500 000 000 bytes → "1.5 GB"
        val bytes = 1_500_000_000L
        val summary = carrySummary(selectedCount = 2, availableCount = 5, selectedBytes = bytes)
        assertThat(summary).isEqualTo("2 of 5 selected · 1.5 GB")
    }
}
