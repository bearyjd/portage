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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cc.grepon.portage.recv.ui.theme.LocalSpacing

/**
 * Landing. Swiss editorial: a small indexed running head, an oversized display headline pinned
 * to the gutter, a single explanatory line, then the primary "Scan" call. The app-data
 * division-of-labor note sits as tracked-out fine print above the action — context, not noise.
 */
@Composable
fun IdleScreen(
    onScan: () -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = s.gutter),
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(s.xxl))

        // Indexed section marker — a Swiss tell.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "01",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(s.sm))
            Text(
                text = "START",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(s.lg))

        Text(
            text = "Bring your\nold phone\nover.",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(s.lg))
        HairlineDivider()
        Spacer(Modifier.height(s.lg))

        Text(
            text = "On your old phone, open portage and start a transfer. " +
                "It will show a one-time pairing code. Point this phone at it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(0.92f),
        )

        Spacer(Modifier.height(s.xl))

        Text(
            text = "APP DATA NEEDS A BACKUP · PORTAGE MOVES THE REST",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )

        Spacer(Modifier.height(s.md))

        SwissPrimaryButton(
            text = "Scan the other phone",
            onClick = onScan,
            fullWidth = true,
        )

        Spacer(Modifier.height(s.lg))

        // The privilege bootstrap (ADR-003): optional, re-runnable, never required for Tier 0.
        SwissTextAction(
            text = "Advanced transfer setup",
            onClick = onSetup,
        )

        Spacer(Modifier.height(s.xl))
    }
}
