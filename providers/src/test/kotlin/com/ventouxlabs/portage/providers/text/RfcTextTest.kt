/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.text

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.providers.wire.PhysicalLine
import org.junit.Test

class RfcTextTest {

    @Test
    fun `escapes backslash semicolon comma and newline`() {
        assertThat(RfcText.escape("""a\b;c,d""" + "\ne")).isEqualTo("""a\\b\;c\,d\ne""")
    }

    @Test
    fun `unescape inverts escape`() {
        val gnarly = "Acme; Inc.\\West, branch\nSuite 4"
        assertThat(RfcText.unescape(RfcText.escape(gnarly))).isEqualTo(gnarly)
    }

    @Test
    fun `unescape accepts uppercase N as newline`() {
        assertThat(RfcText.unescape("""line1\Nline2""")).isEqualTo("line1\nline2")
    }

    @Test
    fun `unescape leaves a trailing lone backslash intact`() {
        assertThat(RfcText.unescape("""abc\""")).isEqualTo("""abc\""")
    }

    @Test
    fun `unescape passes through unknown escapes verbatim`() {
        assertThat(RfcText.unescape("""a\tb""")).isEqualTo("""a\tb""")
    }

    @Test
    fun `plain text is untouched`() {
        assertThat(RfcText.escape("plain")).isEqualTo("plain")
        assertThat(RfcText.unescape("plain")).isEqualTo("plain")
    }

    @Test
    fun `unfold merges continuation lines starting with space or tab`() {
        val folded = listOf(
            "DESCRIPTION:first part",
            " second part",
            "\tthird part",
            "SUMMARY:next prop",
        )
        assertThat(RfcText.unfold(folded)).containsExactly(
            "DESCRIPTION:first partsecond partthird part",
            "SUMMARY:next prop",
        ).inOrder()
    }

    @Test
    fun `unfoldWithGaps discards the continuation run after a dropped line`() {
        val lines = listOf(
            PhysicalLine.Text("SUMMARY:Lunch"),
            PhysicalLine.Dropped(continuation = false),
            PhysicalLine.Text(" tail"),
            PhysicalLine.Text("\tmore"),
            PhysicalLine.Text("X:1"),
            PhysicalLine.Text(" fold"),
        )
        assertThat(RfcText.unfoldWithGaps(lines)).containsExactly("SUMMARY:Lunch", "X:1fold").inOrder()
    }

    @Test
    fun `unfoldWithGaps discards the whole property when its continuation was dropped`() {
        val lines = listOf(
            PhysicalLine.Text("X:a"),
            PhysicalLine.Dropped(continuation = true),
            PhysicalLine.Text(" tail"),
            PhysicalLine.Text("Y:b"),
        )
        assertThat(RfcText.unfoldWithGaps(lines)).containsExactly("Y:b")
    }

    @Test
    fun `unfold ignores a leading continuation with nothing to attach to`() {
        assertThat(RfcText.unfold(listOf(" dangling", "X:1"))).containsExactly("X:1")
    }

    @Test
    fun `fold leaves short lines alone`() {
        assertThat(RfcText.fold("FN:Ada")).isEqualTo("FN:Ada")
    }

    @Test
    fun `fold keeps every physical line within 75 octets and unfold restores it`() {
        val line = "NOTE:" + "x".repeat(200)
        val folded = RfcText.fold(line)

        val physical = folded.split("\r\n")
        assertThat(physical.size).isGreaterThan(1)
        physical.forEach { assertThat(it.toByteArray(Charsets.UTF_8).size).isAtMost(75) }
        physical.drop(1).forEach { assertThat(it).startsWith(" ") }

        assertThat(RfcText.unfold(physical)).containsExactly(line)
    }

    @Test
    fun `fold never splits a multi-byte character`() {
        val line = "NOTE:" + "é".repeat(120) // 2 octets each
        val folded = RfcText.fold(line)

        folded.split("\r\n").forEach { physical ->
            val content = physical.removePrefix(" ")
            assertThat(content.toByteArray(Charsets.UTF_8).size).isAtMost(75)
            // Re-encoding each piece must be valid UTF-8 of whole chars: é count must add up.
        }
        assertThat(RfcText.unfold(folded.split("\r\n"))).containsExactly(line)
    }

    @Test
    fun `unfold runs in linear time on a huge run of continuation lines`() {
        val lines = ArrayList<String>(400_001)
        lines += "NOTE:"
        repeat(400_000) { lines += " x" }

        val start = System.nanoTime()
        val result = RfcText.unfold(lines)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertThat(result).hasSize(1)
        assertThat(result[0].length).isEqualTo(5 + 400_000)
        assertThat(elapsedMs).isLessThan(2_000)
    }
}
