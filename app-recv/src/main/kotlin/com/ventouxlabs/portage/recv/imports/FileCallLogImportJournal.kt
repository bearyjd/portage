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

import com.ventouxlabs.portage.providers.calllog.CallLogImportJournal
import com.ventouxlabs.portage.providers.calllog.CallRecord
import java.io.File
import java.security.MessageDigest

/**
 * App-private retry journal for call history. Each line is a SHA-256 fingerprint, never raw call
 * metadata. Entries are appended only after the provider accepted an insert.
 */
class FileCallLogImportJournal(private val file: File) : CallLogImportJournal {
    private val fingerprints: MutableSet<String> by lazy {
        runCatching {
            if (file.isFile) file.readLines().filterTo(mutableSetOf(), FINGERPRINT::matches)
            else mutableSetOf()
        }.getOrDefault(mutableSetOf())
    }

    @Synchronized
    override fun contains(record: CallRecord): Boolean = fingerprint(record) in fingerprints

    @Synchronized
    override fun record(record: CallRecord) {
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

    private fun fingerprint(record: CallRecord): String {
        val canonical = listOf(
            record.number,
            record.type.toString(),
            record.dateMillis.toString(),
            record.durationSeconds.toString(),
            record.cachedName.orEmpty(),
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}
