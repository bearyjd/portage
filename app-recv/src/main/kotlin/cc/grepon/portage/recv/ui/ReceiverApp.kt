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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.grepon.portage.recv.ReceiverState
import cc.grepon.portage.recv.ReceiverViewModel
import cc.grepon.portage.recv.ui.theme.LocalSpacing
import cc.grepon.portage.recv.ui.theme.PortageTheme

/**
 * Root of the receiver UI. Collects the single [ReceiverState] flow and crossfades the matching
 * screen beneath a fixed Swiss masthead. The masthead is the shared structural anchor (not a
 * Material TopAppBar); each state renders into the body below it.
 */
@Composable
fun ReceiverApp(viewModel: ReceiverViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PortageTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { SwissMasthead() },
        ) { padding ->
            AnimatedContent(
                targetState = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                transitionSpec = {
                    (fadeIn(animationSpec = tween(260)) togetherWith
                        fadeOut(animationSpec = tween(180)))
                },
                contentKey = { it.key() },
                label = "receiverState",
            ) { current ->
                StateBody(current = current, viewModel = viewModel)
            }
        }
    }
}

/** Dispatch one state to its screen. Exhaustive over the sealed [ReceiverState]. */
@Composable
private fun StateBody(
    current: ReceiverState,
    viewModel: ReceiverViewModel,
) {
    when (current) {
        is ReceiverState.Idle ->
            IdleScreen(onScan = viewModel::startScanning, modifier = Modifier.fillMaxSize())

        is ReceiverState.Scanning ->
            ScanScreen(onScanned = viewModel::onQrScanned, modifier = Modifier.fillMaxSize())

        is ReceiverState.Pairing ->
            PendingBody(headline = "Pairing", caption = "Securing the link and reading the manifest…")

        is ReceiverState.Reviewing ->
            ChecklistScreen(
                senderName = current.senderName,
                groups = current.groups,
                onToggle = viewModel::onToggle,
                onConfirm = viewModel::onConfirm,
                modifier = Modifier.fillMaxSize(),
                absentKinds = current.absentKinds,
            )

        is ReceiverState.Transferring ->
            TransferringScreen(
                items = current.items,
                modifier = Modifier.fillMaxSize(),
            )

        is ReceiverState.Done ->
            DoneScreen(
                moved = current.moved,
                skipped = current.skipped,
                onDone = viewModel::reset,
                modifier = Modifier.fillMaxSize(),
            )

        is ReceiverState.Failed ->
            FailedScreen(
                reason = current.reason,
                onRetry = viewModel::reset,
                modifier = Modifier.fillMaxSize(),
            )
    }
}

/** Quiet interstitial for the brief [ReceiverState.Pairing] handshake window. */
@Composable
private fun PendingBody(headline: String, caption: String) {
    val s = LocalSpacing.current
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = s.gutter), contentAlignment = Alignment.CenterStart) {
        Column {
            Text(
                text = headline,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Stable transition key per state *kind* — keeps data-only updates (e.g. progress ticks,
 * checklist toggles) from triggering a full crossfade, so motion fires on real screen changes.
 */
private fun ReceiverState.key(): String = when (this) {
    is ReceiverState.Idle -> "idle"
    is ReceiverState.Scanning -> "scanning"
    is ReceiverState.Pairing -> "pairing"
    is ReceiverState.Reviewing -> "reviewing"
    is ReceiverState.Transferring -> "transferring"
    is ReceiverState.Done -> "done"
    is ReceiverState.Failed -> "failed"
}
