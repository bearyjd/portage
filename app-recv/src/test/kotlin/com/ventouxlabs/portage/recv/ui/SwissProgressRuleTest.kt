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
 * Unit coverage for [sweepTranslation], the pure translationX formula behind [SwissIndeterminateRule]
 * (portage #58). It maps the 0f→1f sweep to a segment that enters fully off the left edge and exits
 * fully off the right, so the loop restart happens off-screen.
 */
class SwissProgressRuleTest {

    private val trackPx = 1000f
    private val segment = 0.30f

    @Test
    fun `at sweep start the segment sits fully off the left edge`() {
        // translationX = -segment * trackPx → the segment's right edge lands exactly at x = 0.
        assertThat(sweepTranslation(trackPx, segment, sweep = 0f)).isWithin(1e-3f).of(-300f)
    }

    @Test
    fun `at sweep end the segment sits fully off the right edge`() {
        // translationX = trackPx → the segment's left edge lands exactly at the track's right edge.
        assertThat(sweepTranslation(trackPx, segment, sweep = 1f)).isWithin(1e-3f).of(1000f)
    }

    @Test
    fun `midway the segment straddles the track`() {
        // 1000 * (1.3 * 0.5 - 0.3) = 350.
        assertThat(sweepTranslation(trackPx, segment, sweep = 0.5f)).isWithin(1e-3f).of(350f)
    }

    @Test
    fun `translation increases monotonically with sweep`() {
        var previous = sweepTranslation(trackPx, segment, sweep = 0f)
        for (i in 1..10) {
            val next = sweepTranslation(trackPx, segment, sweep = i / 10f)
            assertThat(next > previous).isTrue()
            previous = next
        }
    }

    @Test
    fun `a zero-width track never moves the segment`() {
        assertThat(sweepTranslation(trackPx = 0f, segment, sweep = 0.5f)).isWithin(1e-3f).of(0f)
    }
}
