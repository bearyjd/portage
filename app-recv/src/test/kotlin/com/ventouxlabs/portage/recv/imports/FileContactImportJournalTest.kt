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
    }

    @Test
    fun `journal normalizes group order and case like provider dedup`() {
        val journal = FileContactImportJournal(temporaryFolder.newFile("contacts.sha256"))
        journal.record(ContactRecord(displayName = "Grouped", groupNames = listOf("Work", "Family")))

        assertThat(
            journal.contains(
                ContactRecord(displayName = "grouped", groupNames = listOf("family", "work")),
            ),
        ).isTrue()
    }
}
