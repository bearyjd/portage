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

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.inventory.InstallStore
import com.ventouxlabs.portage.providers.relay.RelayApp
import com.ventouxlabs.portage.providers.relay.RelayRestorePrompt
import com.ventouxlabs.portage.providers.roles.RestorableRole
import com.ventouxlabs.portage.providers.roles.RoleRestoreCandidate
import com.ventouxlabs.portage.recv.FailedItem
import com.ventouxlabs.portage.recv.OptInPermissions
import com.ventouxlabs.portage.recv.ReceiverState
import com.ventouxlabs.portage.recv.RestoredPermissions
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt
import com.ventouxlabs.portage.recv.ui.theme.PortageTheme

/*
 * Done-screen previews — DEBUG SOURCE SET ONLY, never compiled into a release APK.
 *
 * These exist because the Done screen has no JVM-testable rendering path (no Robolectric, no
 * Compose UI test harness in this repo). DoneSectionsTest can prove a section emits the right
 * NUMBER of rows; only a rendered frame shows whether the result is legible. `ui-tooling` is
 * already a debugImplementation, so its PreviewActivity can launch these on a real device:
 *
 *   adb shell am start -n com.ventouxlabs.portage.recv.debug/androidx.compose.ui.tooling.PreviewActivity \
 *     -e composable com.ventouxlabs.portage.recv.ui.DoneScreenPreviewKt.DoneEverySectionPreview
 *
 * No new dependency, no production code touched.
 *
 * Each preview goes through [PreviewHost], which reproduces the Scaffold that ReceiverApp wraps
 * every screen in. Without it the previews render edge-to-edge and the summary's "DONE" eyebrow
 * collides with the system status bar — a harness artifact that looks exactly like a real insets
 * bug. The host has to match the real one or the visual pass lies to you.
 */
@Composable
private fun PreviewHost(content: @Composable (Modifier) -> Unit) = PortageTheme {
    Scaffold { inner -> content(Modifier.padding(inner)) }
}

/** Every section populated at once — the densest state the Done screen can reach. */
@Preview(showBackground = true, widthDp = 412, heightDp = 3200)
@Composable
fun DoneEverySectionPreview() = PreviewHost { m ->
    DoneScreen(
        modifier = m,
        moved = 7,
        skipped = 2,
        onDone = {},
        apkInstallPrompts = listOf(
            ApkInstallPrompt("org.thoughtcrime.securesms", "Signal", 11),
            ApkInstallPrompt("com.beemdevelopment.aegis", "Aegis Authenticator", 12),
        ),
        restoredPermissions = listOf(
            RestoredPermissions("org.thoughtcrime.securesms", listOf("android.permission.INTERNET")),
        ),
        optInPermissions = listOf(
            OptInPermissions(
                "org.thoughtcrime.securesms",
                listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            ),
        ),
        roleCandidates = listOf(
            RoleRestoreCandidate(RestorableRole.BROWSER, "org.mozilla.firefox"),
            RoleRestoreCandidate(RestorableRole.DIALER, "com.example.dialer"),
        ),
        restoredRoles = listOf(RestorableRole.HOME),
        roleAttempts = mapOf(
            RestorableRole.DIALER to ReceiverState.RoleAttempt.UNAVAILABLE,
        ),
        installActions = listOf(
            InstallAction("com.example.notes", "Notes", InstallStore.FDROID, "https://f-droid.org"),
        ),
        repairEntries = listOf(
            RePairEntry("AA:BB:CC:DD:EE:FF", "Pixel Buds Pro", devType = 2, majorClass = 0),
        ),
        relayPrompts = listOf(
            RelayRestorePrompt(
                itemId = 21,
                app = RelayApp.SIGNAL,
                targetPackage = "org.thoughtcrime.securesms",
                originalName = "signal-2026-08-04.backup",
                restoreNote = "Open Signal and import this with your passphrase.",
            ),
        ),
        failedItems = listOf(
            FailedItem(31, "Call history", ItemStatus.WRITE_ERROR, "database locked"),
            FailedItem(32, "Wallpaper", ItemStatus.OVERSIZE, null),
        ),
        onOpenBackup = {},
    )
}

/** The summary-only branch: nothing to work through, so the count is centered and alone. */
@Preview(showBackground = true, widthDp = 412, heightDp = 900)
@Composable
fun DoneSummaryOnlyPreview() = PreviewHost { m ->
    DoneScreen(modifier = m, moved = 12, skipped = 0, onDone = {}, onOpenBackup = {})
}

/** Both failure partitions, nothing else — the "TRY AGAIN" vs "LEFT BEHIND" split. */
@Preview(showBackground = true, widthDp = 412, heightDp = 1200)
@Composable
fun DoneFailuresOnlyPreview() = PreviewHost { m ->
    DoneScreen(
        modifier = m,
        moved = 0,
        skipped = 3,
        onDone = {},
        failedItems = listOf(
            FailedItem(41, "Contacts", ItemStatus.HASH_MISMATCH, null),
            FailedItem(42, "Text messages", ItemStatus.WRITE_ERROR, "no default SMS role"),
            FailedItem(43, "Wallpaper", ItemStatus.OVERSIZE, null),
            FailedItem(44, "Ringtone", ItemStatus.UNKNOWN_KIND, null),
        ),
        onOpenBackup = {},
    )
}
