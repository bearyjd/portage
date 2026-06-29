/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.calllog.AndroidCallLogStore
import com.ventouxlabs.portage.providers.calllog.CallLogApplyProvider
import com.ventouxlabs.portage.providers.calllog.CallLogExportProvider
import com.ventouxlabs.portage.providers.calllog.CallRecord
import com.ventouxlabs.portage.providers.contacts.AndroidContactsStore
import com.ventouxlabs.portage.providers.contacts.ContactRecord
import com.ventouxlabs.portage.providers.contacts.ContactsApplyProvider
import com.ventouxlabs.portage.providers.contacts.LabeledValue
import com.ventouxlabs.portage.providers.contacts.VCard3
import com.ventouxlabs.portage.providers.sms.AndroidSmsRoleGateway
import com.ventouxlabs.portage.providers.sms.AndroidSmsStore
import com.ventouxlabs.portage.providers.sms.SmsApplyProvider
import com.ventouxlabs.portage.providers.sms.SmsExportProvider
import com.ventouxlabs.portage.providers.sms.SmsRecord
import com.ventouxlabs.portage.recv.imports.FileCallLogImportJournal
import com.ventouxlabs.portage.recv.imports.FileContactImportJournal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Destructive-but-self-cleaning contract tests against Android's real providers. The orchestration
 * script installs the debug app, preserves/restores the SMS holder, and runs this suite. Every
 * fixture uses a reserved marker and is deleted before and after its test.
 */
