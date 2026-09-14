/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ventouxlabs.portage.send.SenderState

internal enum class SenderDestructiveIntent {
    CANCEL_ACTIVE,
    CANCEL_SAVED,
    COMPLETE,
    START_NEW,
}

internal enum class SenderDestructiveAction(
    val title: String,
    val confirmLabel: String,
) {
    CANCEL_ACTIVE_MOVE("Cancel this move?", "Cancel move"),
    DELETE_SAVED_MOVE("Delete the saved move?", "Delete saved move"),
    DISCARD_UNCERTAIN_MOVE("Finish without resuming?", "Delete and finish"),
    REPLACE_SAVED_MOVE("Start a new move?", "Delete and start new"),
}

internal const val DESTRUCTIVE_MOVE_WARNING =
    "Saved transfer data on this phone will be deleted. Resuming this move will become impossible. " +
        "Changes already applied on the other phone will not be undone."

/** Pure action matrix: a null result means the requested action is non-destructive in this state. */
internal fun destructiveActionFor(
    state: SenderState,
    intent: SenderDestructiveIntent,
): SenderDestructiveAction? = when (intent) {
    SenderDestructiveIntent.CANCEL_ACTIVE -> when (state) {
        is SenderState.ShowingQr, is SenderState.Linked, is SenderState.Sending ->
            SenderDestructiveAction.CANCEL_ACTIVE_MOVE
        else -> null
    }

    SenderDestructiveIntent.CANCEL_SAVED ->
        if (state is SenderState.Failed && state.hasSavedMove) {
            SenderDestructiveAction.DELETE_SAVED_MOVE
        } else null

    SenderDestructiveIntent.COMPLETE ->
        if (state is SenderState.Done && state.canResume) {
            SenderDestructiveAction.DISCARD_UNCERTAIN_MOVE
        } else null

    SenderDestructiveIntent.START_NEW ->
        if (state is SenderState.Failed && state.hasSavedMove) {
            SenderDestructiveAction.REPLACE_SAVED_MOVE
        } else null
}

/** Retains a destructive callback only until the user confirms or dismisses its disclosure. */
internal class SenderDestructiveActionGate {
    var pendingAction by mutableStateOf<SenderDestructiveAction?>(null)
        private set
    private var confirmedAction: (() -> Unit)? = null

    fun request(action: SenderDestructiveAction, onConfirm: () -> Unit) {
        pendingAction = action
        confirmedAction = onConfirm
    }

    fun dismiss() {
        pendingAction = null
        confirmedAction = null
    }

    fun confirm() {
        val action = confirmedAction ?: return
        pendingAction = null
        confirmedAction = null
        action()
    }
}
