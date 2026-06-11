/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.contacts

/** A typed value like `TEL;TYPE=CELL`. [type] is the uppercase vCard TYPE token. */
data class LabeledValue(val value: String, val type: String)

/**
 * One contact, as much of it as portage carries: name, phones, emails, postal addresses,
 * organization, note. Photos and app-specific raw-contact data are out of scope for v1.
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
)
