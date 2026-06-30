/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.mms

import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.sms.SmsRoleGateway
import com.ventouxlabs.portage.providers.wire.JsonLines
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

private class FakeMmsStore(
    private val messages: MutableList<MmsRecord> = mutableListOf(),
    var throwOnRead: Boolean = false,
) : MmsStore {
    val inserted = mutableListOf<MmsRecord>()

    override fun count(): Int {
        if (throwOnRead) throw SecurityException("READ_SMS denied")
        return messages.size
    }

    override fun writeAllTo(sink: OutputStream, maxBytes: Long): MmsExportSummary {
        if (throwOnRead) throw SecurityException("READ_SMS denied")
        return MmsWire.writeBounded(messages.asSequence(), sink, maxBytes)
    }

    override fun insert(record: MmsRecord): Boolean {
        inserted += record
        return true
    }
}

private class FakeRoleGateway(var selfIsDefault: Boolean = false) : SmsRoleGateway {
    override fun isSelfDefault(): Boolean = selfIsDefault
    override fun currentDefault(): String? = "com.example.messages"
    override fun launchRestore(priorHolderPackage: String?): Boolean = true
}

class MmsProvidersTest {

    private val record = MmsRecord(
        dateSeconds = 1_750_000_000,
        box = 1,
        subject = "photo",
        addresses = listOf(MmsAddressRecord("+15551234567", type = 137)),
        parts = listOf(
            MmsPartRecord(contentType = "text/plain", text = "hello"),
            MmsPartRecord(contentType = "image/jpeg", dataBase64 = "/9j/4AAQ"),
        ),
    )

    private suspend fun payloadOf(vararg records: MmsRecord): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        MmsExportProvider(FakeMmsStore(records.toMutableList())).exportTo(out)
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `available follows readability and content`() = runTest {
        assertThat(MmsExportProvider(FakeMmsStore()).available()).isFalse()
        assertThat(MmsExportProvider(FakeMmsStore(mutableListOf(record))).available()).isTrue()
        assertThat(MmsExportProvider(FakeMmsStore(throwOnRead = true)).available()).isFalse()
    }

    @Test
    fun `apply refuses to write while not the default SMS app`() = runTest {
        val store = FakeMmsStore()
        val provider = MmsApplyProvider(store, FakeRoleGateway(selfIsDefault = false))

        val outcome = provider.apply(payloadOf(record))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.inserted).isEmpty()
    }

    @Test
    fun `apply round trips mms while holding the default role`() = runTest {
        val store = FakeMmsStore()
        val provider = MmsApplyProvider(store, FakeRoleGateway(selfIsDefault = true))

        val outcome = provider.apply(payloadOf(record))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(record)
    }

    @Test
    fun `export skips records that would exceed the receiver item cap`() = runTest {
        val first = record.copy(subject = "first")
        val second = record.copy(subject = "second")
        val firstLineSize = MmsWire.encodedLine(first).size.toLong()
        val out = ByteArrayOutputStream()

        val summary = FakeMmsStore(mutableListOf(first, second)).writeAllTo(out, maxBytes = firstLineSize)
        val parsed = JsonLines.readFrom<MmsRecord>(ByteArrayInputStream(out.toByteArray()))

        assertThat(summary.exported).isEqualTo(1)
        assertThat(summary.skipped).isEqualTo(1)
        assertThat(summary.bytes).isEqualTo(firstLineSize)
        assertThat(parsed.records).containsExactly(first)
    }
}
