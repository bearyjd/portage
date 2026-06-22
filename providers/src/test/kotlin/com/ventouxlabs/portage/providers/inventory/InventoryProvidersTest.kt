/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.inventory

import com.ventouxlabs.portage.model.ItemStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private class FakeInventorySource(
    private val apps: List<AppRecord> = emptyList(),
    private val installed: Set<String> = emptySet(),
) : InventorySource {
    override fun installedUserApps(): List<AppRecord> = apps
    override fun installedPackageNames(): Set<String> = installed
}

class InventoryProvidersTest {

    private val fdroidApp = AppRecord("org.fossify.gallery", 421, "org.fdroid.fdroid", "Fossify Gallery")
    private val playApp = AppRecord("com.banking.app", 7, "com.android.vending", "Bank")
    private val auroraApp = AppRecord("com.maps.app", 12, "com.aurora.store", "Maps")
    private val sideloaded = AppRecord("dev.tool.apk", 3, null, "Sideloaded Tool")

    @Test
    fun `available only when there are user apps to list`() = runTest {
        assertThat(AppInventoryExportProvider(FakeInventorySource()).available()).isFalse()
        assertThat(AppInventoryExportProvider(FakeInventorySource(listOf(fdroidApp))).available()).isTrue()
    }

    @Test
    fun `export and apply round trip the inventory into install actions`() = runTest {
        val out = ByteArrayOutputStream()
        AppInventoryExportProvider(FakeInventorySource(listOf(fdroidApp, playApp, auroraApp, sideloaded)))
            .exportTo(out)

        var actions: List<InstallAction> = emptyList()
        val outcome = AppInventoryApplyProvider(FakeInventorySource()) { actions = it }
            .apply(ByteArrayInputStream(out.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(actions.map { it.packageName }).containsExactly(
            "org.fossify.gallery", "com.banking.app", "com.maps.app", "dev.tool.apk",
        ).inOrder()
    }

    @Test
    fun `install actions deep-link per source store`() {
        assertThat(requireNotNull(InstallAction.from(fdroidApp)).uri)
            .isEqualTo("https://f-droid.org/packages/org.fossify.gallery")
        assertThat(requireNotNull(InstallAction.from(playApp)).uri)
            .isEqualTo("https://play.google.com/store/apps/details?id=com.banking.app")
        assertThat(requireNotNull(InstallAction.from(auroraApp)).uri)
            .isEqualTo("market://details?id=com.maps.app")
        assertThat(requireNotNull(InstallAction.from(sideloaded)).uri)
            .isEqualTo("market://details?id=dev.tool.apk")

        assertThat(requireNotNull(InstallAction.from(fdroidApp)).store).isEqualTo(InstallStore.FDROID)
        assertThat(requireNotNull(InstallAction.from(playApp)).store).isEqualTo(InstallStore.PLAY)
        assertThat(requireNotNull(InstallAction.from(auroraApp)).store).isEqualTo(InstallStore.AURORA)
        assertThat(requireNotNull(InstallAction.from(sideloaded)).store).isEqualTo(InstallStore.UNKNOWN)
    }

    @Test
    fun `a hostile package name is dropped, never turned into a URI`() {
        // The packageName arrives from the peer — scheme/query smuggling must die here
        // (security review 2026-06-11, HIGH: validate-or-drop on the package grammar).
        val hostile = listOf(
            "a&url=https://evil.example", // query smuggling
            "../x",                       // traversal junk
            "x ",                         // trailing space
            "évil.app",                   // non-ASCII
            "noseparator",                // single segment is not a package
            "",                           // empty
            "a..b",                       // empty segment
            "https://evil.example/#",     // outright URL
        )
        hostile.forEach { name ->
            assertThat(InstallAction.from(AppRecord(name, 1, null, "X"))).isNull()
        }
    }

    @Test
    fun `apply drops invalid package names and reports them`() = runTest {
        val out = ByteArrayOutputStream()
        AppInventoryExportProvider(
            FakeInventorySource(listOf(fdroidApp, AppRecord("bad&name", 1, null, "Evil"))),
        ).exportTo(out)

        var actions: List<InstallAction> = emptyList()
        val outcome = AppInventoryApplyProvider(FakeInventorySource()) { actions = it }
            .apply(ByteArrayInputStream(out.toByteArray()))

        assertThat(actions.map { it.packageName }).containsExactly("org.fossify.gallery")
        assertThat(outcome.detail).contains("1 dropped")
    }

    @Test
    fun `already-installed packages are excluded from the reinstall checklist`() = runTest {
        val out = ByteArrayOutputStream()
        AppInventoryExportProvider(FakeInventorySource(listOf(fdroidApp, playApp))).exportTo(out)

        var actions: List<InstallAction> = emptyList()
        val receiverSide = FakeInventorySource(installed = setOf("com.banking.app"))
        val outcome = AppInventoryApplyProvider(receiverSide) { actions = it }
            .apply(ByteArrayInputStream(out.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(actions.map { it.packageName }).containsExactly("org.fossify.gallery")
        assertThat(outcome.detail).contains("1 already installed")
    }

    @Test
    fun `duplicate package names in the inventory collapse to one install action`() = runTest {
        val out = ByteArrayOutputStream()
        AppInventoryExportProvider(
            FakeInventorySource(listOf(fdroidApp, fdroidApp.copy(label = "Same pkg, other label"))),
        ).exportTo(out)

        var actions: List<InstallAction> = emptyList()
        AppInventoryApplyProvider(FakeInventorySource()) { actions = it }
            .apply(ByteArrayInputStream(out.toByteArray()))

        assertThat(actions.map { it.packageName }).containsExactly("org.fossify.gallery")
    }

    @Test
    fun `an unreadable payload is a WRITE_ERROR with no actions emitted`() = runTest {
        var called = false
        val outcome = AppInventoryApplyProvider(FakeInventorySource()) { called = true }
            .apply(ByteArrayInputStream("not json".toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(called).isFalse()
    }
}
