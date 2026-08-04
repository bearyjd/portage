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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.inventory.InstallStore
import com.ventouxlabs.portage.providers.relay.RelayApp
import com.ventouxlabs.portage.providers.relay.RelayRestorePrompt
import com.ventouxlabs.portage.recv.FailedItem
import com.ventouxlabs.portage.recv.RestoredPermissions
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt
import com.ventouxlabs.portage.recv.ui.theme.LocalSpacing

/*
 * Done-screen list sections.
 *
 * Every section renders the same scaffold — header rule, one row per entry, closing note — so the
 * scaffold lives in [doneSection] once and each section supplies only its words, its key and its
 * row. The section functions carry the rationale for what they surface.
 */

/**
 * One Done-screen list section. Renders nothing when [entries] is empty, so callers stay
 * declarative — an empty section must not emit its header, or the screen claims work it didn't do.
 */
private fun <T> LazyListScope.doneSection(
    label: String,
    entries: List<T>,
    unit: String,
    units: String,
    key: (T) -> Any,
    footer: String,
    row: @Composable (T) -> Unit,
) {
    if (entries.isEmpty()) return
    item {
        val s = LocalSpacing.current
        Spacer(Modifier.height(s.lg))
        Text(
            text = sectionHeading(label, entries.size, unit, units),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = s.sm),
        )
        HairlineDivider()
    }
    items(entries, key = key) { entry -> row(entry) }
    item {
        val s = LocalSpacing.current
        Spacer(Modifier.height(s.md))
        Text(
            text = footer,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A tappable Done-screen row: title over caption, action word right-aligned, the whole row the tap
 * target. The reinstall, carried-APK, re-pair and relay rows are this same row with different words.
 */
@Composable
private fun TapActionRow(
    title: String,
    caption: String,
    action: String,
    onClick: () -> Unit,
) {
    val s = LocalSpacing.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = s.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(s.xs))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = action,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HairlineDivider()
    }
}

/**
 * Carried-APK installs (ADR-006 D3/D6): each row commits its already-sealed `PackageInstaller`
 * session and fires the system install-confirm UI. Our own app installing our own carried bytes —
 * NO shell uid, NO silent install (the silent path is the deferred P6 concern). The package was
 * wire-validated before staging.
 *
 * GOS-batching UX open question (ADR-006 follow-ups): A16 may not batch multiple confirm intents,
 * so this is one tap per app and the closing note says so.
 */
internal fun LazyListScope.apkInstallSection(
    prompts: List<ApkInstallPrompt>,
    onInstallApk: (ApkInstallPrompt) -> Unit,
) = doneSection(
    label = "INSTALL",
    entries = prompts,
    unit = "APP",
    units = "APPS",
    key = { "apk:${it.sessionId}" },
    footer = "These came over with your phone. Tap to install — Android asks you to confirm each one.",
) { prompt ->
    TapActionRow(
        title = prompt.label,
        caption = "Carried with your phone",
        action = "INSTALL",
        onClick = { onInstallApk(prompt) },
    )
}

/** Apps whose permissions portage switched back on after a silent install. */
internal fun LazyListScope.restoredPermissionsSection(restored: List<RestoredPermissions>) = doneSection(
    label = "RESTORED",
    entries = restored,
    unit = "APP",
    units = "APPS",
    key = { "perms:${it.packageName}" },
    footer = "These came over silently, so portage switched their permissions back on for you — " +
        "just like on your old phone.",
) { entry ->
    RestoredPermissionsRow(restored = entry)
}

/** Store deep links for apps portage could not carry — the receiver never installs silently (PRP §2). */
internal fun LazyListScope.reinstallSection(
    actions: List<InstallAction>,
    onInstall: (InstallAction) -> Unit,
) = doneSection(
    label = "REINSTALL",
    entries = actions,
    unit = "APP",
    units = "APPS",
    key = { "install:${it.packageName}" },
    footer = "Each opens its store — one tap to install. Nothing installs on its own.",
) { action ->
    TapActionRow(
        title = action.label,
        caption = storeName(action.store),
        action = "INSTALL",
        onClick = { onInstall(action) },
    )
}

