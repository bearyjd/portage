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
import cc.grepon.portage.providers.ApplyOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

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

    @Test
    fun `recordPriorDefault and relinquishTo delegate to the role gateway`() = runTest {
        val gateway = FakeRoleGateway(default = "org.fossify.messages")
        val provider = SmsApplyProvider(FakeSmsStore(), gateway)

        assertThat(provider.recordPriorDefault()).isEqualTo("org.fossify.messages")
        provider.relinquishTo("org.fossify.messages")
        assertThat(gateway.restoreCalls).containsExactly("org.fossify.messages")
    }

    // --- The handoff coordinator: teardown is REQUIRED, not optional (DEVILS_ADVOCATE Q4). ---

    @Test
    fun `handoff relinquishes to the recorded prior holder after a successful apply`() = runTest {
        val relinquished = mutableListOf<String?>()

        val outcome = SmsHandoff.run(
            recordPrior = { "org.fossify.messages" },
            acquire = { true },
            apply = { ApplyOutcome(ItemStatus.OK, "applied 2, skipped 0") },
            relinquish = { relinquished += it },
        )

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(relinquished).containsExactly("org.fossify.messages")
    }

    @Test
    fun `handoff relinquishes even when apply throws`() = runTest {
        val relinquished = mutableListOf<String?>()

        val thrown = runCatching {
            SmsHandoff.run(
                recordPrior = { "org.fossify.messages" },
                acquire = { true },
                apply = { throw IOException("mid-apply failure") },
                relinquish = { relinquished += it },
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IOException::class.java)
        assertThat(relinquished).containsExactly("org.fossify.messages")
    }

    @Test
    fun `handoff relinquishes when apply reports a failure status`() = runTest {
        val relinquished = mutableListOf<String?>()

        val outcome = SmsHandoff.run(
            recordPrior = { "org.fossify.messages" },
            acquire = { true },
            apply = { ApplyOutcome(ItemStatus.WRITE_ERROR, "nothing applied") },
            relinquish = { relinquished += it },
        )

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(relinquished).containsExactly("org.fossify.messages")
    }

    @Test
    fun `handoff declined by the user skips apply and has no role to give back`() = runTest {
        var applied = false
        val relinquished = mutableListOf<String?>()

        val outcome = SmsHandoff.run(
            recordPrior = { "org.fossify.messages" },
            acquire = { false },
            apply = { applied = true; ApplyOutcome(ItemStatus.OK) },
            relinquish = { relinquished += it },
        )

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(applied).isFalse()
        assertThat(relinquished).isEmpty()
    }
}
