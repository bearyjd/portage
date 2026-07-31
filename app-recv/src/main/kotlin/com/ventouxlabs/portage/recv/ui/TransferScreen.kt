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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.providers.roles.RestorableRole
import com.ventouxlabs.portage.providers.roles.RoleRestoreCandidate
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.inventory.InstallStore
import com.ventouxlabs.portage.providers.permission.PermissionAllowlist
import com.ventouxlabs.portage.providers.relay.RelayApp
import com.ventouxlabs.portage.providers.relay.RelayRestorePrompt
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.recv.FailedItem
import com.ventouxlabs.portage.recv.ItemPhase
import com.ventouxlabs.portage.recv.ItemProgress
import com.ventouxlabs.portage.recv.OptInPermissions
import com.ventouxlabs.portage.recv.RestoredPermissions
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt
import com.ventouxlabs.portage.recv.ui.theme.LocalSpacing

/**
 * Progress. Swiss numerals carry the headline — "completed / total" over a single
 * determinate rule — and beneath it every selected item gets its own status line, so a
 * failed item is visible the moment it fails, not at the end.
 */
@Composable
fun TransferringScreen(
    items: List<ItemProgress>,
    modifier: Modifier = Modifier,
) {
    val s = LocalSpacing.current
    val total = items.size
    val completed = items.count { it.phase == ItemPhase.DONE || it.phase == ItemPhase.FAILED }
    val fraction = if (total > 0) completed.toFloat() / total.toFloat() else 0f
    val animated by animateFloatAsState(targetValue = fraction, label = "progress")

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = s.gutter),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "04 · CARRYING",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.lg))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = completed.toString(),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = " / $total",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = s.xs),
            )
        }
        Spacer(Modifier.height(s.md))
        SwissDeterminateRule(fraction = animated)
        Spacer(Modifier.height(s.lg))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(items, key = { it.itemId }) { item ->
                ItemProgressRow(item)
            }
        }
        Spacer(Modifier.height(s.md))
        Text(
            text = "Keep both phones close and awake.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One item's status line: name left, phase word right, provider detail underneath. */
@Composable
private fun ItemProgressRow(item: ItemProgress) {
    val s = LocalSpacing.current
    val failed = item.phase == ItemPhase.FAILED
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = s.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = phaseWord(item.phase),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (failed) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (item.phase == ItemPhase.RECEIVING && item.totalBytes > 0) {
                    Text(
                        text = "${formatBytes(item.bytesReceived)} / ${formatBytes(item.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HairlineDivider()
    }
}

private fun phaseWord(phase: ItemPhase): String = when (phase) {
    ItemPhase.PENDING -> "WAITING"
    ItemPhase.RECEIVING -> "RECEIVING"
    ItemPhase.APPLYING -> "APPLYING"
    ItemPhase.DONE -> "DONE"
    ItemPhase.FAILED -> "FAILED"
}

// KEEP BYTE-IDENTICAL with app-send PairingScreen.formatBytes. No shared UI module exists yet;
// the real dedup lands with the ADR-007 :feature-* extraction (deferred), not before.
/** 1.5 KB / 3.4 MB / 1.2 GB — one decimal per tier; a glance value, not accounting. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * Done summary. The moved count dominates, then a line noting app data isn't carried. When App
 * Inventory was applied, the per-app reinstall list follows as one-tap store deep links (the
 * receiver never installs silently, PRP §2); when a bonded-Bluetooth roster was applied, the
 * "re-pair each here" list follows (PRP-07 Phase 1: the receiver shows the list and never bonds —
 * link keys are non-transferable, so re-pairing each device is unavoidable and honest). The layout
 * scrolls so a long list fits.
 */
@Composable
fun DoneScreen(
    moved: Int,
    skipped: Int,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    installActions: List<InstallAction> = emptyList(),
    repairEntries: List<RePairEntry> = emptyList(),
    relayPrompts: List<RelayRestorePrompt> = emptyList(),
    apkInstallPrompts: List<ApkInstallPrompt> = emptyList(),
    restoredPermissions: List<RestoredPermissions> = emptyList(),
    optInPermissions: List<OptInPermissions> = emptyList(),
    roleCandidates: List<RoleRestoreCandidate> = emptyList(),
    restoredRoles: List<RestorableRole> = emptyList(),
    failedItems: List<FailedItem> = emptyList(),
    onInstall: (InstallAction) -> Unit = {},
    onInstallApk: (ApkInstallPrompt) -> Unit = {},
    onGrantOptIn: (packageName: String, permissions: List<String>) -> Unit = { _, _ -> },
    onRestoreRole: (RestorableRole, String) -> Unit = { _, _ -> },
    onOpenBluetoothSettings: () -> Unit = {},
    onOpenRelayApp: (RelayRestorePrompt) -> Unit = {},
    backupActionLabel: String = "Open backup settings",
    onOpenBackup: (() -> Unit)? = null,
) {
    val s = LocalSpacing.current
    if (installActions.isEmpty() && repairEntries.isEmpty() &&
        relayPrompts.isEmpty() && apkInstallPrompts.isEmpty() && restoredPermissions.isEmpty() &&
        optInPermissions.isEmpty() && failedItems.isEmpty()
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = s.gutter),
            verticalArrangement = Arrangement.Center,
        ) {
            DoneSummary(
                moved = moved,
                skipped = skipped,
                backupActionLabel = backupActionLabel,
                onOpenBackup = onOpenBackup,
            )
            Spacer(Modifier.height(s.xl))
            SwissPrimaryButton(text = "Done", onClick = onDone, fullWidth = true)

        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = s.gutter, end = s.gutter, top = s.lg, bottom = s.lg),
        ) {
            item {
                DoneSummary(
                    moved = moved,
                    skipped = skipped,
                    backupActionLabel = backupActionLabel,
                    onOpenBackup = onOpenBackup,
                    hasFailedItems = failedItems.isNotEmpty(),
                )
            }
            if (apkInstallPrompts.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(s.lg))
                    Text(
                        text = "INSTALL · ${apkInstallPrompts.size} ${if (apkInstallPrompts.size == 1) "APP" else "APPS"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = s.sm),
                    )
                    HairlineDivider()
                }
                items(apkInstallPrompts, key = { "apk:${it.sessionId}" }) { prompt ->
                    ApkInstallRow(prompt = prompt, onInstallApk = onInstallApk)
                }
                item {
                    Spacer(Modifier.height(s.md))
                    Text(
                        // GOS-batching UX open question (ADR-006 follow-ups): A16 may not batch multiple
                        // PackageInstaller confirm intents, so this is one tap per app and the copy says so.
                        text = "These came over with your phone. Tap to install — Android asks you to confirm each one.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (restoredPermissions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(s.lg))
                    Text(
                        text = "RESTORED · ${restoredPermissions.size} ${if (restoredPermissions.size == 1) "APP" else "APPS"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = s.sm),
                    )
                    HairlineDivider()
                }
                items(restoredPermissions, key = { "perms:${it.packageName}" }) { restored ->
                    RestoredPermissionsRow(restored = restored)
                }
                item {
                    Spacer(Modifier.height(s.md))
                    Text(
                        text = "These came over silently, so portage switched their permissions back on for you — just like on your old phone.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (optInPermissions.isNotEmpty()) {
                // ADVANCED PERMISSIONS (ADR-006 D5, Phase 5d) — opt-in dangerous perms the source app held
                // and this device's copy declares. Collapsed by default and granted ONLY when the user
                // expands and taps: the expand+tap IS the explicit opt-in. Nothing here is granted silently.
                item {
                    Spacer(Modifier.height(s.lg))
                    OptInPermissionsSection(optInPermissions = optInPermissions, onGrantOptIn = onGrantOptIn)
                }
            }
            if (roleCandidates.isNotEmpty() || restoredRoles.isNotEmpty()) {
                // DEFAULT APPS (#122). The shell path applies a role change with NO system confirm
                // dialog, so this tap is the ONLY consent that exists. One tap per role, never a
                // "restore all" — each default is a separate decision.
                item {
                    Spacer(Modifier.height(s.lg))
                    DefaultRolesSection(
                        candidates = roleCandidates,
                        restored = restoredRoles,
                        onRestoreRole = onRestoreRole,
                    )
                }
            }
            if (installActions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(s.lg))
                    Text(
                        text = "REINSTALL · ${installActions.size} ${if (installActions.size == 1) "APP" else "APPS"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = s.sm),
                    )
                    HairlineDivider()
                }
                items(installActions, key = { "install:${it.packageName}" }) { action ->
                    ReinstallRow(action = action, onInstall = onInstall)
                }
                item {
                    Spacer(Modifier.height(s.md))
                    Text(
                        text = "Each opens its store — one tap to install. Nothing installs on its own.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (repairEntries.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(s.lg))
                    Text(
                        text = "RE-PAIR · ${repairEntries.size} ${if (repairEntries.size == 1) "DEVICE" else "DEVICES"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = s.sm),
                    )
                    HairlineDivider()
                }
                items(repairEntries, key = { "bt:${it.address}" }) { entry ->
                    RePairRow(entry = entry, onOpenBluetoothSettings = onOpenBluetoothSettings)
                }
                item {
                    Spacer(Modifier.height(s.md))
                    Text(
                        text = "You were paired to these. Bluetooth pairings can't move between phones — open Bluetooth settings and pair each one again.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (relayPrompts.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(s.lg))
                    Text(
                        text = "RESTORE · ${relayPrompts.size} ${if (relayPrompts.size == 1) "BACKUP" else "BACKUPS"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = s.sm),
                    )
                    HairlineDivider()
                }
                items(relayPrompts, key = { "relay:${it.itemId}" }) { prompt ->
                    RelayRow(prompt = prompt, onOpenRelayApp = onOpenRelayApp)
                }
                item {
                    Spacer(Modifier.height(s.md))
                    Text(
                        text = "Each backup file is here, encrypted by the app and only openable with your passphrase — portage never sees it. Open the app and import it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val tryAgainItems = failedItems.filter { !isTerminal(it.status) }
            val leftBehindItems = failedItems.filter { isTerminal(it.status) }
            if (tryAgainItems.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(s.lg))
                    Text(
                        text = "TRY AGAIN · ${tryAgainItems.size} ${if (tryAgainItems.size == 1) "ITEM" else "ITEMS"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = s.sm),
                    )
                    HairlineDivider()
                }
                items(tryAgainItems, key = { "again:${it.itemId}" }) { item ->
                    FailedItemRow(item = item)
                }
                item {
                    Spacer(Modifier.height(s.md))
                    Text(
                        text = "Sending these again usually works — start another transfer from your old phone.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (leftBehindItems.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(s.lg))
                    Text(
                        text = "LEFT BEHIND · ${leftBehindItems.size} ${if (leftBehindItems.size == 1) "ITEM" else "ITEMS"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = s.sm),
                    )
                    HairlineDivider()
                }
                items(leftBehindItems, key = { "behind:${it.itemId}" }) { item ->
                    FailedItemRow(item = item)
                }
                item {
                    Spacer(Modifier.height(s.md))
                    Text(
                        text = "Sending these again won't change the answer — each row says why.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            HairlineDivider()
            Column(modifier = Modifier.padding(horizontal = s.gutter, vertical = s.md)) {
                SwissPrimaryButton(text = "Done", onClick = onDone, fullWidth = true)
            }
        }
    }
}

/** The moved-count summary, shared by the plain and reinstall-list Done layouts. */
@Composable
private fun DoneSummary(
    moved: Int,
    skipped: Int,
    backupActionLabel: String = "Open backup settings",
    onOpenBackup: (() -> Unit)? = null,
    hasFailedItems: Boolean = false,
) {
    val s = LocalSpacing.current
    Column {
        Text(
            text = "DONE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(s.lg))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = moved.toString(),
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (moved == 1) " thing moved" else " things moved",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = s.sm),
            )
        }
        // When hasFailedItems, the TRY AGAIN / LEFT BEHIND section headers carry the breakdown —
        // showing a lumped "N left behind" would double-label and disagree with the section counts.
        // Suppress it in that case; only the zero-moved empty-state line still renders.
        // (Defensive case "Nothing was picked to carry" is unreachable: ReceiverViewModel.start()
        // returns early when nothing is selected, so Done is never reached with 0 results.)
        if (hasFailedItems && moved == 0) {
            Spacer(Modifier.height(s.sm))
            Text(
                text = "Nothing made it over this time — the rows below say why.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!hasFailedItems && skipped > 0) {
            Spacer(Modifier.height(s.sm))
            Text(
                text = "$skipped left behind",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(s.lg))
        HairlineDivider()
        Spacer(Modifier.height(s.lg))
        Text(
            text = "App data isn't carried — restore it from a system backup.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        // The Seedvault handoff (PRP §3 division of labor): point at the system backup that
        // owns app DATA, right where the user is wondering about it.
        onOpenBackup?.let {
            Spacer(Modifier.height(s.sm))
            SwissTextAction(text = backupActionLabel, onClick = it)
        }
    }
}

/** One restored-permissions row: the carried app over the friendly names of what was switched back on. */
@Composable
private fun RestoredPermissionsRow(restored: RestoredPermissions) {
    val s = LocalSpacing.current
    Column(Modifier.padding(vertical = s.md)) {
        Text(
            text = restored.packageName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(s.xs))
        Text(
            text = restored.permissions.joinToString(", ") { friendlyPermissionName(it) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One failed item: display name + human reason + optional wire detail, with the verdict word right-aligned. */
@Composable
private fun FailedItemRow(item: FailedItem) {
    val s = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = s.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = s.md)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            statusReason(item.status)?.let { reason ->
                Spacer(Modifier.height(s.xs))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.detail?.let { detail ->
                Spacer(Modifier.height(s.xs))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = statusWord(item.status),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The user-facing name for a permission surfaced on the Done screen. The DEFAULT_SAFE specials map to the
 * GrapheneOS toggle names ("Network" / "Sensors"); the opt-in dangerous perms (Phase 5d) map to the
 * permission-GROUP name the system permission dialog uses ("Camera", "Location", …). An unmapped perm
 * falls back to a humanized form of its bare constant suffix — safe, and only reached for a perm we don't
 * yet have a friendly label for.
 */
private fun friendlyPermissionName(permission: String): String = when (permission) {
    PermissionAllowlist.INTERNET -> "Network"
    PermissionAllowlist.OTHER_SENSORS -> "Sensors"
    "android.permission.CAMERA" -> "Camera"
    "android.permission.RECORD_AUDIO" -> "Microphone"
    "android.permission.ACCESS_FINE_LOCATION" -> "Precise location"
    "android.permission.ACCESS_COARSE_LOCATION" -> "Approximate location"
    "android.permission.ACCESS_BACKGROUND_LOCATION" -> "Background location"
    "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS" -> "Contacts"
    "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR" -> "Calendar"
    "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG" -> "Call log"
    "android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS" -> "Phone"
    "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS" -> "SMS"
    "android.permission.BODY_SENSORS" -> "Body sensors"
    "android.permission.ACTIVITY_RECOGNITION" -> "Physical activity"
    "android.permission.POST_NOTIFICATIONS" -> "Notifications"
    "android.permission.READ_MEDIA_IMAGES" -> "Photos"
    "android.permission.READ_MEDIA_VIDEO" -> "Videos"
    "android.permission.READ_MEDIA_AUDIO" -> "Music & audio"
    "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE" -> "Files & media"
    else -> permission.substringAfterLast('.')
        .split('_')
        .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
}

/**
 * The opt-in dangerous-permission review (ADR-006 D5, Phase 5d). Collapsed to a tracked-out header the
 * user can ignore; expanding it and tapping a grant IS the explicit opt-in — nothing is granted until
 * then. portage only ever lists perms it captured from the source app AND this device's installed copy
 * declares (the planner's opt-in set); the ViewModel re-checks that belt before any `pm grant`.
 */
@Composable
private fun OptInPermissionsSection(
    optInPermissions: List<OptInPermissions>,
    onGrantOptIn: (packageName: String, permissions: List<String>) -> Unit,
) {
    val s = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(bottom = s.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ADVANCED PERMISSIONS · ${optInPermissions.size} ${if (optInPermissions.size == 1) "APP" else "APPS"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (expanded) "HIDE" else "REVIEW",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HairlineDivider()
        if (expanded) {
            optInPermissions.forEach { app ->
                OptInAppCard(app = app, onGrantOptIn = onGrantOptIn)
            }
        } else {
            Spacer(Modifier.height(s.md))
            Text(
                text = "These apps had sensitive permissions — like camera or location — on your old phone. portage won't switch those on by itself. Tap to review and choose.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One app's opt-in review: the package over a checkable list of the offered perms, then a "grant selected"
 * (gated on a selection) and a "grant all" affordance. Selection is local UI state keyed on the offered
 * set, so a partial grant — which shrinks [OptInPermissions.permissions] when the row re-emits — resets
 * the checkboxes cleanly. The grant itself is the ViewModel's job; this only reports the user's choice.
 */
@Composable
private fun OptInAppCard(
    app: OptInPermissions,
    onGrantOptIn: (packageName: String, permissions: List<String>) -> Unit,
) {
    val s = LocalSpacing.current
    val selected = remember(app.packageName, app.permissions) { mutableStateListOf<String>() }
    Column(Modifier.padding(top = s.md)) {
        Text(
            text = app.packageName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(s.xs))
        app.permissions.forEach { perm ->
            val checked = perm in selected
            PermissionCheckRow(
                label = friendlyPermissionName(perm),
                checked = checked,
                onToggle = { if (checked) selected.remove(perm) else selected.add(perm) },
            )
        }
        Spacer(Modifier.height(s.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(s.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwissPrimaryButton(
                text = "Grant selected",
                onClick = {
                    onGrantOptIn(app.packageName, selected.toList())
                    selected.clear()
                },
                enabled = selected.isNotEmpty(),
            )
            SwissTextAction(
                text = "Grant all",
                onClick = {
                    // "Grant all" acts on the whole offered list for this app, not the checkbox subset —
                    // clear the selection so the ticks don't linger out of sync with what was just granted.
                    onGrantOptIn(app.packageName, app.permissions.toList())
                    selected.clear()
                },
            )
        }
        Spacer(Modifier.height(s.md))
        HairlineDivider()
    }
}

/** A Swiss-square check row: a filled accent box when checked, a hairline outline when not. */
@Composable
private fun PermissionCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val s = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = s.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.md),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .then(
                    if (checked) {
                        Modifier.background(MaterialTheme.colorScheme.primary, RectangleShape)
                    } else {
                        Modifier.border(s.hairline, MaterialTheme.colorScheme.outline, RectangleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** One reinstall row: app label over its store, the whole row a tap that opens the store. */
@Composable
private fun ReinstallRow(action: InstallAction, onInstall: (InstallAction) -> Unit) {
    val s = LocalSpacing.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onInstall(action) }
                .padding(vertical = s.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(s.xs))
                Text(
                    text = storeName(action.store),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "INSTALL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HairlineDivider()
    }
}

private fun storeName(store: InstallStore): String = when (store) {
    InstallStore.PLAY -> "Play Store"
    InstallStore.FDROID -> "F-Droid"
    InstallStore.AURORA -> "Aurora Store"
    InstallStore.UNKNOWN -> "Any store"
}

/**
 * One carried-APK install row (ADR-006 D3/D6): the app over a "carried with your phone" caption, the
 * whole row a tap that commits its already-sealed `PackageInstaller` session and fires the system
 * install-confirm UI. Our own app installing our own carried bytes — NO shell uid, NO silent install
 * (the silent path is the deferred P6 concern). The package was wire-validated before staging.
 */
@Composable
private fun ApkInstallRow(prompt: ApkInstallPrompt, onInstallApk: (ApkInstallPrompt) -> Unit) {
    val s = LocalSpacing.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onInstallApk(prompt) }
                .padding(vertical = s.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = prompt.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(s.xs))
                Text(
                    text = "Carried with your phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "INSTALL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HairlineDivider()
    }
}

/**
 * One re-pair row: device name over its MAC, the whole row a tap that opens system Bluetooth
 * settings (PRP-07 Phase 1 — the OS owns bonding; portage never bonds and carries no link keys).
 * The name and MAC are pre-validated/sanitized by [RePairEntry.from]; nothing here is shown raw.
 */
@Composable
private fun RePairRow(entry: RePairEntry, onOpenBluetoothSettings: () -> Unit) {
    val s = LocalSpacing.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenBluetoothSettings() }
                .padding(vertical = s.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(s.xs))
                Text(
                    text = "${btKindLabel(entry.devType)} · ${entry.address}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "RE-PAIR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HairlineDivider()
    }
}

/** Human hint from BluetoothDevice.getType(): 1=CLASSIC, 2=LE, 3=DUAL, else unknown. */
private fun btKindLabel(devType: Int): String = when (devType) {
    1 -> "Bluetooth"
    2 -> "Bluetooth LE"
    3 -> "Bluetooth (dual)"
    else -> "Bluetooth device"
}

/**
 * One relayed-backup row (PRP-06): the target app over the restore reminder, the whole row a tap that
 * opens that app so the user can import the file with their passphrase. portage relayed the OPAQUE
 * file only — it never imports it and never holds the passphrase. The app id, name, and note are all
 * pre-validated/sanitized by [RelayRestorePrompt] (derived from the typed RelayApp enum); nothing here
 * is shown raw and the opaque bytes are never surfaced.
 */
@Composable
private fun RelayRow(prompt: RelayRestorePrompt, onOpenRelayApp: (RelayRestorePrompt) -> Unit) {
    val s = LocalSpacing.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenRelayApp(prompt) }
                .padding(vertical = s.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = relayAppLabel(prompt.app),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(s.xs))
                Text(
                    text = prompt.restoreNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "OPEN",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HairlineDivider()
    }
}

/** Friendly label for the target relay app (derived from the typed enum, never a wire string). */
private fun relayAppLabel(app: RelayApp): String = when (app) {
    RelayApp.SIGNAL -> "Signal"
    RelayApp.MOLLY -> "Molly"
    RelayApp.AEGIS -> "Aegis"
    RelayApp.OTHER -> "App backup"
}

internal fun statusWord(status: ItemStatus): String = when (status) {
    ItemStatus.OK -> "MOVED"
    ItemStatus.SKIPPED -> "SKIPPED"
    ItemStatus.HASH_MISMATCH -> "DAMAGED"
    ItemStatus.WRITE_ERROR -> "NOT SAVED"
    ItemStatus.UNKNOWN_KIND -> "UNKNOWN"
    ItemStatus.OVERSIZE -> "TOO BIG"
}

internal fun statusReason(status: ItemStatus): String? = when (status) {
    ItemStatus.OK -> null
    ItemStatus.SKIPPED -> "This phone chose to leave it."
    ItemStatus.HASH_MISMATCH -> "Didn't arrive intact — sending it again usually fixes this."
    ItemStatus.WRITE_ERROR -> "This phone couldn't save it — worth sending again."
    ItemStatus.UNKNOWN_KIND ->
        "This phone's portage doesn't know this kind of item — update portage here, then send again."
    ItemStatus.OVERSIZE -> "Too big to carry — this phone caps what one item can bring."
}

internal fun isTerminal(status: ItemStatus): Boolean = when (status) {
    ItemStatus.OK, ItemStatus.HASH_MISMATCH, ItemStatus.WRITE_ERROR -> false
    ItemStatus.SKIPPED, ItemStatus.UNKNOWN_KIND, ItemStatus.OVERSIZE -> true
}

/** Human label for a carried default-app role. */
private fun roleLabel(role: RestorableRole): String = when (role) {
    RestorableRole.BROWSER -> "Browser"
    RestorableRole.DIALER -> "Phone"
    RestorableRole.HOME -> "Home screen"
}

/**
 * The default-app restore surface (#122).
 *
 * Consent lives HERE and nowhere else. Restoring a role through the bridge shows **no system
 * confirm dialog** — the platform will not ask on portage's behalf — so this tap is the only thing
 * standing between "portage knows your old default" and "portage changed your default". Hence:
 * one explicit tap per role, no "restore all", and nothing pre-selected.
 *
 * Only roles whose app is actually installed here are ever offered (filtered in the apply
 * provider), so a tap cannot point a role at something missing. A role that fails to apply stays
 * offered rather than moving to "set" — portage must not claim a default it did not set.
 */
@Composable
private fun DefaultRolesSection(
    candidates: List<RoleRestoreCandidate>,
    restored: List<RestorableRole>,
    onRestoreRole: (RestorableRole, String) -> Unit,
) {
    val s = LocalSpacing.current
    Column {
        Text(
            text = "DEFAULT APPS · ${candidates.size + restored.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(s.sm))
        HairlineDivider()
        Spacer(Modifier.height(s.md))
        Text(
            text = "These were your defaults on the old phone. portage won't switch them over by " +
                "itself — choose each one you want.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        candidates.forEach { candidate ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = s.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = roleLabel(candidate.role),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = candidate.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SwissTextAction(
                    text = "SET",
                    onClick = { onRestoreRole(candidate.role, candidate.packageName) },
                )
            }
        }
        restored.forEach { role ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = s.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = roleLabel(role),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "SET",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
