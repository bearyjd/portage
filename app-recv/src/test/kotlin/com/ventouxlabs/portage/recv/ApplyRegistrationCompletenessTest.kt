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

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.providers.ApplyProviderRegistry
import com.ventouxlabs.portage.providers.apk.ApkTargetConfig
import com.ventouxlabs.portage.providers.calendar.CalendarStore
import com.ventouxlabs.portage.providers.calendar.EventRecord
import com.ventouxlabs.portage.providers.calllog.CallLogStore
import com.ventouxlabs.portage.providers.calllog.CallRecord
import com.ventouxlabs.portage.providers.contacts.ContactRecord
import com.ventouxlabs.portage.providers.contacts.ContactsStore
import com.ventouxlabs.portage.providers.inventory.AppRecord
import com.ventouxlabs.portage.providers.inventory.InventorySource
import com.ventouxlabs.portage.providers.mms.MmsStore
import com.ventouxlabs.portage.providers.settings.SecureGlobalSettingsStore
import com.ventouxlabs.portage.providers.settings.SystemSettingsStore
import com.ventouxlabs.portage.settings.Namespace
import com.ventouxlabs.portage.providers.sms.SmsRecord
import com.ventouxlabs.portage.providers.sms.SmsRoleGateway
import com.ventouxlabs.portage.providers.sms.SmsStore
import com.ventouxlabs.portage.providers.sound.SoundFileRemap
import com.ventouxlabs.portage.providers.sound.SoundRole
import com.ventouxlabs.portage.providers.sound.SoundStore
import com.ventouxlabs.portage.providers.wallpaper.ImageBounds
import com.ventouxlabs.portage.providers.wallpaper.WallpaperStore
import com.ventouxlabs.portage.providers.wallpaper.WallpaperSurface
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards `.agent_native/agent_roadmap.md` item #1: wiring a new [ItemKind] end-to-end is a manual
 * checklist across `core-model`, `app-send`, `app-recv`, and `AndroidManifest.xml` — nothing fails
 * at COMPILE time if the `app-recv` registration step is missed, it silently degrades to
 * `ItemStatus.UNKNOWN_KIND` at transfer time. This test calls the SAME [buildApplyProviders]
 * factory `MainActivity` uses (with fakes standing in for the Android-backed stores) so a missed
 * registration fails HERE, in CI, with a clear diff of the missing kind.
 */
class ApplyRegistrationCompletenessTest {

    @Test
    fun `every ItemKind has a registered apply provider`() {
        val registry = ApplyProviderRegistry(
            buildApplyProviders(
                contactsStore = FakeContactsStore,
                calendarStore = FakeCalendarStore,
                callLogStore = FakeCallLogStore,
                smsStore = FakeSmsStore,
                smsRoleGateway = FakeSmsRoleGateway,
                mmsStore = FakeMmsStore,
                inventorySource = FakeInventorySource,
                apkStagingDir = File("unused-completeness-test-staging"),
                apkTargetConfig = { ApkTargetConfig(emptyList(), "mdpi", emptyList()) },
                systemSettingsStore = FakeSystemSettingsStore,
                secureGlobalSettingsStore = FakeSecureGlobalSettingsStore,
                wallpaperStore = FakeWallpaperStore,
                soundStore = FakeSoundStore,
                soundFileRemap = SoundFileRemap(),
                relayHandoff = { _, _, _, _ -> true },
                userFileWrite = { _, _ -> true },
                sinks = DoneSinks(
                    onInstallActions = {},
                    onRepairEntries = {},
                    onRelayPrompt = {},
                    onApkInstallPrompt = {},
                    onPermissionsRestored = { _, _ -> },
                    onOptInPermissions = { _, _ -> },
                    onRoleCandidates = {},
                ),
                onApkInstall = {},
                onStoreFallback = { _, _ -> },
            ),
        )

        val registered = ItemKind.entries.filter { registry.forKind(it) != null }

        // No apply-only exceptions today: every shipped ItemKind has an app-recv apply provider.
        assertThat(registered).containsExactlyElementsIn(ItemKind.entries)
    }
}

private object FakeContactsStore : ContactsStore {
    override fun count() = 0
    override fun readAll(): List<ContactRecord> = emptyList()
    override fun insert(record: ContactRecord) = true
}

private object FakeCalendarStore : CalendarStore {
    override fun count() = 0
    override fun readAll(): List<EventRecord> = emptyList()
    override fun insert(event: EventRecord) = true
    override fun hasWritableCalendar() = true
    override fun createLocalCalendar(displayName: String) = false
}

private object FakeCallLogStore : CallLogStore {
    override fun count() = 0
    override fun readAll(): List<CallRecord> = emptyList()
    override fun insert(record: CallRecord) = true
}

private object FakeSmsStore : SmsStore {
    override fun count() = 0
    override fun readAll(): List<SmsRecord> = emptyList()
    override fun insert(record: SmsRecord) = true
}

private object FakeSmsRoleGateway : SmsRoleGateway {
    override fun isSelfDefault() = false
    override fun currentDefault(): String? = null
    override fun launchRestore(priorHolderPackage: String?) = false
}

private object FakeMmsStore : MmsStore {
    override fun count() = 0
    override fun writeAllTo(sink: java.io.OutputStream, maxBytes: Long) =
        com.ventouxlabs.portage.providers.mms.MmsExportSummary(exported = 0, skipped = 0, bytes = 0)
    override fun insert(record: com.ventouxlabs.portage.providers.mms.MmsRecord) = true
}

private object FakeInventorySource : InventorySource {
    override fun installedUserApps(): List<AppRecord> = emptyList()
    override fun installedPackageNames(): Set<String> = emptySet()
}

private object FakeSystemSettingsStore : SystemSettingsStore {
    override fun read(name: String): String? = null
    override fun canWrite() = false
    override fun write(name: String, value: String) = false
}

private object FakeSecureGlobalSettingsStore : SecureGlobalSettingsStore {
    override fun read(namespace: Namespace, name: String): String? = null
    override fun canWrite() = false
    override fun write(namespace: Namespace, name: String, value: String) = false
}

private object FakeWallpaperStore : WallpaperStore {
    override fun read(surface: WallpaperSurface): ByteArray? = null
    override fun decodeBounds(bytes: ByteArray): ImageBounds? = null
    override fun setStream(surface: WallpaperSurface, bytes: ByteArray) = false
}

private object FakeSoundStore : SoundStore {
    override fun read(role: SoundRole): String? = null
    override fun titleOf(uri: String): String? = null
    override fun resolveBuiltin(role: SoundRole, title: String): String? = null
    override fun canWrite() = false
    override fun setDefault(role: SoundRole, uri: String) = false
}
