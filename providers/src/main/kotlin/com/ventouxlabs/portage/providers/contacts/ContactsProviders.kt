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

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.TransferScopedApplyProvider
import java.io.InputStream
import java.io.OutputStream

/** Sender side: contacts → vCard 3.0. Denied permission ⇒ unavailable, empty export. */
class ContactsExportProvider(private val store: ContactsStore) : ExportProvider {

    override val kind = ItemKind.CONTACTS_VCF
    override val displayName = "Contacts"
    override val group = "People"

    override suspend fun available(): Boolean =
        runCatching { store.count() > 0 }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val records = runCatching { store.readAll() }.getOrDefault(emptyList())
        VCard3.write(records, sink)
    }
}

/** Retry ledger complementing provider read-back, which may lag behind a just-inserted raw contact. */
interface ContactImportJournal {
    fun contains(record: ContactRecord): Boolean
    fun record(record: ContactRecord)
    fun clear()

    object None : ContactImportJournal {
        override fun contains(record: ContactRecord) = false
        override fun record(record: ContactRecord) = Unit
        override fun clear() = Unit
    }
}

/** Receiver side: vCard 3.0 → local raw contacts, best-effort per card. */
class ContactsApplyProvider(
    private val store: ContactsStore,
    private val journal: ContactImportJournal = ContactImportJournal.None,
) : ApplyProvider, TransferScopedApplyProvider {

    override val kind = ItemKind.CONTACTS_VCF

    override fun beginTransfer() {
        runCatching { journal.clear() }
    }

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val parsed = VCard3.parse(source)
        var applied = 0
        var alreadyPresent = 0
        var skipped = parsed.malformed
        val existing = runCatching { store.readAll().mapTo(mutableSetOf(), ContactRecord::dedupKey) }
            .getOrDefault(mutableSetOf())
        for (record in parsed.records) {
            val key = record.dedupKey()
            if (key in existing || runCatching { journal.contains(record) }.getOrDefault(false)) {
                alreadyPresent++
            } else if (runCatching { store.insert(record) }.getOrDefault(false)) {
                applied++
                existing += key
                // The provider write already succeeded. Journal persistence is an idempotency aid,
                // so a full disk or other ledger failure must not discard progress or abort the item.
                runCatching { journal.record(record) }
            } else {
                skipped++
            }
        }
        val status =
            if (parsed.records.isNotEmpty() && applied == 0 && alreadyPresent == 0) {
                ItemStatus.WRITE_ERROR
            } else {
                ItemStatus.OK
            }
        return ApplyOutcome(status, "applied $applied, already present $alreadyPresent, skipped $skipped")
    }
}

/** Stable exact-record key: formatting/order differences normalize, materially different data does not. */
private fun ContactRecord.dedupKey(): String = listOf(
    displayName.trim().lowercase(),
    givenName.orEmpty().trim().lowercase(),
    familyName.orEmpty().trim().lowercase(),
    phones.map {
        "${it.value.filter(Char::isLetterOrDigit).lowercase()}:${it.type.uppercase()}"
    }.sorted().joinToString("|"),
    emails.map { "${it.value.trim().lowercase()}:${it.type.uppercase()}" }.sorted().joinToString("|"),
    postals.map { "${it.value.trim().lowercase()}:${it.type.uppercase()}" }.sorted().joinToString("|"),
    organization.orEmpty().trim().lowercase(),
    title.orEmpty().trim().lowercase(),
    note.orEmpty().trim(),
).joinToString("\u001f")
