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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.grepon.portage.send.SenderState
import cc.grepon.portage.send.SenderViewModel
import cc.grepon.portage.send.ui.theme.PortageTheme

/**
 * Root of the sender UI — the receiver's structure mirrored: one state flow, a fixed Swiss
 * masthead, crossfading state bodies underneath.
 */
@Composable
fun SenderApp(viewModel: SenderViewModel, summary: DeviceSummary) {
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
                label = "senderState",
            ) { current ->
                StateBody(current = current, viewModel = viewModel, summary = summary)
            }
        }
    }
}

/** Dispatch one state to its screen. Exhaustive over the sealed [SenderState]. */
@Composable
private fun StateBody(
    current: SenderState,
    viewModel: SenderViewModel,
    summary: DeviceSummary,
) {
    when (current) {
        is SenderState.Home ->
            HomeScreen(
                summary = summary,
                onStart = viewModel::onStartTransfer,
                modifier = Modifier.fillMaxSize(),
            )

        is SenderState.Preparing ->
            PendingScreen(
                step = "01 · PACKING",
                headline = "Packing",
                caption = "Reading what this phone can carry…",
            )

        is SenderState.ShowingQr ->
            PairingScreen(
                qrText = current.qrText,
                itemCount = current.itemCount,
                totalBytes = current.totalBytes,
                onCancel = viewModel::reset,
                modifier = Modifier.fillMaxSize(),
            )

        is SenderState.Linked ->
            PendingScreen(
                step = "02 · PAIRED",
                headline = "Linked",
                caption = "Secure channel up. Waiting for the new phone's picks…",
            )

        is SenderState.Sending ->
            SendingScreen(items = current.items, modifier = Modifier.fillMaxSize())

        is SenderState.Done ->
            SendDoneScreen(
                sent = current.sent,
                failed = current.failed,
                onDone = viewModel::reset,
                modifier = Modifier.fillMaxSize(),
            )

        is SenderState.Failed ->
            SendFailedScreen(
                reason = current.reason,
                onRetry = viewModel::reset,
                modifier = Modifier.fillMaxSize(),
            )
    }
}

/** Stable transition key per state kind so data ticks don't retrigger the crossfade. */
private fun SenderState.key(): String = when (this) {
    is SenderState.Home -> "home"
    is SenderState.Preparing -> "preparing"
    is SenderState.ShowingQr -> "qr"
    is SenderState.Linked -> "linked"
    is SenderState.Sending -> "sending"
    is SenderState.Done -> "done"
    is SenderState.Failed -> "failed"
}
