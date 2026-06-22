/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.apk

import cc.grepon.portage.model.ItemKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream

/** A fake installed-app seam: returns a fixed app list with no Android dependency. */
private class FakeInstalledAppSource(private val apps: List<InstalledApp>) : InstalledAppSource {
    override fun installedUserApps(): List<InstalledApp> = apps
}

private fun file(name: String, length: Long): InstalledApkFile =
    InstalledApkFile(name = name, absolutePath = "/data/app/$name", length = length)

class InstalledAppsTest {

    private val signalSplit = InstalledApp(
        packageName = "org.thoughtcrime.securesms",
        label = "Signal",
        versionCode = 1234L,
        files = listOf(
            file("base.apk", 100),
            file("split_config.arm64_v8a.apk", 50),
            file("split_config.xxhdpi.apk", 20),
            file("split_config.en.apk", 5),
        ),
    )

    @Test
    fun `the fake seam reports its user apps`() {
        val source = FakeInstalledAppSource(listOf(signalSplit))
        assertThat(source.installedUserApps().map { it.packageName })
            .containsExactly("org.thoughtcrime.securesms")
    }

    @Test
    fun `totalBytes sums every split file's length`() {
        assertThat(signalSplit.totalBytes).isEqualTo(175L)
    }

    @Test
    fun `one provider is built per app with the APK kind and label`() {
        val providers = installedAppApkProviders(listOf(signalSplit)) { ByteArrayInputStream(ByteArray(0)) }
        assertThat(providers).hasSize(1)
        assertThat(providers.single().kind).isEqualTo(ItemKind.APK)
        assertThat(providers.single().group).isEqualTo("Apps")
        assertThat(providers.single().displayName).isEqualTo("Signal")
    }

    @Test
    fun `the base file becomes the literal base name and splits derive their tags`() = runTest {
        // Capture the exact bytes one provider emits, decode the container, and assert the per-file
        // entry names + tags came out of deriveTags — the receiver re-derives the same.
        val app = InstalledApp(
            packageName = "com.example.app",
            label = "Example",
            versionCode = 7L,
            files = listOf(
                file("base.apk", 4),
                file("split_config.arm64_v8a.apk", 4),
                file("split_config.xxhdpi.apk", 4),
                file("split_config.en.apk", 4),
            ),
        )
        // Each file opens four bytes so available() (length > 0) is satisfied.
        val provider = installedAppApkProviders(listOf(app)) { ByteArrayInputStream(ByteArray(4)) }.single()
        val out = java.io.ByteArrayOutputStream()
        provider.exportTo(out)
        val container = ApkCodec.readFrom(ByteArrayInputStream(out.toByteArray()))!!

        val byName = container.files.associate { it.entry.name to it.entry }
        // base.apk → the literal "base" wire name, BASE role.
        assertThat(byName["base"]?.role).isEqualTo(ApkFileRole.BASE)
        // splits keep their name (without .apk) and carry derived tags.
        assertThat(byName["split_config.arm64_v8a"]?.role).isEqualTo(ApkFileRole.CONFIG)
        assertThat(byName["split_config.arm64_v8a"]?.abi).isEqualTo("arm64_v8a")
        assertThat(byName["split_config.xxhdpi"]?.density).isEqualTo("xxhdpi")
        assertThat(byName["split_config.en"]?.lang).isEqualTo("en")
        // The container header carries the app identity.
        assertThat(container.header.packageName).isEqualTo("com.example.app")
        assertThat(container.header.versionCode).isEqualTo(7L)
    }

    @Test
    fun `a single base-only app is available`() = runTest {
        val app = InstalledApp("com.solo.app", "Solo", 1L, listOf(file("base.apk", 10)))
        val provider = installedAppApkProviders(listOf(app)) { ByteArrayInputStream(ByteArray(10)) }.single()
        assertThat(provider.available()).isTrue()
    }

