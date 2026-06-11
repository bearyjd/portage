/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.calendar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class IcsTest {

    private fun roundTrip(events: List<EventRecord>): IcsParseResult {
        val out = ByteArrayOutputStream()
        Ics.write(events, out)
        return Ics.parse(ByteArrayInputStream(out.toByteArray()))
    }

    private fun parse(text: String): IcsParseResult =
        Ics.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    @Test
    fun `round trips a timed event`() {
        val event = EventRecord(
            uid = "abc-123@portage",
            title = "Dentist; bring forms",
            description = "Floss\nmore",
            location = "12 Main St, Springfield",
            startMillis = 1_750_000_000_000,
            endMillis = 1_750_003_600_000,
            allDay = false,
            rrule = null,
            duration = null,
        )

        val back = roundTrip(listOf(event))

        assertThat(back.malformed).isEqualTo(0)
        assertThat(back.events).containsExactly(event)
    }

    @Test
    fun `round trips an all-day event at date precision`() {
        // 2026-06-10T00:00:00Z
        val event = EventRecord(
            uid = null,
            title = "Anniversary",
            description = null,
            location = null,
            startMillis = 1_781_049_600_000,
            endMillis = null,
            allDay = true,
            rrule = "FREQ=YEARLY",
            duration = "P1D",
        )

        val back = roundTrip(listOf(event))

        assertThat(back.malformed).isEqualTo(0)
        assertThat(back.events).containsExactly(event)
    }

    @Test
    fun `writes a single VCALENDAR wrapper with CRLF`() {
        val out = ByteArrayOutputStream()
        Ics.write(
            listOf(EventRecord(null, "X", null, null, 0, null, false, null, null)),
            out,
        )
        val text = out.toString(Charsets.UTF_8)

        assertThat(text).startsWith("BEGIN:VCALENDAR\r\n")
        assertThat(text).endsWith("END:VCALENDAR\r\n")
        assertThat(text).contains("VERSION:2.0\r\n")
        assertThat(text).contains("BEGIN:VEVENT\r\n")
    }

    @Test
    fun `an event without SUMMARY or DTSTART is malformed but not fatal`() {
        val back = parse(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20260610T120000Z
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:No start
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:Keeper
            DTSTART:20260610T120000Z
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )

        assertThat(back.malformed).isEqualTo(2)
        assertThat(back.events.map { it.title }).containsExactly("Keeper")
    }

    @Test
    fun `unknown properties and components are ignored`() {
        val back = parse(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTIMEZONE
            TZID:America/New_York
            END:VTIMEZONE
            BEGIN:VEVENT
            SUMMARY:Keeper
            DTSTART:20260610T120000Z
            SEQUENCE:3
            X-WHATEVER:y
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )

        assertThat(back.malformed).isEqualTo(0)
        assertThat(back.events.map { it.title }).containsExactly("Keeper")
    }

    @Test
    fun `a malformed DTSTART value is counted malformed`() {
        val back = parse(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Bad clock
            DTSTART:not-a-date
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )

        assertThat(back.events).isEmpty()
        assertThat(back.malformed).isEqualTo(1)
    }

    @Test
    fun `empty input parses to an empty result`() {
        val back = parse("")
        assertThat(back.events).isEmpty()
        assertThat(back.malformed).isEqualTo(0)
    }
}