/**
 * Bonded-Bluetooth roster (PRP-07 Phase 1): the OS owns bonding, so portage shows the list and
 * never bonds — link keys are non-transferable. Each row opens system Bluetooth settings. The name
 * and MAC are pre-validated/sanitized by [RePairEntry.from]; nothing here is shown raw.
 */
internal fun LazyListScope.rePairSection(
    entries: List<RePairEntry>,
    onOpenBluetoothSettings: () -> Unit,
) = doneSection(
    label = "RE-PAIR",
    entries = entries,
    unit = "DEVICE",
    units = "DEVICES",
    key = { "bt:${it.address}" },
    footer = "You were paired to these. Bluetooth pairings can't move between phones — open " +
        "Bluetooth settings and pair each one again.",
) { entry ->
    TapActionRow(
        title = entry.name,
        caption = "${btKindLabel(entry.devType)} · ${entry.address}",
        action = "RE-PAIR",
        onClick = onOpenBluetoothSettings,
    )
}

/**
 * Relayed app backups (PRP-06): portage relayed the OPAQUE file only — it never imports it and
 * never holds the passphrase. Each row opens the target app so the user can import it. The app id,
 * name and note are pre-validated/sanitized by [RelayRestorePrompt] (derived from the typed
 * RelayApp enum); the opaque bytes are never surfaced.
 */
internal fun LazyListScope.relaySection(
    prompts: List<RelayRestorePrompt>,
    onOpenRelayApp: (RelayRestorePrompt) -> Unit,
) = doneSection(
    label = "RESTORE",
    entries = prompts,
    unit = "BACKUP",
    units = "BACKUPS",
    key = { "relay:${it.itemId}" },
    footer = "Each backup file is here, encrypted by the app and only openable with your " +
        "passphrase — portage never sees it. Open the app and import it.",
) { prompt ->
    TapActionRow(
        title = relayAppLabel(prompt.app),
        caption = prompt.restoreNote,
        action = "OPEN",
        onClick = { onOpenRelayApp(prompt) },
    )
}

/** Failures a re-send can still fix — see [isTerminal]. */
internal fun LazyListScope.tryAgainSection(items: List<FailedItem>) = doneSection(
    label = "TRY AGAIN",
    entries = items,
    unit = "ITEM",
    units = "ITEMS",
    key = { "again:${it.itemId}" },
    footer = "Sending these again usually works — start another transfer from your old phone.",
) { item ->
    FailedItemRow(item = item)
}

/** Failures a re-send cannot fix — the verdict is the same next time, so each row says why. */
internal fun LazyListScope.leftBehindSection(items: List<FailedItem>) = doneSection(
    label = "LEFT BEHIND",
    entries = items,
    unit = "ITEM",
    units = "ITEMS",
    key = { "behind:${it.itemId}" },
    footer = "Sending these again won't change the answer — each row says why.",
) { item ->
    FailedItemRow(item = item)
}

private fun storeName(store: InstallStore): String = when (store) {
    InstallStore.PLAY -> "Play Store"
    InstallStore.FDROID -> "F-Droid"
    InstallStore.AURORA -> "Aurora Store"
    InstallStore.UNKNOWN -> "Any store"
}

/** Human hint from BluetoothDevice.getType(): 1=CLASSIC, 2=LE, 3=DUAL, else unknown. */
private fun btKindLabel(devType: Int): String = when (devType) {
    1 -> "Bluetooth"
    2 -> "Bluetooth LE"
    3 -> "Bluetooth (dual)"
    else -> "Bluetooth device"
}

/** Friendly label for the target relay app (derived from the typed enum, never a wire string). */
private fun relayAppLabel(app: RelayApp): String = when (app) {
    RelayApp.SIGNAL -> "Signal"
    RelayApp.MOLLY -> "Molly"
    RelayApp.AEGIS -> "Aegis"
    RelayApp.OTHER -> "App backup"
}
