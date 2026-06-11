/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.grepon.portage.send.SendPhase
import cc.grepon.portage.send.SendProgress
import cc.grepon.portage.send.ui.theme.LocalSpacing

/** Quiet interstitial between screens (preparing exports, linked-awaiting-picks). */
@Composable
fun PendingScreen(
    step: String,
    headline: String,
    caption: String,
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
            text = step,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.md))
        Text(
            text = headline,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.sm))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Streaming the receiver's picks: byte-true progress over a single determinate rule, then
 * every requested item with its own status line (the receiver's verdicts land live).
 */
@Composable
fun SendingScreen(
    items: List<SendProgress>,
    modifier: Modifier = Modifier,
) {
    val s = LocalSpacing.current
    val totalBytes = items.sumOf { it.totalBytes }
    val sentBytes = items.sumOf { it.bytesSent }
    val animated by animateFloatAsState(
        targetValue = sentBytes.toFloat() / totalBytes.coerceAtLeast(1).toFloat(),
        label = "sendProgress",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = s.gutter),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "03 · CARRYING",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.lg))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatBytes(sentBytes),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = " / ${formatBytes(totalBytes)}",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = s.xs),
            )
        }
        Spacer(Modifier.height(s.md))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.outline),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(s.lg))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(items, key = { it.itemId }) { item ->
                SendProgressRow(item)
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

@Composable
private fun SendProgressRow(item: SendProgress) {
    val s = LocalSpacing.current
    val failed = item.phase == SendPhase.FAILED
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

private fun phaseWord(phase: SendPhase): String = when (phase) {
    SendPhase.QUEUED -> "QUEUED"
    SendPhase.SENDING -> "SENDING"
    SendPhase.ACKED -> "RECEIVED"
    SendPhase.FAILED -> "FAILED"
}

/** Done summary on the sender: what the receiver confirmed, what it refused. */
@Composable
fun SendDoneScreen(
    sent: Int,
    failed: Int,
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
                text = sent.toString(),
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (sent == 1) " thing carried over" else " things carried over",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = s.sm),
            )
        }
        if (failed > 0) {
            Spacer(Modifier.height(s.sm))
            Text(
                text = "$failed refused by the new phone — its summary has the why",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(s.lg))
        HairlineDivider()
        Spacer(Modifier.height(s.lg))
        Text(
            text = "App data? Move it with Seedvault.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.xl))
        SwissPrimaryButton(text = "Done", onClick = onDone, fullWidth = true)
    }
}

/** Fail-closed terminal screen with the reason and the way back. */
@Composable
fun SendFailedScreen(
    reason: String,
    onRetry: () -> Unit,
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
            text = "STOPPED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(s.lg))
        Text(
            text = "That didn't carry",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.md))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.xl))
        SwissPrimaryButton(text = "Start over", onClick = onRetry, fullWidth = true)
    }
}
