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

import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.wire.JsonLines
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private class FakeCallLogStore(
    private val calls: MutableList<CallRecord> = mutableListOf(),
    var throwOnRead: Boolean = false,
    var rejectInserts: Boolean = false,
) : CallLogStore {
    val inserted = mutableListOf<CallRecord>()

    override fun count(): Int {
        if (throwOnRead) throw SecurityException("READ_CALL_LOG denied")
        return calls.size
    }

    override fun readAll(): List<CallRecord> {
        if (throwOnRead) throw SecurityException("READ_CALL_LOG denied")
        return calls.toList()
    }

    override fun insert(record: CallRecord): Boolean {
        if (rejectInserts) return false
        inserted += record
        return true
    }
}

private class FakeCallLogJournal : CallLogImportJournal {
    private val records = mutableSetOf<CallRecord>()
    override fun contains(record: CallRecord) = record in records
    override fun record(record: CallRecord) {
        records += record
    }
    override fun clear() = records.clear()
}

class CallLogProvidersTest {

    private val incoming = CallRecord("+15551234567", type = 1, dateMillis = 1_750_000_000_000, durationSeconds = 61)
    private val missed = CallRecord("+15559876543", type = 3, dateMillis = 1_750_000_100_000, durationSeconds = 0, cachedName = "Bob")

    @Test
    fun `available follows readability and content`() = runTest {
        assertThat(CallLogExportProvider(FakeCallLogStore()).available()).isFalse()
        assertThat(CallLogExportProvider(FakeCallLogStore(mutableListOf(incoming))).available()).isTrue()
        assertThat(CallLogExportProvider(FakeCallLogStore(throwOnRead = true)).available()).isFalse()
    }

    @Test
    fun `export and apply round trip the call history`() = runTest {
        val out = ByteArrayOutputStream()
        CallLogExportProvider(FakeCallLogStore(mutableListOf(incoming, missed))).exportTo(out)

        val store = FakeCallLogStore()
        val outcome = CallLogApplyProvider(store).apply(ByteArrayInputStream(out.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(incoming, missed).inOrder()
    }

    @Test
    fun `denied read permission exports an empty payload`() = runTest {
        val out = ByteArrayOutputStream()
        CallLogExportProvider(FakeCallLogStore(throwOnRead = true)).exportTo(out)
        assertThat(out.size()).isEqualTo(0)
    }

    @Test
    fun `a corrupt line is skipped and counted, not fatal`() = runTest {
        val good = JsonLines.format.encodeToString(CallRecord.serializer(), incoming)
        val payload = "$good\n{broken\n".toByteArray(Charsets.UTF_8)

        val store = FakeCallLogStore()
        val outcome = CallLogApplyProvider(store).apply(ByteArrayInputStream(payload))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(incoming)
        assertThat(outcome.detail).contains("skipped 1")
    }

    @Test
    fun `all inserts rejected is a WRITE_ERROR`() = runTest {
        val out = ByteArrayOutputStream()
        CallLogExportProvider(FakeCallLogStore(mutableListOf(incoming))).exportTo(out)

        val outcome = CallLogApplyProvider(FakeCallLogStore(rejectInserts = true))
            .apply(ByteArrayInputStream(out.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
    }

    @Test
    fun `journal makes an exact call-log retry idempotent without read permission`() = runTest {
        val out = ByteArrayOutputStream()
        CallLogExportProvider(FakeCallLogStore(mutableListOf(incoming, missed))).exportTo(out)
        val payload = out.toByteArray()
        val store = FakeCallLogStore(throwOnRead = true)
        val journal = FakeCallLogJournal()

        val first = CallLogApplyProvider(store, journal).apply(ByteArrayInputStream(payload))
        val second = CallLogApplyProvider(store, journal).apply(ByteArrayInputStream(payload))

        assertThat(first.status).isEqualTo(ItemStatus.OK)
        assertThat(second.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(incoming, missed).inOrder()
        assertThat(second.detail).contains("already imported 2")
    }

    @Test
    fun `journal write failure does not abort successful call-log inserts`() = runTest {
        val out = ByteArrayOutputStream()
        CallLogExportProvider(FakeCallLogStore(mutableListOf(incoming, missed))).exportTo(out)
        val journal = object : CallLogImportJournal {
            override fun contains(record: CallRecord) = false
            override fun record(record: CallRecord) = throw java.io.IOException("disk full")
            override fun clear() = Unit
        }
        val store = FakeCallLogStore(throwOnRead = true)

        val outcome = CallLogApplyProvider(store, journal)
            .apply(ByteArrayInputStream(out.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(incoming, missed).inOrder()
        assertThat(outcome.detail).contains("applied 2")
    }

    @Test
    fun `a new transfer clears call-log retry history`() = runTest {
        val out = ByteArrayOutputStream()
        CallLogExportProvider(FakeCallLogStore(mutableListOf(incoming))).exportTo(out)
        val payload = out.toByteArray()
        val store = FakeCallLogStore(throwOnRead = true)
        val provider = CallLogApplyProvider(store, FakeCallLogJournal())

        provider.beginTransfer()
        provider.apply(ByteArrayInputStream(payload))
        provider.apply(ByteArrayInputStream(payload))
        provider.beginTransfer()
        provider.apply(ByteArrayInputStream(payload))

        assertThat(store.inserted).containsExactly(incoming, incoming).inOrder()
    }

    @Test
    fun `failed call-log inserts are not journaled and retry later`() = runTest {
        val out = ByteArrayOutputStream()
        CallLogExportProvider(FakeCallLogStore(mutableListOf(incoming))).exportTo(out)
        val payload = out.toByteArray()
        val store = FakeCallLogStore(rejectInserts = true)
        val journal = FakeCallLogJournal()

        assertThat(CallLogApplyProvider(store, journal).apply(ByteArrayInputStream(payload)).status)
            .isEqualTo(ItemStatus.WRITE_ERROR)
        store.rejectInserts = false
        assertThat(CallLogApplyProvider(store, journal).apply(ByteArrayInputStream(payload)).status)
            .isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(incoming)
    }
}
