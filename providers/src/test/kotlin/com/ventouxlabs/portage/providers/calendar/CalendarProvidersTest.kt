/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.calendar

import com.ventouxlabs.portage.model.ItemStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private class FakeCalendarStore(
    private val events: MutableList<EventRecord> = mutableListOf(),
    var throwOnRead: Boolean = false,
    var hasWritableCalendar: Boolean = true,
    /** Models a device where creating a local calendar is refused (provider rejects the insert). */
    var canCreateLocalCalendar: Boolean = true,
) : CalendarStore {
    val inserted = mutableListOf<EventRecord>()
    val createdCalendars = mutableListOf<String>()

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

    override fun hasWritableCalendar(): Boolean = hasWritableCalendar

    override fun createLocalCalendar(displayName: String): Boolean {
        if (!canCreateLocalCalendar) return false
        createdCalendars += displayName
        hasWritableCalendar = true // it now exists, so inserts start succeeding
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

    /**
     * #159. A freshly-set-up degoogled phone commonly has ZERO calendars, which is portage's
     * target user — not an edge case. Previously every insert failed and the item reported a
     * generic WRITE_ERROR, so the Done screen said "worth sending again" when sending again could
     * never help. Now the apply creates a local, account-less calendar to receive the events.
     */
    @Test
    fun `no calendar on the device creates a local one and the events land`() = runTest {
        val store = FakeCalendarStore(hasWritableCalendar = false)
        val payload = ByteArrayOutputStream().also { Ics.write(listOf(standup), it) }

        val outcome = CalendarApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.createdCalendars).containsExactly(CalendarApplyProvider.LOCAL_CALENDAR_NAME)
        assertThat(store.inserted).containsExactly(standup)
        // The user must be told a calendar appeared — it is a visible change to their device.
        assertThat(outcome.detail).contains(CalendarApplyProvider.LOCAL_CALENDAR_NAME)
    }

    /** A device that already has a calendar must NOT get a second one created behind the user. */
    @Test
    fun `an existing calendar is used as-is, no local calendar is created`() = runTest {
        val store = FakeCalendarStore(hasWritableCalendar = true)
        val payload = ByteArrayOutputStream().also { Ics.write(listOf(standup), it) }

        val outcome = CalendarApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.createdCalendars).isEmpty()
        assertThat(outcome.detail).doesNotContain(CalendarApplyProvider.LOCAL_CALENDAR_NAME)
    }

    /**
     * Creation can still be refused (OEM policy, restricted profile). That must stay a visible,
     * TRUTHFUL failure — never a crash, and never the old "worth sending again" implication.
     */
    @Test
    fun `a refused local calendar is still WRITE_ERROR and says why`() = runTest {
        val store = FakeCalendarStore(hasWritableCalendar = false, canCreateLocalCalendar = false)
        val payload = ByteArrayOutputStream().also { Ics.write(listOf(standup), it) }

        val outcome = CalendarApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(store.inserted).isEmpty()
        assertThat(outcome.detail).contains("no calendar")
    }

    /**
     * The calendar gets created but every insert still fails (permission revoked mid-apply, or the
     * provider rejects the rows). Rare, but the report must not say "added to a new local
     * calendar" when nothing was added — that is #159's original lie relocated, not fixed.
     */
    @Test
    fun `a created calendar with zero successful inserts never claims events were added`() = runTest {
        val store = object : CalendarStore {
            var created = false
            override fun count() = 0
            override fun readAll(): List<EventRecord> = emptyList()
            override fun insert(event: EventRecord) = false // every write fails
            override fun hasWritableCalendar() = created
            override fun createLocalCalendar(displayName: String): Boolean {
                created = true
                return true
            }
        }
        val payload = ByteArrayOutputStream().also { Ics.write(listOf(standup), it) }

        val outcome = CalendarApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(outcome.detail).doesNotContain("added to")
    }

    /** An empty payload must not create a calendar the user then has to delete for nothing. */
    @Test
    fun `an empty payload creates no calendar`() = runTest {
        val store = FakeCalendarStore(hasWritableCalendar = false)
        val payload = ByteArrayOutputStream().also { Ics.write(emptyList(), it) }

        val outcome = CalendarApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.createdCalendars).isEmpty()
    }
}
