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

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events

/**
 * Thin CalendarContract adapter behind [CalendarStore]. Reads propagate [SecurityException]
 * (providers degrade); writes return false on any failure. Inserts target the device's
 * primary writable calendar; recurring events get DURATION (CalendarContract requires it
 * instead of DTEND for recurrences).
 */
class AndroidCalendarStore(private val resolver: ContentResolver) : CalendarStore {

    override fun count(): Int =
        resolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID), "${Events.DELETED}=0", null, null,
        )?.use { it.count } ?: 0

    override fun readAll(): List<EventRecord> {
        val projection = arrayOf(
            Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART,
            Events.DTEND, Events.ALL_DAY, Events.RRULE, Events.DURATION, Events.UID_2445,
        )
        val events = mutableListOf<EventRecord>()
        resolver.query(Events.CONTENT_URI, projection, "${Events.DELETED}=0", null, Events.DTSTART)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    if (cursor.isNull(3)) continue
                    events += EventRecord(
                        uid = cursor.getString(8)?.ifBlank { null },
                        title = title,
                        description = cursor.getString(1)?.ifBlank { null },
                        location = cursor.getString(2)?.ifBlank { null },
                        startMillis = cursor.getLong(3),
                        endMillis = if (cursor.isNull(4)) null else cursor.getLong(4),
                        allDay = cursor.getInt(5) == 1,
                        rrule = cursor.getString(6)?.ifBlank { null },
                        duration = cursor.getString(7)?.ifBlank { null },
                    )
                }
            }
        return events
    }

    override fun insert(event: EventRecord): Boolean {
        val calendarId = writableCalendarId() ?: return false
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, event.title)
            put(Events.DESCRIPTION, event.description)
            put(Events.EVENT_LOCATION, event.location)
            put(Events.DTSTART, event.startMillis)
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(Events.UID_2445, event.uid)
            if (event.rrule != null) {
                // Recurring rows must carry DURATION, not DTEND.
                put(Events.RRULE, event.rrule)
                put(Events.DURATION, event.duration ?: derivedDuration(event))
            } else {
                // Non-recurring rows must carry DTEND; honor a DURATION-only event's real
                // span instead of defaulting (multi-day all-day events, review 2026-06-11).
                val end = event.endMillis
                    ?: event.duration?.let(Ics::durationToMillis)?.let { event.startMillis + it }
                    ?: (event.startMillis + DEFAULT_EVENT_MILLIS)
                put(Events.DTEND, end)
            }
        }
        return runCatching { resolver.insert(Events.CONTENT_URI, values) != null }.getOrDefault(false)
    }

    /** First writable calendar id, primary first; cached for the batch. */
    private fun writableCalendarId(): Long? = cachedCalendarId ?: runCatching {
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.CALENDAR_ACCESS_LEVEL}>=${Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null,
            "${Calendars.IS_PRIMARY} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()?.also { cachedCalendarId = it }

    private var cachedCalendarId: Long? = null

    private fun derivedDuration(event: EventRecord): String {
        val end = event.endMillis
        return if (end != null && end > event.startMillis) {
            "PT${(end - event.startMillis) / 1000}S"
        } else if (event.allDay) {
            "P1D"
        } else {
            "PT1H"
        }
    }

    private companion object {
        const val DEFAULT_EVENT_MILLIS = 3_600_000L
    }
}
