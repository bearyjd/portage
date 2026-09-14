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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ventouxlabs.portage.recv.ui.theme.LocalSpacing
import com.ventouxlabs.portage.recv.ReceiverState

/**
 * An interruption can preserve a saved move and changes already applied on this phone.
 * Resuming preserves the saved move; cancelling is a separate, explicitly destructive action.
 */
@Composable
fun FailedScreen(
    failure: ReceiverState.Failed,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = s.gutter, vertical = s.lg),
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
            text = failure.reason,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.md))
        Text(
            text = failureRecoveryMessage(failure),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.xl))
        SwissPrimaryButton(
            text = if (failure.canResumeSavedMove) "Resume saved move" else "Try again",
            onClick = if (failure.canResumeSavedMove) onResume else onRetry,
            fullWidth = true,
        )
        if (failure.canResumeSavedMove) {
            Spacer(Modifier.height(s.md))
            Text(
                text = "Cancelling deletes this phone's saved transfer data and prevents resuming this move. It does not undo changes already applied.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onCancel) { Text("Cancel saved move") }
        }
    }
}

internal fun failureRecoveryMessage(failure: ReceiverState.Failed): String = when {
    failure.mayHaveAppliedChanges ->
        "Some changes may already have been applied on this phone. The transfer's final outcome could not be confirmed."
    failure.canResumeSavedMove ->
        "Your saved move is still available. Scan a fresh pairing code from the sender to resume."
    else -> "The transfer could not finish. Resolve the problem before trying again."
}
