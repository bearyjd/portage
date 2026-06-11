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

import cc.grepon.portage.providers.text.RfcText
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Parsed payload: events plus how many VEVENTs were unusable (per-record resilience). */
data class IcsParseResult(val events: List<EventRecord>, val malformed: Int)

/**
 * iCalendar (RFC 5545) reader/writer for the `calendar.ics` item kind. Times are written
 * in UTC (`...Z`) or DATE form for all-day events. The parser is lenient: unknown
 * properties/components are ignored; a VEVENT missing SUMMARY or a parseable DTSTART is
 * counted malformed and skipped, never fatal (PROTOCOL.md §5). A `TZID=` local time is
 * read best-effort as UTC — our own writer never emits one.
 */
object Ics {

    private const val CRLF = "\r\n"
    private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE

    fun write(events: List<EventRecord>, sink: OutputStream) {
        val writer = sink.bufferedWriter(Charsets.UTF_8)
        writer.write(buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Grepon Labs//portage//EN")
            for (event in events) appendEvent(event)
            appendLine("END:VCALENDAR")
        })
        writer.flush()
    }

    private fun StringBuilder.appendEvent(event: EventRecord) {
        appendLine("BEGIN:VEVENT")
        event.uid?.let { appendLine("UID:${RfcText.escape(it)}") }
        appendLine("SUMMARY:${RfcText.escape(event.title)}")
        appendLine(dateTimeProperty("DTSTART", event.startMillis, event.allDay))
        event.endMillis?.let { appendLine(dateTimeProperty("DTEND", it, event.allDay)) }
        event.duration?.let { appendLine("DURATION:$it") }
        event.rrule?.let { appendLine("RRULE:$it") }
        event.description?.let { appendLine("DESCRIPTION:${RfcText.escape(it)}") }
        event.location?.let { appendLine("LOCATION:${RfcText.escape(it)}") }
        appendLine("END:VEVENT")
    }

    private fun StringBuilder.appendLine(line: String) {
        append(RfcText.fold(line))
        append(CRLF)
    }

    private fun dateTimeProperty(name: String, millis: Long, allDay: Boolean): String {
        // NOTE: atZone(...).toLocalDate() not LocalDate.ofInstant(...) — the latter is a
        // Java 9 API that only reaches Android at API 34; minSdk is 31.
        val utc = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        return if (allDay) {
            "$name;VALUE=DATE:${DATE_FORMAT.format(utc.toLocalDate())}"
        } else {
            "$name:${UTC_FORMAT.format(utc.toLocalDateTime())}Z"
        }
    }

    fun parse(source: InputStream): IcsParseResult {
        val physical = source.bufferedReader(Charsets.UTF_8).readLines().map { it.trimEnd('\r') }
        val logical = RfcText.unfold(physical)

        val events = mutableListOf<EventRecord>()
        var malformed = 0
        var event: EventBuilder? = null

        for (line in logical) {
            val upper = line.uppercase()
            when {
                upper == "BEGIN:VEVENT" -> event = EventBuilder()
                upper == "END:VEVENT" -> {
                    event?.let { finished ->
                        finished.build()?.let { events += it } ?: malformed++
                    }
                    event = null
                }
                else -> event?.acceptProperty(line)
            }
        }
        return IcsParseResult(events, malformed)
    }

    /**
     * RFC 5545 DURATION → millis. `java.time.Duration` covers the `PnDTnHnMnS` forms;
     * the week form (`PnW`) is special-cased because java.time rejects it. Null on garbage.
     */
    fun durationToMillis(value: String): Long? {
        val v = value.trim().uppercase()
        if (v.isEmpty()) return null
        Regex("""P(\d+)W""").matchEntire(v)?.let {
            return it.groupValues[1].toLongOrNull()?.times(7 * 86_400_000L)
        }
        return runCatching { java.time.Duration.parse(v).toMillis() }.getOrNull()
    }

    /** Parse a DTSTART/DTEND value: `yyyyMMdd'T'HHmmss[Z]` or bare `yyyyMMdd`. Null if neither. */
    private fun parseDateTime(value: String): Long? = runCatching {
        val v = value.removeSuffix("Z")
        when (v.length) {
            8 -> LocalDate.parse(v, DATE_FORMAT).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            15 -> LocalDateTime.parse(v, UTC_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
            else -> null
        }
    }.getOrNull()

    private class EventBuilder {
        var uid: String? = null
        var title: String? = null
        var description: String? = null
        var location: String? = null
        var startMillis: Long? = null
        var endMillis: Long? = null
        var allDay = false
        var rrule: String? = null
        var duration: String? = null
        var badDate = false

        fun acceptProperty(line: String) {
            val colon = line.indexOf(':')
            if (colon <= 0) return
            val left = line.substring(0, colon)
            val rawValue = line.substring(colon + 1)
            val nameAndParams = left.split(';')
            val name = nameAndParams[0].uppercase()
            val isDateValue = nameAndParams.drop(1).any { it.uppercase() == "VALUE=DATE" }

            when (name) {
                "UID" -> uid = RfcText.unescape(rawValue).ifEmpty { null }
                "SUMMARY" -> title = RfcText.unescape(rawValue)
                "DESCRIPTION" -> description = RfcText.unescape(rawValue).ifEmpty { null }
                "LOCATION" -> location = RfcText.unescape(rawValue).ifEmpty { null }
                "RRULE" -> rrule = rawValue.ifEmpty { null }
                "DURATION" -> duration = rawValue.ifEmpty { null }
                "DTSTART" -> {
                    allDay = isDateValue
                    startMillis = parseDateTime(rawValue)
                    if (startMillis == null) badDate = true
                }
                "DTEND" -> {
                    endMillis = parseDateTime(rawValue)
                    if (endMillis == null) badDate = true
                }
                else -> Unit // unknown property — ignore (forward compat)
            }
        }

        /** Usable = non-blank SUMMARY + parseable DTSTART and no broken date field. */
        fun build(): EventRecord? {
            val summary = title?.takeIf { it.isNotBlank() } ?: return null
            val start = startMillis ?: return null
            if (badDate) return null
            return EventRecord(uid, summary, description, location, start, endMillis, allDay, rrule, duration)
        }
    }
}
