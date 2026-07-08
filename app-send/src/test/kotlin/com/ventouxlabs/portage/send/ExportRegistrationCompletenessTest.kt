/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.providers.bluetooth.BluetoothStore
import com.ventouxlabs.portage.providers.bluetooth.BondedDevice
import com.ventouxlabs.portage.providers.calendar.CalendarStore
import com.ventouxlabs.portage.providers.calendar.EventRecord
import com.ventouxlabs.portage.providers.calllog.CallLogStore
import com.ventouxlabs.portage.providers.calllog.CallRecord
import com.ventouxlabs.portage.providers.contacts.ContactRecord
import com.ventouxlabs.portage.providers.contacts.ContactsStore
import com.ventouxlabs.portage.providers.inventory.AppRecord
import com.ventouxlabs.portage.providers.inventory.InventorySource
import com.ventouxlabs.portage.providers.mms.MmsExportSummary
import com.ventouxlabs.portage.providers.mms.MmsRecord
import com.ventouxlabs.portage.providers.mms.MmsStore
import com.ventouxlabs.portage.providers.settings.SecureGlobalSettingsStore
import com.ventouxlabs.portage.providers.settings.SystemSettingsStore
import com.ventouxlabs.portage.providers.sms.SmsRecord
import com.ventouxlabs.portage.providers.sms.SmsStore
import com.ventouxlabs.portage.providers.sound.SoundRole
import com.ventouxlabs.portage.providers.sound.SoundStore
import com.ventouxlabs.portage.providers.wallpaper.ImageBounds
import com.ventouxlabs.portage.providers.wallpaper.WallpaperStore
import com.ventouxlabs.portage.providers.wallpaper.WallpaperSurface
import com.ventouxlabs.portage.settings.Namespace
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.OutputStream

/**
 * Guards `.agent_native/agent_roadmap.md` item #1 on the export side: wiring a new [ItemKind]
 * end-to-end is a manual checklist across `core-model`, `app-send`, `app-recv`, and
 * `AndroidManifest.xml` — nothing fails at COMPILE time if the `app-send` export registration
 * step is missed. This test calls the SAME [buildExportProviders] factory `MainActivity` uses
 * (with fakes standing in for the Android-backed stores) so a missed registration fails HERE.
 *
 * `APK`, `APP_BACKUP_RELAY`, and `USER_FILE` are explicit, documented exceptions — see
 * [buildExportProviders]'s kdoc — they are apply-only, never produced through this fixed provider
 * list.
 */
class ExportRegistrationCompletenessTest {

    @Test
    fun `every ItemKind except the apply-only exceptions has a registered export provider`() {
        val providers = buildExportProviders(
            contactsStore = CompletenessContactsStore,
            calendarStore = CompletenessCalendarStore,
            callLogStore = CompletenessCallLogStore,
            smsStore = CompletenessSmsStore,
            mmsStore = CompletenessMmsStore,
            inventorySource = CompletenessInventorySource,
            systemSettingsStore = CompletenessSystemSettingsStore,
            secureGlobalSettingsStore = CompletenessSecureGlobalSettingsStore,
            wallpaperStore = CompletenessWallpaperStore,
            soundStore = CompletenessSoundStore,
            bluetoothStore = CompletenessBluetoothStore,
        )

        val registered = providers.map { it.kind }.distinct()

        val applyOnlyExceptions = setOf(ItemKind.APK, ItemKind.APP_BACKUP_RELAY, ItemKind.USER_FILE)
        val expected = ItemKind.entries.filter { it !in applyOnlyExceptions }

        assertThat(registered).containsExactlyElementsIn(expected)
    }
}

private object CompletenessContactsStore : ContactsStore {
    override fun count() = 0
    override fun readAll(): List<ContactRecord> = emptyList()
    override fun insert(record: ContactRecord) = true
}

private object CompletenessCalendarStore : CalendarStore {
    override fun count() = 0
    override fun readAll(): List<EventRecord> = emptyList()
    override fun insert(event: EventRecord) = true
}

private object CompletenessCallLogStore : CallLogStore {
    override fun count() = 0
    override fun readAll(): List<CallRecord> = emptyList()
    override fun insert(record: CallRecord) = true
}

private object CompletenessSmsStore : SmsStore {
    override fun count() = 0
    override fun readAll(): List<SmsRecord> = emptyList()
    override fun insert(record: SmsRecord) = true
}

private object CompletenessMmsStore : MmsStore {
    override fun count() = 0
    override fun writeAllTo(sink: OutputStream, maxBytes: Long) =
        MmsExportSummary(exported = 0, skipped = 0, bytes = 0)
    override fun insert(record: MmsRecord) = true
}

private object CompletenessInventorySource : InventorySource {
    override fun installedUserApps(): List<AppRecord> = emptyList()
    override fun installedPackageNames(): Set<String> = emptySet()
}

private object CompletenessSystemSettingsStore : SystemSettingsStore {
    override fun read(name: String): String? = null
    override fun canWrite() = false
    override fun write(name: String, value: String) = false
}

private object CompletenessSecureGlobalSettingsStore : SecureGlobalSettingsStore {
    override fun read(namespace: Namespace, name: String): String? = null
    override fun canWrite() = false
    override fun write(namespace: Namespace, name: String, value: String) = false
}

private object CompletenessWallpaperStore : WallpaperStore {
    override fun read(surface: WallpaperSurface): ByteArray? = null
    override fun decodeBounds(bytes: ByteArray): ImageBounds? = null
    override fun setStream(surface: WallpaperSurface, bytes: ByteArray) = false
}

private object CompletenessSoundStore : SoundStore {
    override fun read(role: SoundRole): String? = null
    override fun titleOf(uri: String): String? = null
    override fun resolveBuiltin(role: SoundRole, title: String): String? = null
    override fun canWrite() = false
    override fun setDefault(role: SoundRole, uri: String) = false
}

private object CompletenessBluetoothStore : BluetoothStore {
    override fun isReadable() = false
    override fun bondedDevices(): List<BondedDevice> = emptyList()
}
