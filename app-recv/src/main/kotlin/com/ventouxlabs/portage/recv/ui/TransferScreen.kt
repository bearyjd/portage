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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.ventouxlabs.portage.recv.ItemPhase
import com.ventouxlabs.portage.recv.ItemProgress
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
