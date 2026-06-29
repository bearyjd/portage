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

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/**
 * Thin ContactsContract adapter behind [ContactsStore]. Deliberately mechanical — all
 * carry/parse logic lives in the JVM-tested [VCard3]/[ContactsProviders] layer. Reads
 * propagate [SecurityException] (providers degrade); writes return false on any failure.
 */
class AndroidContactsStore(private val resolver: ContentResolver) : ContactsStore {

    override fun count(): Int =
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            null, null, null,
        )?.use { it.count } ?: 0

    override fun readAll(): List<ContactRecord> {
        val builders = linkedMapOf<Long, MutableContact>()
        val projection = arrayOf(
            Data.RAW_CONTACT_ID, Data.DISPLAY_NAME_PRIMARY, Data.MIMETYPE,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4,
        )
        val mimes = arrayOf(
            StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE,
            StructuredPostal.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE,
        )
        val selection = "${Data.MIMETYPE} IN (${mimes.joinToString(",") { "?" }})"

        // CONTACT_ID is Android's aggregate identity and can union fields from several raw
        // contacts. Import deduplication needs the original per-source record, so group Data rows
        // by RAW_CONTACT_ID and derive its display name from its own StructuredName row.
        resolver.query(Data.CONTENT_URI, projection, selection, mimes, Data.RAW_CONTACT_ID)?.use { cursor ->
            while (cursor.moveToNext()) {
                val rawContactId = cursor.getLong(0)
                // Some constituent raw contacts contain useful phone/email rows but no name row.
                // Seed those from the aggregate display name so they remain exportable; an own
                // StructuredName below replaces the fallback without merging any other fields.
                val contact = builders.getOrPut(rawContactId) {
                    MutableContact(displayName = cursor.getString(1).orEmpty())
                }
                val data1 = cursor.getString(3)
                when (cursor.getString(2)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        contact.displayName = data1.orEmpty()
                        contact.givenName = cursor.getString(4)?.ifBlank { null }
                        contact.familyName = cursor.getString(5)?.ifBlank { null }
                    }
                    Phone.CONTENT_ITEM_TYPE -> data1?.let {
                        contact.phones += LabeledValue(it, phoneTypeName(cursor.getInt(4)))
                    }
                    Email.CONTENT_ITEM_TYPE -> data1?.let {
                        contact.emails += LabeledValue(it, contactTypeName(cursor.getInt(4)))
                    }
                    StructuredPostal.CONTENT_ITEM_TYPE -> data1?.let {
                        contact.postals += LabeledValue(it, contactTypeName(cursor.getInt(4)))
                    }
                    Organization.CONTENT_ITEM_TYPE -> {
                        contact.organization = data1?.ifBlank { null }
                        contact.title = cursor.getString(6)?.ifBlank { null }
                    }
                    Note.CONTENT_ITEM_TYPE -> contact.note = data1?.ifBlank { null }
                }
            }
        }
        return builders.values.filter { it.displayName.isNotBlank() }.map { it.toRecord() }
    }

    override fun insert(record: ContactRecord): Boolean {
        val ops = arrayListOf(
            // Null account = device-local contact BY DESIGN: portage is no-cloud, and a
            // GOS device typically has no sync account. Verify on-device that local
            // contacts show in the default Contacts view (tracked follow-up).
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build(),
        )

        fun dataRow(mime: String) = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, mime)

        ops += dataRow(StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, record.displayName)
            .withValue(StructuredName.GIVEN_NAME, record.givenName)
            .withValue(StructuredName.FAMILY_NAME, record.familyName)
            .build()
        record.phones.forEach {
            ops += dataRow(Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, it.value)
                .withValue(Phone.TYPE, phoneTypeValue(it.type))
                .build()
        }
        record.emails.forEach {
            ops += dataRow(Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, it.value)
                .withValue(Email.TYPE, contactTypeValue(it.type))
                .build()
        }
        record.postals.forEach {
            ops += dataRow(StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(StructuredPostal.FORMATTED_ADDRESS, it.value)
                .withValue(StructuredPostal.TYPE, contactTypeValue(it.type))
                .build()
        }
        if (record.organization != null || record.title != null) {
            ops += dataRow(Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, record.organization)
                .withValue(Organization.TITLE, record.title)
                .build()
        }
        record.note?.let {
            ops += dataRow(Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, it).build()
        }

        return runCatching { resolver.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess
    }

    private class MutableContact(
        var displayName: String = "",
        var givenName: String? = null,
        var familyName: String? = null,
        val phones: MutableList<LabeledValue> = mutableListOf(),
        val emails: MutableList<LabeledValue> = mutableListOf(),
        val postals: MutableList<LabeledValue> = mutableListOf(),
        var organization: String? = null,
        var title: String? = null,
        var note: String? = null,
    ) {
        fun toRecord() = ContactRecord(
            displayName, givenName, familyName,
            phones.toList(), emails.toList(), postals.toList(),
            organization, title, note,
        )
    }

    private fun phoneTypeName(type: Int): String = when (type) {
        Phone.TYPE_MOBILE -> "CELL"
        Phone.TYPE_HOME -> "HOME"
        Phone.TYPE_WORK -> "WORK"
        else -> "OTHER"
    }

    private fun phoneTypeValue(name: String): Int = when (name) {
        "CELL" -> Phone.TYPE_MOBILE
        "HOME" -> Phone.TYPE_HOME
        "WORK" -> Phone.TYPE_WORK
        else -> Phone.TYPE_OTHER
    }

    /** Email/postal share the HOME/WORK/OTHER constants (same values in both kinds). */
    private fun contactTypeName(type: Int): String = when (type) {
        Email.TYPE_HOME -> "HOME"
        Email.TYPE_WORK -> "WORK"
        else -> "OTHER"
    }

    private fun contactTypeValue(name: String): Int = when (name) {
        "HOME" -> Email.TYPE_HOME
        "WORK" -> Email.TYPE_WORK
        else -> Email.TYPE_OTHER
    }
}
