/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.calllog

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.TransferScopedApplyProvider
import com.ventouxlabs.portage.providers.wire.JsonLines
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * One call-log entry. [type] uses the CallLog.Calls constants verbatim
 * (1=incoming, 2=outgoing, 3=missed, …) — passed through, never interpreted.
 */
@Serializable
data class CallRecord(
    val number: String,
    val type: Int,
    val dateMillis: Long,
    val durationSeconds: Long,
    val cachedName: String? = null,
)

/**
 * The ContentResolver seam for the call log. Reads MAY throw [SecurityException] when
 * READ_CALL_LOG is denied; [insert] returns false on failure (never throws).
 */
interface CallLogStore {
    fun count(): Int
    fun readAll(): List<CallRecord>
    fun insert(record: CallRecord): Boolean
}

/**
 * Retry ledger for write-only call-log apply. The receiver deliberately does not request
 * READ_CALL_LOG, so it cannot query the target provider for duplicates.
 */
interface CallLogImportJournal {
    fun contains(record: CallRecord): Boolean
    fun record(record: CallRecord)
    fun clear()

    object None : CallLogImportJournal {
        override fun contains(record: CallRecord) = false
        override fun record(record: CallRecord) = Unit
        override fun clear() = Unit
    }
}

/** Sender side: call log → JSON lines. Denied permission ⇒ unavailable, empty export. */
class CallLogExportProvider(private val store: CallLogStore) : ExportProvider {

    override val kind = ItemKind.CALL_LOG
    override val displayName = "Call history"
    override val group = "History"

    override suspend fun available(): Boolean =
        runCatching { store.count() > 0 }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val records = runCatching { store.readAll() }.getOrDefault(emptyList())
        JsonLines.writeTo(sink, records)
    }
}

/** Receiver side: JSON lines → call-log rows, best-effort per record. */
class CallLogApplyProvider(
    private val store: CallLogStore,
    private val journal: CallLogImportJournal = CallLogImportJournal.None,
) : ApplyProvider, TransferScopedApplyProvider {

    override val kind = ItemKind.CALL_LOG

    override fun beginTransfer() {
        runCatching { journal.clear() }
    }

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val parsed = JsonLines.readFrom<CallRecord>(source)
        var applied = 0
        var alreadyImported = 0
        var skipped = parsed.malformed
        for (record in parsed.records) {
            if (runCatching { journal.contains(record) }.getOrDefault(false)) {
                alreadyImported++
            } else if (runCatching { store.insert(record) }.getOrDefault(false)) {
                applied++
                // The call row is durable already; losing the retry fingerprint must not stop later
                // records or turn a successful provider write into an item-level failure.
                runCatching { journal.record(record) }
            } else {
                skipped++
            }
        }
        val status =
            if (parsed.records.isNotEmpty() && applied == 0 && alreadyImported == 0) {
                ItemStatus.WRITE_ERROR
            } else {
                ItemStatus.OK
            }
        return ApplyOutcome(status, "applied $applied, already imported $alreadyImported, skipped $skipped")
    }
}
