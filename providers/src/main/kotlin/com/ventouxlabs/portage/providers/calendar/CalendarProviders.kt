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

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ExportProvider
import java.io.InputStream
import java.io.OutputStream

/** Sender side: calendar events → ICS. Denied permission ⇒ unavailable, empty export. */
class CalendarExportProvider(private val store: CalendarStore) : ExportProvider {

    override val kind = ItemKind.CALENDAR_ICS
    override val displayName = "Calendar"
    override val group = "Schedule"

    override suspend fun available(): Boolean =
        runCatching { store.count() > 0 }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val events = runCatching { store.readAll() }.getOrDefault(emptyList())
        Ics.write(events, sink)
    }
}

/** Receiver side: ICS → events on the device's writable calendar, best-effort per event. */
class CalendarApplyProvider(private val store: CalendarStore) : ApplyProvider {

    override val kind = ItemKind.CALENDAR_ICS

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val parsed = Ics.parse(source)
        // #159: a degoogled phone commonly has ZERO calendars, so there is nothing for
        // Events.CALENDAR_ID to resolve to and every insert fails. Create a local, account-less
        // calendar to receive them. Guarded on a non-empty payload so an empty item never leaves
        // the user an empty calendar to clean up, and only when none exists — a device that
        // already has one must not silently gain a second. An unreadable store defaults to
        // "has one", which changes nothing on the device and lets the inserts report the truth.
        val hadCalendar = runCatching { store.hasWritableCalendar() }.getOrDefault(true)
        val createdLocal = parsed.events.isNotEmpty() && !hadCalendar &&
            runCatching { store.createLocalCalendar(LOCAL_CALENDAR_NAME) }.getOrDefault(false)

        var applied = 0
        var skipped = parsed.malformed
        for (event in parsed.events) {
            if (runCatching { store.insert(event) }.getOrDefault(false)) applied++ else skipped++
        }
        val status = if (parsed.events.isNotEmpty() && applied == 0) ItemStatus.WRITE_ERROR else ItemStatus.OK
        return ApplyOutcome(
            status,
            detail(applied, skipped, createdLocal, noTarget = !hadCalendar && !createdLocal),
        )
    }

    /**
     * The Done row's words. Two honesty rules, both learned from #159's original wording:
     *  - the no-calendar failure must NOT read as "worth sending again" — re-sending cannot help;
     *  - never claim events were "added to a new local calendar" when [applied] is 0. Creating the
     *    calendar and then failing every insert (permission revoked mid-apply, provider error) is
     *    rare but real, and reporting it as a success is the same lie in a new place.
     */
    private fun detail(applied: Int, skipped: Int, createdLocal: Boolean, noTarget: Boolean): String = when {
        noTarget -> "no calendar on this phone to add events to, and one couldn't be created"
        createdLocal && applied > 0 ->
            "applied $applied, skipped $skipped — added to a new local calendar \"$LOCAL_CALENDAR_NAME\""
        else -> "applied $applied, skipped $skipped"
    }

    companion object {
        /** Shown to the user on the review screen and again on Done — keep the two in step. */
        const val LOCAL_CALENDAR_NAME = "Imported"
    }
}
