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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.providers.roles.RestorableRole
import com.ventouxlabs.portage.providers.roles.RoleRestoreCandidate
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.relay.RelayRestorePrompt
import com.ventouxlabs.portage.recv.FailedItem
import com.ventouxlabs.portage.recv.ReceiverState
import com.ventouxlabs.portage.recv.OptInPermissions
import com.ventouxlabs.portage.recv.RestoredPermissions
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt
import com.ventouxlabs.portage.recv.ui.theme.LocalSpacing

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
    roleAttempts: Map<RestorableRole, ReceiverState.RoleAttempt> = emptyMap(),
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
    // Every list that can render a section below MUST be checked here. A section missing from this
    // guard is invisible exactly when it is the only thing that happened — the summary-only branch
    // returns before the section list is ever reached.
    if (installActions.isEmpty() && repairEntries.isEmpty() &&
        relayPrompts.isEmpty() && apkInstallPrompts.isEmpty() && restoredPermissions.isEmpty() &&
        optInPermissions.isEmpty() && failedItems.isEmpty() &&
        roleCandidates.isEmpty() && restoredRoles.isEmpty()
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
            apkInstallSection(prompts = apkInstallPrompts, onInstallApk = onInstallApk)
            restoredPermissionsSection(restored = restoredPermissions)
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
                        attempts = roleAttempts,
                        onRestoreRole = onRestoreRole,
                    )
                }
            }
            reinstallSection(actions = installActions, onInstall = onInstall)
            rePairSection(entries = repairEntries, onOpenBluetoothSettings = onOpenBluetoothSettings)
            relaySection(prompts = relayPrompts, onOpenRelayApp = onOpenRelayApp)
            tryAgainSection(items = failedItems.filter { !isTerminal(it.status) })
            leftBehindSection(items = failedItems.filter { isTerminal(it.status) })
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
