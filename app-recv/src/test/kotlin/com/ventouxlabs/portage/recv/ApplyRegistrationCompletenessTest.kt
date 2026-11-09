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
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.apk.ApkArchivePackageName
import com.ventouxlabs.portage.providers.apk.ApkCodec
import com.ventouxlabs.portage.providers.apk.ApkContainerHeader
import com.ventouxlabs.portage.providers.apk.ApkFileEntry
import com.ventouxlabs.portage.providers.apk.ApkFileRole
import com.ventouxlabs.portage.providers.apk.ApkInstallResult
import com.ventouxlabs.portage.providers.apk.ApkSilentInstaller
import com.ventouxlabs.portage.providers.apk.ApkSourceFile
import com.ventouxlabs.portage.providers.apk.ApkTargetConfig
import com.ventouxlabs.portage.providers.apk.RuntimePermissionGranter
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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    @get:Rule
    val tmp = TemporaryFolder()

    private fun registryWith(
        stagingDir: File,
        archivePackageName: ApkArchivePackageName = ApkArchivePackageName.None,
        hasSilentInstall: () -> Boolean = { false },
        silentInstaller: ApkSilentInstaller = ApkSilentInstaller.Deferred,
        permissionGranter: RuntimePermissionGranter = RuntimePermissionGranter.NoOp,
    ): ApplyProviderRegistry = ApplyProviderRegistry(
            buildApplyProviders(
                contactsStore = FakeContactsStore,
                calendarStore = FakeCalendarStore,
                callLogStore = FakeCallLogStore,
                smsStore = FakeSmsStore,
                smsRoleGateway = FakeSmsRoleGateway,
                mmsStore = FakeMmsStore,
                inventorySource = FakeInventorySource,
                apkStagingDir = stagingDir,
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
                archivePackageName = archivePackageName,
                hasSilentInstall = hasSilentInstall,
                silentInstaller = silentInstaller,
                permissionGranter = permissionGranter,
            )
    )

    @Test
    fun `every ItemKind has a registered apply provider`() {
        val registry = registryWith(File("unused-completeness-test-staging"))

        val registered = ItemKind.entries.filter { registry.forKind(it) != null }

        // No apply-only exceptions today: every shipped ItemKind has an app-recv apply provider.
        assertThat(registered).containsExactlyElementsIn(ItemKind.entries)
    }
    private fun apkItem(): ByteArray {
        val entry = ApkFileEntry("base", ApkFileRole.BASE, length = 4)
        val header = ApkContainerHeader("com.example.app", 1L, 1, emptyList())
        return ByteArrayOutputStream().use { out ->
            ApkCodec.writeContainer(out, header, listOf(ApkSourceFile(entry) { ByteArrayInputStream(ByteArray(4)) }))
            out.toByteArray()
        }
    }

    /** Pins the production wiring: the archive seam passed to [buildApplyProviders] reaches the silent path. */
    @Test
    fun `buildApplyProviders threads the archive seam into the silent install path`() = runBlocking {
        var seamCalls = 0
        val registry = registryWith(
            stagingDir = tmp.newFolder("apk"),
            archivePackageName = ApkArchivePackageName { seamCalls++; "com.example.app" },
            hasSilentInstall = { true },
            silentInstaller = ApkSilentInstaller { _, _ -> ApkInstallResult.Installed },
        )
        val outcome = registry.forKind(ItemKind.APK)!!.apply(ByteArrayInputStream(apkItem()))
        assertThat(seamCalls).isEqualTo(1)
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(outcome.detail).contains("silently")
    }

    @Test
    fun `an unwired archive seam never takes the silent path`() = runBlocking {
        var silentCalls = 0
        val registry = registryWith(
            stagingDir = tmp.newFolder("apk-none"),
            hasSilentInstall = { true },
            silentInstaller = ApkSilentInstaller { _, _ -> silentCalls++; ApkInstallResult.Installed },
        )
        registry.forKind(ItemKind.APK)!!.apply(ByteArrayInputStream(apkItem()))
        assertThat(silentCalls).isEqualTo(0)
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
