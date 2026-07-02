/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.contacts

import com.ventouxlabs.portage.model.ItemStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Hand-written fake at the ContentResolver seam (no Android on the JVM test classpath). */
private class FakeContactsStore(
    private val contacts: MutableList<ContactRecord> = mutableListOf(),
    var throwOnRead: Boolean = false,
    var failInsertsFor: Set<String> = emptySet(),
) : ContactsStore {
    val inserted = mutableListOf<ContactRecord>()

    override fun count(): Int {
        if (throwOnRead) throw SecurityException("READ_CONTACTS denied")
        return contacts.size
    }

    override fun readAll(): List<ContactRecord> {
        if (throwOnRead) throw SecurityException("READ_CONTACTS denied")
        return contacts.toList()
    }

    override fun insert(record: ContactRecord): Boolean {
        if (record.displayName in failInsertsFor) return false
        inserted += record
        contacts += record
        return true
    }
}

class ContactsProvidersTest {

    private val ada = ContactRecord(displayName = "Ada", phones = listOf(LabeledValue("+1555", "CELL")))
    private val bob = ContactRecord(displayName = "Bob")
    private val favorite = ContactRecord(displayName = "Favorite", starred = true)

    @Test
    fun `available is true only when readable and non-empty`() = runTest {
        assertThat(ContactsExportProvider(FakeContactsStore()).available()).isFalse()
        assertThat(ContactsExportProvider(FakeContactsStore(mutableListOf(ada))).available()).isTrue()
    }

    @Test
    fun `available degrades to false when permission is denied`() = runTest {
        val provider = ContactsExportProvider(FakeContactsStore(throwOnRead = true))
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `exportTo streams the store as vCards`() = runTest {
        val out = ByteArrayOutputStream()
        ContactsExportProvider(FakeContactsStore(mutableListOf(ada, bob, favorite))).exportTo(out)

        val back = VCard3.parse(ByteArrayInputStream(out.toByteArray()))
        assertThat(back.records).containsExactly(ada, bob, favorite).inOrder()
    }

    @Test
    fun `exportTo writes an empty payload instead of crashing when denied`() = runTest {
        val out = ByteArrayOutputStream()
        ContactsExportProvider(FakeContactsStore(throwOnRead = true)).exportTo(out)
        assertThat(out.size()).isEqualTo(0)
    }

    @Test
    fun `apply inserts every parsed contact and reports counts`() = runTest {
        val store = FakeContactsStore()
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(ada, bob), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(ada, bob).inOrder()
        assertThat(outcome.detail).contains("applied 2")
    }

    @Test
    fun `a failed insert is skipped, not fatal`() = runTest {
        val store = FakeContactsStore(failInsertsFor = setOf("Ada"))
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(ada, bob), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(bob)
        assertThat(outcome.detail).contains("applied 1")
        assertThat(outcome.detail).contains("skipped 1")
    }

    @Test
    fun `journal write failure does not abort successful contact inserts`() = runTest {
        val journal = object : ContactImportJournal {
            override fun contains(record: ContactRecord) = false
            override fun record(record: ContactRecord) = throw java.io.IOException("disk full")
            override fun clear() = Unit
        }
        val store = FakeContactsStore()
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(ada, bob), it) }

        val outcome = ContactsApplyProvider(store, journal)
            .apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(ada, bob).inOrder()
        assertThat(outcome.detail).contains("applied 2")
    }

    @Test
    fun `apply is idempotent for exact contacts already on the target`() = runTest {
        val store = FakeContactsStore()
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(ada, bob), it) }.toByteArray()

        val first = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload))
        val second = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload))

        assertThat(first.status).isEqualTo(ItemStatus.OK)
        assertThat(second.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(ada, bob).inOrder()
        assertThat(second.detail).contains("already present 2")
    }

    @Test
    fun `dedup normalizes phone formatting and field order`() = runTest {
        val formatted = ada.copy(phones = listOf(LabeledValue("+1 (555)", "cell")))
        val store = FakeContactsStore(mutableListOf(formatted))
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(ada), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).isEmpty()
        assertThat(outcome.detail).contains("already present 1")
    }

    @Test
    fun `dedup preserves distinct vanity phone numbers`() = runTest {
        val flowers = ContactRecord(
            displayName = "Florist",
            phones = listOf(LabeledValue("1-800-FLOWERS", "WORK")),
        )
        val flowing = flowers.copy(phones = listOf(LabeledValue("1-800-FLOWING", "WORK")))
        val store = FakeContactsStore(mutableListOf(flowers))
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(flowing), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(flowing)
        assertThat(outcome.detail).contains("applied 1")
    }

    @Test
    fun `dedup does not duplicate an existing contact solely to change starred state`() = runTest {
        val unstarred = favorite.copy(starred = false)
        val store = FakeContactsStore(mutableListOf(unstarred))
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(favorite), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).isEmpty()
        assertThat(outcome.detail).contains("already present 1")
    }

    @Test
    fun `dedup does not duplicate an existing contact solely to add a photo`() = runTest {
        val photo = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val pictured = ada.copy(photoBase64 = photo)
        val store = FakeContactsStore(mutableListOf(ada))
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(pictured), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).isEmpty()
        assertThat(outcome.detail).contains("already present 1")
    }

    @Test
    fun `dedup preserves contacts with distinct birthday nickname or website details`() = runTest {
        val detailed = ada.copy(
            nickname = "Countess",
            birthday = "1815-12-10",
            websites = listOf(LabeledValue("https://ada.example", "HOME")),
        )
        val store = FakeContactsStore(mutableListOf(ada))
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(detailed), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(detailed)
    }

    @Test
    fun `dedup preserves contacts with distinct group memberships`() = runTest {
        val grouped = ada.copy(groupNames = listOf("Family"))
        val store = FakeContactsStore(mutableListOf(ada))
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(grouped), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).containsExactly(grouped)
    }

    @Test
    fun `dedup normalizes blank duplicate case and whitespace group names`() = runTest {
        val existing = ada.copy(groupNames = listOf("Family", "Work"))
        val equivalent = ada.copy(groupNames = listOf(" work ", "", "FAMILY", "family"))
        val store = FakeContactsStore(mutableListOf(existing))
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(equivalent), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.inserted).isEmpty()
        assertThat(outcome.detail).contains("already present 1")
    }

    @Test
    fun `nothing applied from a non-empty payload is a WRITE_ERROR`() = runTest {
        val store = FakeContactsStore(failInsertsFor = setOf("Ada", "Bob"))
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(ada, bob), it) }

        val outcome = ContactsApplyProvider(store).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
    }

    @Test
    fun `an empty payload applies cleanly as zero records`() = runTest {
        val outcome = ContactsApplyProvider(FakeContactsStore()).apply(ByteArrayInputStream(ByteArray(0)))
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
    }
}
