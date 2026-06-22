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

/**
 * The ContentResolver seam for contacts. Pure data in/out so providers are JVM-unit-testable
 * with fakes; [AndroidContactsStore] is the thin ContactsContract adapter. Reads MAY throw
 * [SecurityException] when READ_CONTACTS is denied — providers degrade, never crash.
 */
interface ContactsStore {

    /** Number of aggregate contacts visible to the app. */
    fun count(): Int

    fun readAll(): List<ContactRecord>

    /** Insert one contact as a new local raw contact. False on failure (never throws). */
    fun insert(record: ContactRecord): Boolean
}
