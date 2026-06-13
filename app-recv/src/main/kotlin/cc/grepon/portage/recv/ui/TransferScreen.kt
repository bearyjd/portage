/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.grepon.portage.providers.bluetooth.RePairEntry
import cc.grepon.portage.providers.inventory.InstallAction
import cc.grepon.portage.providers.inventory.InstallStore
import cc.grepon.portage.providers.relay.RelayApp
import cc.grepon.portage.providers.relay.RelayRestorePrompt
import cc.grepon.portage.recv.ItemPhase
import cc.grepon.portage.recv.ItemProgress
import cc.grepon.portage.recv.ui.theme.LocalSpacing

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
        DeterminateRule(fraction = animated)
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
            )
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

/** A 2dp track that fills with the accent — the Swiss reading of a progress bar. */
@Composable
private fun DeterminateRule(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
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
    onInstall: (InstallAction) -> Unit = {},
    onOpenBluetoothSettings: () -> Unit = {},
    onOpenRelayApp: (RelayRestorePrompt) -> Unit = {},
    backupActionLabel: String = "Open backup settings",
    onOpenBackup: (() -> Unit)? = null,
) {
    val s = LocalSpacing.current
    if (installActions.isEmpty() && repairEntries.isEmpty() && relayPrompts.isEmpty()) {
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
                )
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
        if (skipped > 0) {
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
