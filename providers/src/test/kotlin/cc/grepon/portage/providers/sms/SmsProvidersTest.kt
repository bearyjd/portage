/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.sms

import cc.grepon.portage.model.ItemStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private class FakeSmsStore(
    private val messages: MutableList<SmsRecord> = mutableListOf(),
    var throwOnRead: Boolean = false,
) : SmsStore {
    val inserted = mutableListOf<SmsRecord>()

    override fun count(): Int {
        if (throwOnRead) throw SecurityException("READ_SMS denied")
        return messages.size
    }

    override fun readAll(): List<SmsRecord> {
        if (throwOnRead) throw SecurityException("READ_SMS denied")
        return messages.toList()
    }

    override fun insert(record: SmsRecord): Boolean {
        inserted += record
        return true
    }
}

private class FakeRoleGateway(
    var selfIsDefault: Boolean = false,
    var default: String? = "com.example.messages",
) : SmsRoleGateway {
    val restoreCalls = mutableListOf<String?>()

    override fun isSelfDefault(): Boolean = selfIsDefault
    override fun currentDefault(): String? = default
    override fun launchRestore(priorHolderPackage: String?): Boolean {
        restoreCalls += priorHolderPackage
        return true
    }
}

class SmsProvidersTest {

    private val inbox = SmsRecord("+15551234567", "hello", dateMillis = 1_750_000_000_000, type = 1, read = true)
    private val sent = SmsRecord("+15551234567", "hi back", dateMillis = 1_750_000_060_000, type = 2, read = true)

    private suspend fun payloadOf(vararg records: SmsRecord): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        SmsExportProvider(FakeSmsStore(records.toMutableList())).exportTo(out)
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `available follows readability and content`() = runTest {
        assertThat(SmsExportProvider(FakeSmsStore()).available()).isFalse()
        assertThat(SmsExportProvider(FakeSmsStore(mutableListOf(inbox))).available()).isTrue()
        assertThat(SmsExportProvider(FakeSmsStore(throwOnRead = true)).available()).isFalse()
    }

    @Test
    fun `apply refuses to write while not the default SMS app`() = runTest {
        val store = FakeSmsStore()
        val provider = SmsApplyProvider(store, FakeRoleGateway(selfIsDefault = false))

        val outcome = provider.apply(payloadOf(inbox))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.inserted).isEmpty()
    }

    @Test
    fun `apply round trips messages while holding the default role`() = runTest {
        val store = FakeSmsStore()
        val provider = SmsApplyProvider(store, FakeRoleGateway(selfIsDefault = true))

        val outcome = provider.apply(payloadOf(inbox, sent))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(inbox, sent).inOrder()
    }
}
