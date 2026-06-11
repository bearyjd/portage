/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.calllog

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.CallLog.Calls

/**
 * Thin CallLog adapter behind [CallLogStore]. Reads propagate [SecurityException]
 * (providers degrade); writes return false on any failure. Restored rows are marked
 * already-seen so the import doesn't light up the missed-call badge.
 */
class AndroidCallLogStore(private val resolver: ContentResolver) : CallLogStore {

    override fun count(): Int =
        resolver.query(Calls.CONTENT_URI, arrayOf(Calls._ID), null, null, null)
            ?.use { it.count } ?: 0

    override fun readAll(): List<CallRecord> {
        val projection = arrayOf(Calls.NUMBER, Calls.TYPE, Calls.DATE, Calls.DURATION, Calls.CACHED_NAME)
        val records = mutableListOf<CallRecord>()
        resolver.query(Calls.CONTENT_URI, projection, null, null, "${Calls.DATE} ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                records += CallRecord(
                    number = cursor.getString(0).orEmpty(),
                    type = cursor.getInt(1),
                    dateMillis = cursor.getLong(2),
                    durationSeconds = cursor.getLong(3),
                    cachedName = cursor.getString(4)?.ifBlank { null },
                )
            }
        }
        return records
    }

    override fun insert(record: CallRecord): Boolean {
        val values = ContentValues().apply {
            put(Calls.NUMBER, record.number)
            put(Calls.TYPE, record.type)
            put(Calls.DATE, record.dateMillis)
            put(Calls.DURATION, record.durationSeconds)
            put(Calls.CACHED_NAME, record.cachedName)
            put(Calls.NEW, 0)
            put(Calls.IS_READ, 1)
        }
        return runCatching { resolver.insert(Calls.CONTENT_URI, values) != null }.getOrDefault(false)
    }
}