@RunWith(AndroidJUnit4::class)
class ProviderDeviceContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver

    @Before
    fun adoptProviderPermissions() {
        adoptShellProviderPermissions()
        cleanup()
    }

    private fun adoptShellProviderPermissions() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_SMS,
        )
    }

    @After
    fun restoreDevice() {
        cleanup()
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    @Test
    fun contactsApplyTwiceCreatesOneExactContact() = runBlocking {
        val record = ContactRecord(
            displayName = CONTACT_NAME,
            phones = listOf(LabeledValue(CONTACT_PHONE, "CELL")),
        )
        val payload = ByteArrayOutputStream().also { VCard3.write(listOf(record), it) }.toByteArray()
        val journalFile = File(context.cacheDir, "device-contract-contact-journal")
        journalFile.delete()
        val provider = ContactsApplyProvider(
            AndroidContactsStore(resolver),
            FileContactImportJournal(journalFile),
        )

        assertThat(provider.apply(ByteArrayInputStream(payload)).status).isEqualTo(ItemStatus.OK)
        val retry = provider.apply(ByteArrayInputStream(payload))

        assertThat(retry.status).isEqualTo(ItemStatus.OK)
        assertThat(retry.detail).contains("already present 1")
        assertThat(AndroidContactsStore(resolver).readAll().count { it.displayName == CONTACT_NAME })
            .isEqualTo(1)
    }

    @Test
    fun contactsReadBackKeepsAggregatedRawContactsSeparate() {
        val store = AndroidContactsStore(resolver)
        val first = ContactRecord(
            displayName = CONTACT_NAME,
            phones = listOf(LabeledValue(CONTACT_PHONE, "CELL")),
        )
        val second = first.copy(phones = listOf(LabeledValue(CONTACT_PHONE_SECOND, "WORK")))

        assertThat(store.insert(first)).isTrue()
        assertThat(store.insert(second)).isTrue()
        resolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, null)
                    .withValue(RawContacts.ACCOUNT_NAME, null)
                    .build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, CONTACT_PHONE)
                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                    .build(),
            ),
        )

        val fixturePhones = setOf(CONTACT_PHONE, CONTACT_PHONE_SECOND)
        val matching = store.readAll().filter { contact ->
            contact.phones.any { it.value in fixturePhones }
        }
        assertThat(matching).hasSize(3)
        assertThat(matching.all { it.displayName.isNotBlank() }).isTrue()
        assertThat(matching.map { it.phones.single().value })
            .containsExactly(CONTACT_PHONE, CONTACT_PHONE_SECOND, CONTACT_PHONE)
    }

    @Test
    fun callLogApplyTwiceCreatesOneExactCallWithoutReadDependency() = runBlocking {
        val record = CallRecord(CALL_NUMBER, Calls.INCOMING_TYPE, CALL_DATE, 17, CALL_NAME)
        val payload = ByteArrayOutputStream().also {
            CallLogExportProvider(FixtureCallStore(record)).exportTo(it)
        }.toByteArray()
        val journalFile = File(context.cacheDir, "device-contract-call-journal")
        journalFile.delete()
        val provider = CallLogApplyProvider(
            AndroidCallLogStore(resolver),
            FileCallLogImportJournal(journalFile),
        )

        assertThat(provider.apply(ByteArrayInputStream(payload)).status).isEqualTo(ItemStatus.OK)
        val retry = provider.apply(ByteArrayInputStream(payload))

        assertThat(retry.status).isEqualTo(ItemStatus.OK)
        assertThat(retry.detail).contains("already imported 1")
        assertThat(countCalls()).isEqualTo(1)
    }

    @Test
    fun smsRoleGateAndProviderWriteOneMessage() = runBlocking {
        val roleManager = context.getSystemService(RoleManager::class.java)
        assumeTrue("orchestrator did not grant ROLE_SMS", roleManager.isRoleHeld(RoleManager.ROLE_SMS))
        val record = SmsRecord(SMS_ADDRESS, SMS_BODY, SMS_DATE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        val payload = ByteArrayOutputStream().also {
            SmsExportProvider(FixtureSmsStore(record)).exportTo(it)
        }.toByteArray()
        val provider = SmsApplyProvider(AndroidSmsStore(resolver), AndroidSmsRoleGateway(context))

        // Exercise the app's real role-granted identity. Keeping UiAutomation's adopted shell
        // identity here makes SmsProvider see shell rather than the default-SMS package and routes
        // the write through a different access path.
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        val outcome = try {
            provider.apply(ByteArrayInputStream(payload))
        } finally {
            adoptShellProviderPermissions()
        }

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(countSms()).isEqualTo(1)
    }

    private fun cleanup() {
        val rawIds = mutableListOf<Long>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.RAW_CONTACT_ID),
            "${Data.DISPLAY_NAME} = ?",
            arrayOf(CONTACT_NAME),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) rawIds += cursor.getLong(0)
        }
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.RAW_CONTACT_ID),
            "${Data.MIMETYPE} = ? AND ${Phone.NUMBER} IN (?, ?)",
            arrayOf(Phone.CONTENT_ITEM_TYPE, CONTACT_PHONE, CONTACT_PHONE_SECOND),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) rawIds += cursor.getLong(0)
        }
        rawIds.distinct().forEach {
            resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, it), null, null)
        }
        resolver.delete(
            Calls.CONTENT_URI,
            "${Calls.NUMBER} = ? AND ${Calls.DATE} = ?",
            arrayOf(CALL_NUMBER, CALL_DATE.toString()),
        )
        // SmsProvider authorizes writes against the real default-SMS app identity. An adopted
        // shell identity can read these rows but does not reliably delete them on GrapheneOS.
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        try {
            resolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ?",
                arrayOf(SMS_ADDRESS, SMS_BODY),
            )
        } finally {
            adoptShellProviderPermissions()
        }
        File(context.cacheDir, "device-contract-contact-journal").delete()
        File(context.cacheDir, "device-contract-call-journal").delete()
    }

    private fun countCalls(): Int =
        resolver.query(
            Calls.CONTENT_URI,
            arrayOf(Calls._ID),
            "${Calls.NUMBER} = ? AND ${Calls.DATE} = ?",
            arrayOf(CALL_NUMBER, CALL_DATE.toString()),
            null,
        )?.use { it.count } ?: 0

    private fun countSms(): Int =
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ?",
            arrayOf(SMS_ADDRESS, SMS_BODY),
            null,
        )?.use { it.count } ?: 0

    private class FixtureCallStore(private val record: CallRecord) :
        com.ventouxlabs.portage.providers.calllog.CallLogStore {
        override fun count() = 1
        override fun readAll() = listOf(record)
        override fun insert(record: CallRecord) = error("export fixture only")
    }

    private class FixtureSmsStore(private val record: SmsRecord) :
        com.ventouxlabs.portage.providers.sms.SmsStore {
        override fun count() = 1
        override fun readAll() = listOf(record)
        override fun insert(record: SmsRecord) = error("export fixture only")
    }

    private companion object {
        const val CONTACT_NAME = "PORTAGE DEVICE CONTRACT"
        const val CONTACT_PHONE = "+1 555 000 9911"
        const val CONTACT_PHONE_SECOND = "+1 555 000 9914"
        const val CALL_NUMBER = "+15550009912"
        const val CALL_NAME = "PORTAGE CONTRACT"
        const val CALL_DATE = 1_893_456_000_000L
        const val SMS_ADDRESS = "+15550009913"
        const val SMS_BODY = "PORTAGE DEVICE CONTRACT — SAFE TO DELETE"
        const val SMS_DATE = 1_893_456_100_000L
    }
}
