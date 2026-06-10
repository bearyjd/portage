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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.grepon.portage.recv.ui.theme.LocalSpacing

/**
 * Fail-closed terminal state. Deliberately calm — the red accent appears only as a small
 * marker square next to the heading (semantic, not alarming). The reason is shown verbatim,
 * then a single "Try again" returns to Idle.
 */
@Composable
fun FailedScreen(
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
        Box(
            Modifier
                .size(s.md)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.height(s.lg))
        Text(
            text = "That didn’t go through.",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.lg))
        HairlineDivider()
        Spacer(Modifier.height(s.lg))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.md))
        Text(
            text = "Nothing was changed on this phone. You can start over.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.xl))
        SwissPrimaryButton(
            text = "Try again",
            onClick = onRetry,
            fullWidth = true,
        )
    }
}
