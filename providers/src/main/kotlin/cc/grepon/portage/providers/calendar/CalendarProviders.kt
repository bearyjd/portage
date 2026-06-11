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

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProvider
import cc.grepon.portage.providers.ExportProvider
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
        var applied = 0
        var skipped = parsed.malformed
        for (event in parsed.events) {
            if (runCatching { store.insert(event) }.getOrDefault(false)) applied++ else skipped++
        }
        val status = if (parsed.events.isNotEmpty() && applied == 0) ItemStatus.WRITE_ERROR else ItemStatus.OK
        return ApplyOutcome(status, "applied $applied, skipped $skipped")
    }
}
