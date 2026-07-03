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

import com.ventouxlabs.portage.providers.contacts.ContactImportJournal
import com.ventouxlabs.portage.providers.contacts.ContactRecord
import com.ventouxlabs.portage.providers.contacts.canonicalImportKey
import java.io.File
import java.security.MessageDigest

/** App-private SHA-256 retry ledger; raw contact data is never persisted by Portage. */
class FileContactImportJournal(private val file: File) : ContactImportJournal {
    private val fingerprints: MutableSet<String> by lazy {
        runCatching {
            if (file.isFile) file.readLines().filterTo(mutableSetOf(), FINGERPRINT::matches)
            else mutableSetOf()
        }.getOrDefault(mutableSetOf())
    }

    @Synchronized
    override fun contains(record: ContactRecord) =
        fingerprint(record) in fingerprints || legacyFingerprint(record) in fingerprints

    @Synchronized
    override fun record(record: ContactRecord) {
        val value = fingerprint(record)
        if (!fingerprints.add(value)) return
        file.parentFile?.mkdirs()
        file.appendText("$value\n")
    }

    @Synchronized
    override fun clear() {
        fingerprints.clear()
        file.delete()
    }

    private fun fingerprint(record: ContactRecord): String {
        return sha256(record.canonicalImportKey())
    }

    /**
     * Compatibility with fingerprints written before the canonical provider/journal identity was
     * shared. New records use only [fingerprint]; this check can disappear after a format migration.
     */
    private fun legacyFingerprint(record: ContactRecord): String {
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
        return sha256(canonical)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}
