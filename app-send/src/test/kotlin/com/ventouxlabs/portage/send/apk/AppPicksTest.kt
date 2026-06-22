/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.apk

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.providers.apk.ApkCodec
import com.ventouxlabs.portage.providers.apk.ApkFileRole
import com.ventouxlabs.portage.providers.apk.InstalledApkFile
import com.ventouxlabs.portage.providers.apk.InstalledApp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class AppPicksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Write a real APK file on disk so the default FileInputStream opener can read it. */
    private fun onDiskApp(packageName: String, label: String, vararg files: Pair<String, Int>): InstalledApp {
        val installed = files.map { (name, size) ->
            val file = File(tmp.root, "$packageName-$name").apply { writeBytes(ByteArray(size) { it.toByte() }) }
            InstalledApkFile(name = name, absolutePath = file.absolutePath, length = file.length())
        }
        return InstalledApp(packageName, label, versionCode = 1L, files = installed)
    }

    @Test
    fun `one provider is built per selected app with the APK kind`() = runTest {
        val apps = listOf(
            onDiskApp("com.a.app", "Alpha", "base.apk" to 10),
            onDiskApp("com.b.app", "Bravo", "base.apk" to 10),
        )
        val providers = apkExportProviders(apps)

        assertThat(providers).hasSize(2)
        assertThat(providers.map { it.kind }).containsExactly(ItemKind.APK, ItemKind.APK)
        assertThat(providers.map { it.displayName }).containsExactly("Alpha", "Bravo")
    }

    @Test
    fun `no selected apps means no providers`() {
        assertThat(apkExportProviders(emptyList())).isEmpty()
    }

    @Test
    fun `available reflects readability — a real base file is available`() = runTest {
        val provider = apkExportProviders(
            listOf(onDiskApp("com.a.app", "Alpha", "base.apk" to 64)),
        ).single()
        assertThat(provider.available()).isTrue()
    }

    @Test
    fun `the default opener streams the real APK file bytes from disk`() = runTest {
        val app = onDiskApp(
            "com.example.app", "Example",
            "base.apk" to 32,
            "split_config.arm64_v8a.apk" to 16,
        )
        val provider = apkExportProviders(listOf(app)).single()

        val out = ByteArrayOutputStream()
        provider.exportTo(out)
        val container = ApkCodec.readFrom(ByteArrayInputStream(out.toByteArray()))!!

        // The base file rode under the literal "base" wire name, the split kept its derived tags.
        val byName = container.files.associate { it.entry.name to it.entry }
        assertThat(byName.keys).containsExactly("base", "split_config.arm64_v8a")
        assertThat(byName["base"]?.role).isEqualTo(ApkFileRole.BASE)
        assertThat(byName["split_config.arm64_v8a"]?.abi).isEqualTo("arm64_v8a")
        assertThat(container.header.packageName).isEqualTo("com.example.app")
    }

    @Test
    fun `an app whose files are missing on disk self-omits`() = runTest {
        // Declared length but the file does not exist — the default opener throws at export, and
        // ManifestBuilder excludes it; available() is still true (the declared lengths look valid), so
        // the staging-time exclusion is what protects against a vanished file.
        val ghost = InstalledApp(
            packageName = "com.ghost.app",
            label = "Ghost",
            versionCode = 1L,
            files = listOf(InstalledApkFile("base.apk", File(tmp.root, "missing.apk").absolutePath, 10)),
        )
        val provider = apkExportProviders(listOf(ghost)).single()

        val threw = runCatching { provider.exportTo(ByteArrayOutputStream()) }.isFailure
        assertThat(threw).isTrue()
    }
}
