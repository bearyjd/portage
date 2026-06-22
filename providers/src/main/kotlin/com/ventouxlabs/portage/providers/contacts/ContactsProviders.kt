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

/** Receiver side: vCard 3.0 → local raw contacts, best-effort per card. */
class ContactsApplyProvider(private val store: ContactsStore) : ApplyProvider {

    override val kind = ItemKind.CONTACTS_VCF

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val parsed = VCard3.parse(source)
        var applied = 0
        var skipped = parsed.malformed
        for (record in parsed.records) {
            if (runCatching { store.insert(record) }.getOrDefault(false)) applied++ else skipped++
        }
        val status = if (parsed.records.isNotEmpty() && applied == 0) ItemStatus.WRITE_ERROR else ItemStatus.OK
        return ApplyOutcome(status, "applied $applied, skipped $skipped")
    }
}
