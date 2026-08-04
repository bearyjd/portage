/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.inventory.InstallStore
import com.ventouxlabs.portage.providers.relay.RelayApp
import com.ventouxlabs.portage.providers.relay.RelayRestorePrompt
import com.ventouxlabs.portage.recv.FailedItem
import com.ventouxlabs.portage.recv.RestoredPermissions
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Records what a Done section emits into a LazyColumn, without composing anything: the `@Composable`
 * content lambdas are captured and never invoked. That keeps these tests on the JVM — this repo has
 * no Robolectric and no Compose UI test harness, so counting emissions is the only way to hold a
 * claim about what the Done screen renders.
 */
private class RecordingLazyListScope : LazyListScope {
    /** One per `item {}` block — for a section, the header rule and the closing note. */
    var singleItems = 0
        private set

    /** Total rows requested across `items(count, …)` calls. */
    var rows = 0
        private set

    /** Row keys in emission order, so the LazyColumn key scheme is checkable. */
    val keys = mutableListOf<Any>()

    /** Nothing at all reached the list. */
    val emittedNothing: Boolean get() = singleItems == 0 && rows == 0

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        singleItems++
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        rows += count
        if (key != null) repeat(count) { keys += key(it) }
    }

    @ExperimentalFoundationApi
    @Suppress("OVERRIDE_DEPRECATION") // deprecated upstream; overridden only to satisfy the interface
    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) = throw UnsupportedOperationException("no Done section uses stickyHeader")
}

private fun record(block: LazyListScope.() -> Unit) = RecordingLazyListScope().apply(block)

class DoneSectionsTest {

    @Test fun `every section emits nothing at all when its list is empty`() {
        // The honesty property doneSection exists to hold. A section that rendered its header on an
        // empty list would put "RE-PAIR · 0 DEVICES" on screen — portage claiming work it did not do.
        assertThat(record { apkInstallSection(emptyList()) {} }.emittedNothing).isTrue()
        assertThat(record { restoredPermissionsSection(emptyList()) }.emittedNothing).isTrue()
        assertThat(record { reinstallSection(emptyList()) {} }.emittedNothing).isTrue()
        assertThat(record { rePairSection(emptyList()) {} }.emittedNothing).isTrue()
        assertThat(record { relaySection(emptyList()) {} }.emittedNothing).isTrue()
        assertThat(record { tryAgainSection(emptyList()) }.emittedNothing).isTrue()
        assertThat(record { leftBehindSection(emptyList()) }.emittedNothing).isTrue()
    }

    @Test fun `a populated section emits a header, one row per entry, and a closing note`() {
        val scope = record {
            reinstallSection(listOf(installAction("com.a"), installAction("com.b"))) {}
        }
        assertThat(scope.singleItems).isEqualTo(2) // header rule + closing note
        assertThat(scope.rows).isEqualTo(2) // one per app
    }

    @Test fun `row keys are section-prefixed so two sections cannot collide on one item id`() {
        // FailedItem 1 lands in exactly one partition, but the prefixes are what make that safe to
        // rely on — an unprefixed key would risk a Compose duplicate-key crash.
        val scope = record {
            tryAgainSection(listOf(failed(1), failed(2)))
            leftBehindSection(listOf(failed(3)))
        }
        assertThat(scope.keys).containsExactly("again:1", "again:2", "behind:3").inOrder()
    }

    @Test fun `each section keys its rows on its own identity field`() {
        assertThat(record { apkInstallSection(listOf(apkPrompt(7))) {} }.keys)
            .containsExactly("apk:7")
        assertThat(record { restoredPermissionsSection(listOf(restored("com.a"))) }.keys)
            .containsExactly("perms:com.a")
        assertThat(record { reinstallSection(listOf(installAction("com.b"))) {} }.keys)
            .containsExactly("install:com.b")
        assertThat(record { rePairSection(listOf(rePair("AA:BB:CC:DD:EE:FF"))) {} }.keys)
            .containsExactly("bt:AA:BB:CC:DD:EE:FF")
        assertThat(record { relaySection(listOf(relayPrompt(4))) {} }.keys)
            .containsExactly("relay:4")
    }

    @Test fun `sectionHeading uses the singular only for a count of exactly one`() {
        assertThat(sectionHeading("REINSTALL", 1, "APP", "APPS")).isEqualTo("REINSTALL · 1 APP")
        assertThat(sectionHeading("REINSTALL", 2, "APP", "APPS")).isEqualTo("REINSTALL · 2 APPS")
        // A section with no entries never renders, but the heading must still not read "0 APP".
        assertThat(sectionHeading("REINSTALL", 0, "APP", "APPS")).isEqualTo("REINSTALL · 0 APPS")
    }

    @Test fun `sectionHeading carries each section's own unit noun`() {
        assertThat(sectionHeading("RE-PAIR", 1, "DEVICE", "DEVICES")).isEqualTo("RE-PAIR · 1 DEVICE")
        assertThat(sectionHeading("RESTORE", 3, "BACKUP", "BACKUPS")).isEqualTo("RESTORE · 3 BACKUPS")
        assertThat(sectionHeading("LEFT BEHIND", 4, "ITEM", "ITEMS")).isEqualTo("LEFT BEHIND · 4 ITEMS")
    }
}

private fun installAction(pkg: String) =
    InstallAction(packageName = pkg, label = "App", store = InstallStore.FDROID, uri = "https://f-droid.org")

private fun apkPrompt(sessionId: Int) =
    ApkInstallPrompt(packageName = "com.apk", label = "Apk", sessionId = sessionId)

private fun restored(pkg: String) = RestoredPermissions(pkg, listOf("android.permission.CAMERA"))

private fun rePair(address: String) =
    RePairEntry(address = address, name = "Buds", devType = 2, majorClass = 0)

private fun relayPrompt(itemId: Int) = RelayRestorePrompt(
    itemId = itemId,
    app = RelayApp.SIGNAL,
    targetPackage = "org.thoughtcrime.securesms",
    originalName = "signal.backup",
    restoreNote = "Open Signal and import it.",
)

private fun failed(itemId: Int) =
    FailedItem(itemId = itemId, displayName = "Item $itemId", status = ItemStatus.WRITE_ERROR, detail = null)
