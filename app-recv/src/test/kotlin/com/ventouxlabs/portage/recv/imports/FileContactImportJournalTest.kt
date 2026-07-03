/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.imports

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.providers.contacts.ContactRecord
import com.ventouxlabs.portage.providers.contacts.LabeledValue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class FileContactImportJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `journal distinguishes every material extended contact field`() {
        val journal = FileContactImportJournal(temporaryFolder.newFile("contacts.sha256"))
        val original = ContactRecord(
            displayName = "Same name",
            nickname = "One",
            birthday = "2000-01-01",
            websites = listOf(LabeledValue("https://one.example", "HOME")),
            groupNames = listOf("Friends"),
        )
        journal.record(original)

        assertThat(journal.contains(original)).isTrue()
        assertThat(journal.contains(original.copy(nickname = "Two"))).isFalse()
        assertThat(journal.contains(original.copy(birthday = "2001-01-01"))).isFalse()
        assertThat(
            journal.contains(
                original.copy(websites = listOf(LabeledValue("https://two.example", "HOME"))),
            ),
        ).isFalse()
        assertThat(journal.contains(original.copy(groupNames = listOf("Family")))).isFalse()
        assertThat(journal.contains(original.copy(middleName = "Different"))).isFalse()
        assertThat(journal.contains(original.copy(phoneticFamilyName = "ディファレント"))).isFalse()
    }

    @Test
    fun `journal normalizes group order and case like provider dedup`() {
        val journal = FileContactImportJournal(temporaryFolder.newFile("contacts.sha256"))
        journal.record(ContactRecord(displayName = "Grouped", groupNames = listOf("Work", "Family")))

        assertThat(
            journal.contains(
                ContactRecord(
                    displayName = "grouped",
                    groupNames = listOf(" family ", "", "work", "FAMILY"),
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `journal recognizes fingerprints written by the legacy format`() {
        val record = ContactRecord(
            displayName = "Legacy",
            phones = listOf(LabeledValue("+15551234", "CELL")),
            note = "existing retry",
        )
        val canonical = listOf(
            record.displayName,
            record.givenName.orEmpty(),
            record.familyName.orEmpty(),
            record.phones.sortedBy { "${it.value}:${it.type}" }.joinToString(),
            record.emails.sortedBy { "${it.value}:${it.type}" }.joinToString(),
            record.postals.sortedBy { "${it.value}:${it.type}" }.joinToString(),
            record.organization.orEmpty(),
            record.title.orEmpty(),
            record.note.orEmpty(),
        ).joinToString("\u001f")
        val legacy = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val file = temporaryFolder.newFile("contacts.sha256").apply { writeText("$legacy\n") }

        assertThat(FileContactImportJournal(file).contains(record)).isTrue()
    }

    @Test
    fun `journal does not conflate group names containing delimiters`() {
        val journal = FileContactImportJournal(temporaryFolder.newFile("contacts.sha256"))
        journal.record(ContactRecord(displayName = "Grouped", groupNames = listOf("a|b")))

        assertThat(
            journal.contains(ContactRecord(displayName = "Grouped", groupNames = listOf("a", "b"))),
        ).isFalse()
    }
}
