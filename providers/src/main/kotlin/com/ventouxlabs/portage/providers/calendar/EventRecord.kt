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
 * One calendar event. Times are UTC epoch millis; [allDay] events carry date precision
 * (midnight UTC). Recurring events carry the verbatim RFC 5545 [rrule] and an RFC 5545
 * [duration] (CalendarContract stores DURATION, not DTEND, for recurrences).
 */
data class EventRecord(
    val uid: String?,
    val title: String,
    val description: String?,
    val location: String?,
    val startMillis: Long,
    val endMillis: Long?,
    val allDay: Boolean,
    val rrule: String?,
    val duration: String?,
)
