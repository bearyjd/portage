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

/**
 * The ContentResolver seam for the calendar. Reads MAY throw [SecurityException] when
 * READ_CALENDAR is denied — providers degrade, never crash. [insert] returns false when
 * there is no writable target calendar or the write fails (never throws).
 */
interface CalendarStore {

    fun count(): Int

    fun readAll(): List<EventRecord>

    fun insert(event: EventRecord): Boolean

    /**
     * Whether a calendar exists that events can actually be written to (#159). MUST have no side
     * effects — it is called at REVIEW time, before the user has agreed to anything, to disclose
     * up front that a local calendar will be created.
     */
    fun hasWritableCalendar(): Boolean

    /**
     * Create a local, account-less calendar named [displayName] to receive imported events, and
     * return whether it now exists. Only called when [hasWritableCalendar] is false.
     *
     * A degoogled phone with no Google/Exchange account commonly has ZERO calendars, which makes
     * this portage's target case rather than an edge one — without it, calendar transfer simply
     * cannot work for the user portage is built for. Creation can still be refused (OEM policy,
     * restricted profile), which returns false rather than throwing.
     */
    fun createLocalCalendar(displayName: String): Boolean
}
