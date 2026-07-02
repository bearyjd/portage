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
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Base64

/**
 * Thin ContactsContract adapter behind [ContactsStore]. Deliberately mechanical — all
 * carry/parse logic lives in the JVM-tested [VCard3]/[ContactsProviders] layer. Reads
 * propagate [SecurityException] (providers degrade); writes return false on any failure.
 */
class AndroidContactsStore(private val resolver: ContentResolver) : ContactsStore {

    private companion object {
        const val MAX_TOTAL_PHOTO_BYTES = 8 * 1024 * 1024
    }

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
            ContactsContract.Contacts.STARRED, Data.DATA15,
        )
        val mimes = arrayOf(
            StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE,
            StructuredPostal.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE,
            Photo.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE,
            Website.CONTENT_ITEM_TYPE,
        )
        val selection = "${Data.MIMETYPE} IN (${mimes.joinToString(",") { "?" }})"

        // CONTACT_ID is Android's aggregate identity and can union fields from several raw
        // contacts. Import deduplication needs the original per-source record, so group Data rows
        // by RAW_CONTACT_ID and derive its display name from its own StructuredName row.
        var retainedPhotoBytes = 0
        resolver.query(Data.CONTENT_URI, projection, selection, mimes, Data.RAW_CONTACT_ID)?.use { cursor ->
            while (cursor.moveToNext()) {
                val rawContactId = cursor.getLong(0)
                // Some constituent raw contacts contain useful phone/email rows but no name row.
                // Seed those from the aggregate display name so they remain exportable; an own
                // StructuredName below replaces the fallback without merging any other fields.
                val contact = builders.getOrPut(rawContactId) {
                    MutableContact(
                        displayName = cursor.getString(1).orEmpty(),
                        starred = cursor.getInt(7) == 1,
                    )
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
                    Nickname.CONTENT_ITEM_TYPE -> contact.nickname = data1?.ifBlank { null }
                    Event.CONTENT_ITEM_TYPE -> if (cursor.getInt(4) == Event.TYPE_BIRTHDAY) {
                        contact.birthday = data1?.ifBlank { null }
                    }
                    Website.CONTENT_ITEM_TYPE -> data1?.let {
                        val type = cursor.getInt(4)
                        contact.websites += LabeledValue(
                            value = it,
                            type = websiteTypeName(type),
                            customLabel = cursor.getString(5)?.takeIf { type == Website.TYPE_CUSTOM },
                        )
                    }
                    Photo.CONTENT_ITEM_TYPE -> cursor.getBlob(8)?.let { photo ->
                        if (contact.photoBase64 == null &&
                            photo.size <= MAX_CONTACT_PHOTO_BYTES &&
                            retainedPhotoBytes + photo.size <= MAX_TOTAL_PHOTO_BYTES
                        ) {
                            contact.photoBase64 = Base64.encodeToString(photo, Base64.NO_WRAP)
                            retainedPhotoBytes += photo.size
                        }
                    }
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
                .withValue(RawContacts.STARRED, if (record.starred) 1 else 0)
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
        record.nickname?.let {
            ops += dataRow(Nickname.CONTENT_ITEM_TYPE).withValue(Nickname.NAME, it).build()
        }
        record.birthday?.let {
            ops += dataRow(Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, it)
                .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                .build()
        }
        record.websites.forEach {
            ops += dataRow(Website.CONTENT_ITEM_TYPE)
                .withValue(Website.URL, it.value)
                .withValue(Website.TYPE, websiteTypeValue(it.type))
                .withValue(Website.LABEL, it.customLabel)
                .build()
        }
        record.photoBase64?.let { encoded ->
            val photo = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            if (photo != null && photo.size <= MAX_CONTACT_PHOTO_BYTES) {
                ops += dataRow(Photo.CONTENT_ITEM_TYPE).withValue(Photo.PHOTO, photo).build()
            }
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
        var starred: Boolean = false,
        var photoBase64: String? = null,
        var nickname: String? = null,
        var birthday: String? = null,
        val websites: MutableList<LabeledValue> = mutableListOf(),
    ) {
        fun toRecord() = ContactRecord(
            displayName, givenName, familyName,
            phones.toList(), emails.toList(), postals.toList(),
            organization, title, note, starred, photoBase64,
            nickname, birthday, websites.toList(),
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

    private fun websiteTypeName(type: Int): String = when (type) {
        Website.TYPE_CUSTOM -> "CUSTOM"
        Website.TYPE_HOMEPAGE -> "HOMEPAGE"
        Website.TYPE_BLOG -> "BLOG"
        Website.TYPE_PROFILE -> "PROFILE"
        Website.TYPE_HOME -> "HOME"
        Website.TYPE_WORK -> "WORK"
        Website.TYPE_FTP -> "FTP"
        else -> "OTHER"
    }

    private fun websiteTypeValue(name: String): Int = when (name.uppercase()) {
        "CUSTOM" -> Website.TYPE_CUSTOM
        "HOMEPAGE" -> Website.TYPE_HOMEPAGE
        "BLOG" -> Website.TYPE_BLOG
        "PROFILE" -> Website.TYPE_PROFILE
        "HOME" -> Website.TYPE_HOME
        "WORK" -> Website.TYPE_WORK
        "FTP" -> Website.TYPE_FTP
        else -> Website.TYPE_OTHER
    }
}
