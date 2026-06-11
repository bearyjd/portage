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

import cc.grepon.portage.model.ItemStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private class FakeCalendarStore(
    private val events: MutableList<EventRecord> = mutableListOf(),
    var throwOnRead: Boolean = false,
    var hasWritableCalendar: Boolean = true,
) : CalendarStore {
    val inserted = mutableListOf<EventRecord>()

    override fun count(): Int {
        if (throwOnRead) throw SecurityException("READ_CALENDAR denied")
        return events.size
    }

    override fun readAll(): List<EventRecord> {
        if (throwOnRead) throw SecurityException("READ_CALENDAR denied")
        return events.toList()
    }

    override fun insert(event: EventRecord): Boolean {
        if (!hasWritableCalendar) return false
        inserted += event
        return true
    }
}

class CalendarProvidersTest {

    private val standup = EventRecord(
        uid = "u1", title = "Standup", description = null, location = null,
        startMillis = 1_750_000_000_000, endMillis = 1_750_001_800_000,
        allDay = false, rrule = null, duration = null,
    )

    @Test
    fun `available follows readability and content`() = runTest {
        assertThat(CalendarExportProvider(FakeCalendarStore()).available()).isFalse()
        assertThat(CalendarExportProvider(FakeCalendarStore(mutableListOf(standup))).available()).isTrue()
        assertThat(CalendarExportProvider(FakeCalendarStore(throwOnRead = true)).available()).isFalse()
    }

    @Test
    fun `exportTo streams ICS and denied permission yields an empty calendar`() = runTest {
        val out = ByteArrayOutputStream()
        CalendarExportProvider(FakeCalendarStore(mutableListOf(standup))).exportTo(out)
        assertThat(Ics.parse(ByteArrayInputStream(out.toByteArray())).events).containsExactly(standup)

        val deniedOut = ByteArrayOutputStream()
        CalendarExportProvider(FakeCalendarStore(throwOnRead = true)).exportTo(deniedOut)
        assertThat(Ics.parse(ByteArrayInputStream(deniedOut.toByteArray())).events).isEmpty()
    }

    @Test
    fun `apply inserts parsed events and reports counts`() = runTest {
        val store = FakeCalendarStore()
        val payload = ByteArrayOutputStream().also { Ics.write(listOf(standup), it) }

        val outcome = CalendarApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(standup)
    }

    @Test
    fun `no writable calendar means WRITE_ERROR, not a crash`() = runTest {
        val store = FakeCalendarStore(hasWritableCalendar = false)
        val payload = ByteArrayOutputStream().also { Ics.write(listOf(standup), it) }

        val outcome = CalendarApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(store.inserted).isEmpty()
    }
}
