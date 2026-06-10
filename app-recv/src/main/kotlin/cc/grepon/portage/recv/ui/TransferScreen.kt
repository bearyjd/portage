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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.grepon.portage.recv.ui.theme.LocalSpacing

/**
 * Progress. Swiss numerals carry the message — an oversized "completed / total" pair over a
 * single determinate rule that fills with the accent. No spinner chrome; the bar IS the status.
 */
@Composable
fun TransferringScreen(
    completed: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val s = LocalSpacing.current
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
        Text(
            text = "items brought over",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.xl))
        DeterminateRule(fraction = animated)
        Spacer(Modifier.height(s.md))
        Text(
            text = "Keep both phones close and awake.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
 * Done summary. The moved count dominates; a single line points to Seedvault for app data
 * (the deliberate division of labor), then the exit.
 */
@Composable
fun DoneScreen(
    moved: Int,
    skipped: Int,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = s.gutter),
        verticalArrangement = Arrangement.Center,
    ) {
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
            text = "App data? Restore it with Seedvault.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.xl))
        SwissPrimaryButton(
            text = "Done",
            onClick = onDone,
            fullWidth = true,
        )
    }
}
