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
import android.content.ContentValues
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.calendar.AndroidCalendarStore
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
import com.ventouxlabs.portage.providers.userfile.UserFileHeader
import com.ventouxlabs.portage.recv.files.AndroidUserFileStore
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
            givenName = "Portage",
            familyName = "Contract",
            namePrefix = "Dr.",
            middleName = "Device",
            nameSuffix = "Jr.",
            phoneticGivenName = "ポーテージ",
            phoneticMiddleName = "デバイス",
            phoneticFamilyName = "コントラクト",
            phones = listOf(LabeledValue(CONTACT_PHONE, "CELL")),
            // Valid 1x1 PNG: exercise the real ContactsProvider photo row without large fixtures.
            photoBase64 = CONTACT_PHOTO,
            nickname = "Portage fixture",
            birthday = "--07-01",
            websites = WEBSITE_FIXTURES.map { (type, _) ->
                LabeledValue(
                    value = "https://${type.lowercase()}.portage.example",
                    type = type,
                    customLabel = "Portage custom".takeIf { type == "CUSTOM" },
                )
            },
            groupNames = listOf(CONTACT_GROUP),
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
        val imported = AndroidContactsStore(resolver).readAll().filter { it.displayName == CONTACT_NAME }
        assertThat(imported).hasSize(1)
        assertThat(imported.single().photoBase64).isNotNull()
        assertThat(imported.single().namePrefix).isEqualTo("Dr.")
        assertThat(imported.single().middleName).isEqualTo("Device")
        assertThat(imported.single().nameSuffix).isEqualTo("Jr.")
        assertThat(imported.single().phoneticGivenName).isEqualTo("ポーテージ")
        assertThat(imported.single().phoneticMiddleName).isEqualTo("デバイス")
        assertThat(imported.single().phoneticFamilyName).isEqualTo("コントラクト")
        assertThat(imported.single().nickname).isEqualTo("Portage fixture")
        assertThat(imported.single().birthday).isEqualTo("--07-01")
        assertThat(imported.single().websites).containsExactlyElementsIn(record.websites)
        assertThat(imported.single().groupNames).containsExactly(CONTACT_GROUP)
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Website.URL, Website.TYPE, Website.LABEL),
            "${Data.MIMETYPE} = ? AND ${Website.URL} LIKE ?",
            arrayOf(Website.CONTENT_ITEM_TYPE, "https://%.portage.example"),
            null,
        )?.use { cursor ->
            val actual = mutableMapOf<String, Pair<Int, String?>>()
            while (cursor.moveToNext()) {
                actual[cursor.getString(0)] = cursor.getInt(1) to cursor.getString(2)
            }
            WEBSITE_FIXTURES.forEach { (type, providerType) ->
                val row = actual.getValue("https://${type.lowercase()}.portage.example")
                assertThat(row.first).isEqualTo(providerType)
                assertThat(row.second).isEqualTo("Portage custom".takeIf { type == "CUSTOM" })
            }
        }
        Unit
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

    @Test
    fun userFileWritePublishesExactBytesAndMetadataInPortageDownloads() {
        val bytes = "Portage device contract\n\u0000binary-safe".toByteArray()
        val header = UserFileHeader(USER_FILE_NAME, USER_FILE_MIME, bytes.size.toLong())

        assertThat(AndroidUserFileStore(context).write(header, ByteArrayInputStream(bytes))).isTrue()

        val rows = downloadRows(USER_FILE_NAME)
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.mimeType).isEqualTo(USER_FILE_MIME)
        assertThat(row.relativePath).isEqualTo("Download/Portage/")
        assertThat(row.pending).isEqualTo(0)
        assertThat(resolver.openInputStream(row.uri)?.use { it.readBytes() }).isEqualTo(bytes)
    }

    @Test
    fun userFileWriteDeletesRowWhenInputIsTruncated() {
        val bytes = "truncated".toByteArray()
        val header = UserFileHeader(
            USER_FILE_TRUNCATED_NAME,
            USER_FILE_MIME,
            bytes.size.toLong() + 7,
        )

        assertThat(AndroidUserFileStore(context).write(header, ByteArrayInputStream(bytes))).isFalse()

        assertThat(downloadRows(USER_FILE_TRUNCATED_NAME)).isEmpty()
    }

    /**
     * #163 — the one thing JVM tests cannot reach: does the real CalendarProvider on this OS accept
     * an account-less local calendar, created by THIS app with only WRITE_CALENDAR?
     *
     * #159 ships `createLocalCalendar` on the assumption that [CalendarContract.ACCOUNT_TYPE_LOCAL]
     * needs no registered sync adapter and no account. That is standard practice (Etar, Simple
     * Calendar) but was unverified on GrapheneOS — and a code review passing on the diff cannot
     * establish it. The `CalendarStore` seam is fully unit-tested with a fake, so the provider LOGIC
     * around this call is covered; this test covers the call.
     *
     * Runs WITHOUT the adopted shell identity on purpose. Adopting shell would prove the provider
     * accepts the insert from *shell*, which is not the production caller — portage does this with
     * its own runtime-granted WRITE_CALENDAR. Skips (rather than fails) if the grant is absent, so
     * the suite still passes on a device the orchestration script has not prepared.
     */
    @Test
    fun calendarCreatesAccountLessLocalCalendarAndAcceptsEvents() {
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        try {
            assumeTrue(
                "app needs its OWN WRITE_CALENDAR/READ_CALENDAR grant — run scripts/device-contract.sh",
                context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED,
            )
            deleteMarkerCalendars()
            val store = AndroidCalendarStore(resolver)

            // THE assertion #163 exists for: the platform accepts the local-calendar insert.
            assertThat(store.createLocalCalendar(CALENDAR_MARKER)).isTrue()

            // Exactly one, and it is genuinely account-less and writable — not a soft-deleted
            // ghost, and not a second copy from a previous run.
            val ids = markerCalendarIds()
            assertThat(ids).hasSize(1)
            resolver.query(
                ContentUris.withAppendedId(Calendars.CONTENT_URI, ids.single()),
                arrayOf(
                    Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_ACCESS_LEVEL,
                    Calendars.VISIBLE, Calendars.CALENDAR_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { row ->
                assertThat(row.moveToFirst()).isTrue()
                assertThat(row.getString(0)).isEqualTo(CalendarContract.ACCOUNT_TYPE_LOCAL)
                assertThat(row.getInt(1)).isAtLeast(Calendars.CAL_ACCESS_CONTRIBUTOR)
                assertThat(row.getInt(2)).isEqualTo(1)
                assertThat(row.getString(3)).isEqualTo(CALENDAR_MARKER)
            } ?: error("created calendar ${ids.single()} was not readable back")

            // And it is a usable insert target — an event lands in THAT calendar, which is the
            // whole point (a created-but-unwritable calendar would still fail the user).
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, ids.single())
                put(Events.TITLE, CALENDAR_EVENT_TITLE)
                put(Events.DTSTART, CALENDAR_EVENT_START)
                put(Events.DTEND, CALENDAR_EVENT_START + 3_600_000L)
                put(Events.EVENT_TIMEZONE, "UTC")
            }
            assertThat(resolver.insert(Events.CONTENT_URI, values)).isNotNull()
            resolver.query(
                Events.CONTENT_URI,
                arrayOf(Events._ID),
                "${Events.CALENDAR_ID} = ? AND ${Events.TITLE} = ?",
                arrayOf(ids.single().toString(), CALENDAR_EVENT_TITLE),
                null,
            )?.use { assertThat(it.count).isEqualTo(1) } ?: error("event query returned no cursor")

            // hasWritableCalendar must now see it. NOTE this cannot prove the false→true flip on a
            // phone that already had calendars (it was already true); the zero-calendar half of
            // #163 needs a device with none — see the issue.
            assertThat(AndroidCalendarStore(resolver).hasWritableCalendar()).isTrue()
        } finally {
            deleteMarkerCalendars()
            adoptShellProviderPermissions()
        }
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
        resolver.delete(Groups.CONTENT_URI, "${Groups.TITLE} = ?", arrayOf(CONTACT_GROUP))
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
        deleteDownloads(USER_FILE_NAME)
        deleteDownloads(USER_FILE_TRUNCATED_NAME)
        deleteMarkerCalendars()
    }

    /**
     * Delete ONLY the reserved marker calendar (#163). Triple-scoped — local account type AND the
     * marker account name AND the marker display name — because this suite runs on real phones
     * carrying real Google calendars, and a loose selection here would delete a user's actual data.
     * Deleting a calendar cascades its events, so the fixture event needs no separate sweep.
     *
     * Goes through the sync-adapter URI because that is how the calendar was created, so the
     * ACCOUNT_NAME/ACCOUNT_TYPE params CalendarProvider2 ANDs into the selection line up with the
     * row — which makes the delete NARROWER, not broader. (An earlier version of this comment
     * justified it with a soft-"deleted=1" claim; that rule is documented for Events, and I have
     * not verified it applies to Calendars, so it is not the reason.)
     *
     * A failure is logged rather than swallowed silently: it cannot be fatal (cleanup runs in
     * @After, and throwing there would mask the real test result), but an invisible cleanup
     * failure would leave residue that only shows up as a confusing `hasSize(1)` failure on the
     * NEXT run.
     */
    private fun deleteMarkerCalendars() {
        runCatching {
            resolver.delete(
                syncAdapterCalendars(CALENDAR_MARKER),
                "${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.ACCOUNT_NAME} = ? AND " +
                    "${Calendars.CALENDAR_DISPLAY_NAME} = ?",
                arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, CALENDAR_MARKER, CALENDAR_MARKER),
            )
        }.onFailure {
            Log.w("PortageContract", "marker calendar cleanup failed; next run may see residue", it)
        }
    }

    private fun syncAdapterCalendars(account: String): Uri =
        Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()

    private fun markerCalendarIds(): List<Long> = buildList {
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.CALENDAR_DISPLAY_NAME} = ?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, CALENDAR_MARKER),
            null,
        )?.use { while (it.moveToNext()) add(it.getLong(0)) }
    }

    private fun downloadRows(displayName: String): List<DownloadRow> {
        val rows = mutableListOf<DownloadRow>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.RELATIVE_PATH,
                MediaStore.Downloads.IS_PENDING,
            ),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += DownloadRow(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0),
                    ),
                    mimeType = cursor.getString(1),
                    relativePath = cursor.getString(2),
                    pending = cursor.getInt(3),
                )
            }
        }
        return rows
    }

    private fun deleteDownloads(displayName: String) {
        downloadRows(displayName).forEach { resolver.delete(it.uri, null, null) }
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

    private data class DownloadRow(
        val uri: Uri,
        val mimeType: String,
        val relativePath: String,
        val pending: Int,
    )

    private companion object {
        const val CONTACT_NAME = "PORTAGE DEVICE CONTRACT"
        const val CONTACT_PHONE = "+1 555 000 9911"
        const val CONTACT_PHONE_SECOND = "+1 555 000 9914"
        const val CONTACT_GROUP = "PORTAGE DEVICE CONTRACT GROUP"
        const val CONTACT_PHOTO =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        const val CALL_NUMBER = "+15550009912"
        const val CALL_NAME = "PORTAGE CONTRACT"
        const val CALL_DATE = 1_893_456_000_000L
        const val SMS_ADDRESS = "+15550009913"
        const val SMS_BODY = "PORTAGE DEVICE CONTRACT — SAFE TO DELETE"
        const val SMS_DATE = 1_893_456_100_000L
        const val USER_FILE_NAME = "portage-device-contract.bin"
        const val USER_FILE_TRUNCATED_NAME = "portage-device-contract-truncated.bin"
        const val USER_FILE_MIME = "application/octet-stream"

        /**
         * Reserved calendar marker (#163). Deliberately NOT
         * [com.ventouxlabs.portage.providers.calendar.CalendarApplyProvider.LOCAL_CALENDAR_NAME]
         * ("Imported"): cleanup deletes by this name, and a real user calendar must never be
         * deletable by a test. `createLocalCalendar` takes the display name as a parameter, so a
         * distinct marker exercises the identical platform call.
         */
        const val CALENDAR_MARKER = "PORTAGE DEVICE CONTRACT CAL — SAFE TO DELETE"
        const val CALENDAR_EVENT_TITLE = "PORTAGE DEVICE CONTRACT EVENT"
        const val CALENDAR_EVENT_START = 1_893_456_200_000L
        val WEBSITE_FIXTURES = listOf(
            "CUSTOM" to Website.TYPE_CUSTOM,
            "HOMEPAGE" to Website.TYPE_HOMEPAGE,
            "BLOG" to Website.TYPE_BLOG,
            "PROFILE" to Website.TYPE_PROFILE,
            "HOME" to Website.TYPE_HOME,
            "WORK" to Website.TYPE_WORK,
            "FTP" to Website.TYPE_FTP,
            "OTHER" to Website.TYPE_OTHER,
        )
    }
}