    @Test
    fun `an app with no base file self-omits (available is false)`() = runTest {
        // Only a split, no base — a partial set must never ship a broken half-container.
        val app = InstalledApp(
            packageName = "com.broken.app",
            label = "Broken",
            versionCode = 1L,
            files = listOf(file("split_config.arm64_v8a.apk", 10)),
        )
        val provider = installedAppApkProviders(listOf(app)) { ByteArrayInputStream(ByteArray(10)) }.single()
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `an app with a zero-length file self-omits (available is false)`() = runTest {
        val app = InstalledApp(
            packageName = "com.empty.app",
            label = "Empty",
            versionCode = 1L,
            files = listOf(file("base.apk", 0)),
        )
        val provider = installedAppApkProviders(listOf(app)) { ByteArrayInputStream(ByteArray(0)) }.single()
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `the opener is invoked with each declared file so bytes stream from disk coordinates`() = runTest {
        val opened = mutableListOf<String>()
        val app = InstalledApp(
            packageName = "com.example.app",
            label = "Example",
            versionCode = 1L,
            files = listOf(file("base.apk", 3), file("split_config.de.apk", 3)),
        )
        val provider = installedAppApkProviders(listOf(app)) { installed ->
            opened += installed.name
            ByteArrayInputStream(ByteArray(3))
        }.single()
        provider.exportTo(java.io.ByteArrayOutputStream())
        assertThat(opened).containsExactly("base.apk", "split_config.de.apk").inOrder()
    }

    @Test
    fun `granted runtime permissions are threaded into the exported container header`() = runTest {
        // ADR-006 D5 / Phase 5b: the wire `capturedPermissions` comes from InstalledApp.grantedRuntimePermissions.
        val app = InstalledApp(
            packageName = "com.example.app",
            label = "Example",
            versionCode = 1L,
            files = listOf(file("base.apk", 4)),
            grantedRuntimePermissions = listOf("android.permission.INTERNET", "android.permission.CAMERA"),
        )
        val provider = installedAppApkProviders(listOf(app)) { ByteArrayInputStream(ByteArray(4)) }.single()
        val out = java.io.ByteArrayOutputStream()
        provider.exportTo(out)
        val header = ApkCodec.readHeaderFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(header?.capturedPermissions)
            .containsExactly("android.permission.INTERNET", "android.permission.CAMERA")
    }

    @Test
    fun `an app with no captured permissions exports an empty capturedPermissions list`() = runTest {
        val app = InstalledApp("com.solo.app", "Solo", 1L, listOf(file("base.apk", 4)))
        val provider = installedAppApkProviders(listOf(app)) { ByteArrayInputStream(ByteArray(4)) }.single()
        val out = java.io.ByteArrayOutputStream()
        provider.exportTo(out)
        assertThat(ApkCodec.readHeaderFrom(ByteArrayInputStream(out.toByteArray()))?.capturedPermissions)
            .isEmpty()
    }

    // --- isUserAppFlags: security-relevant invariant that gates which apps appear in the carry list ---

    @Test
    fun `isUserAppFlags returns false for FLAG_SYSTEM (0x00000001)`() {
        // A pure system app must never appear in the carry list.
        assertThat(isUserAppFlags(0x00000001)).isFalse()
    }

    @Test
    fun `isUserAppFlags returns false for FLAG_UPDATED_SYSTEM_APP (0x00000080)`() {
        // An updated system app (e.g. a replaced framework package) must also be excluded.
        assertThat(isUserAppFlags(0x00000080)).isFalse()
    }

    @Test
    fun `isUserAppFlags returns false when both system flags are set`() {
        assertThat(isUserAppFlags(0x00000001 or 0x00000080)).isFalse()
    }

    @Test
    fun `isUserAppFlags returns false when FLAG_SYSTEM is combined with other flags`() {
        // Other flag bits (e.g. FLAG_DEBUGGABLE = 0x2) must not mask the system filter.
        assertThat(isUserAppFlags(0x00000001 or 0x00000002)).isFalse()
    }

    @Test
    fun `isUserAppFlags returns true for a plain user app (flags = 0)`() {
        assertThat(isUserAppFlags(0)).isTrue()
    }

    @Test
    fun `isUserAppFlags returns true for a user app with non-system flags set`() {
        // FLAG_DEBUGGABLE alone must not trigger the system-app exclusion.
        assertThat(isUserAppFlags(0x00000002)).isTrue()
    }

}
