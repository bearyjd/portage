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
}
