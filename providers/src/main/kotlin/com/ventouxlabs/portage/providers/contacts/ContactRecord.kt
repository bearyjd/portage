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

/** A typed value like `TEL;TYPE=CELL`. [type] is the uppercase vCard TYPE token. */
data class LabeledValue(val value: String, val type: String)

/**
 * One contact, as much of it as portage carries: name, phones, emails, postal addresses,
 * organization, note, the user-visible favorite/starred bit, and a bounded thumbnail.
 * App-specific raw-contact data and full-resolution display photos are out of scope.
 */
data class ContactRecord(
    val displayName: String,
    val givenName: String? = null,
    val familyName: String? = null,
    val phones: List<LabeledValue> = emptyList(),
    val emails: List<LabeledValue> = emptyList(),
    val postals: List<LabeledValue> = emptyList(),
    val organization: String? = null,
    val title: String? = null,
    val note: String? = null,
    val starred: Boolean = false,
    /** Base64-encoded JPEG/PNG thumbnail, bounded by [MAX_CONTACT_PHOTO_BYTES] when read/parsed. */
    val photoBase64: String? = null,
)

const val MAX_CONTACT_PHOTO_BYTES = 256 * 1024
