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

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.send.SendProgress
import com.ventouxlabs.portage.send.SenderState
import org.junit.Test

class SenderDestructiveActionsTest {

    @Test
    fun `action matrix gates every saved-move deletion and leaves terminal completion direct`() {
        val activeStates = listOf<SenderState>(
            SenderState.ShowingQr("qr", 1, 1),
            SenderState.Linked,
            SenderState.Sending(listOf(SendProgress(1, "Contacts", 1))),
        )
        activeStates.forEach {
            assertThat(destructiveActionFor(it, SenderDestructiveIntent.CANCEL_ACTIVE))
                .isEqualTo(SenderDestructiveAction.CANCEL_ACTIVE_MOVE)
        }

        val resumableFailure = SenderState.Failed("paused", canResume = true, hasSavedMove = true)
        assertThat(destructiveActionFor(resumableFailure, SenderDestructiveIntent.CANCEL_SAVED))
            .isEqualTo(SenderDestructiveAction.DELETE_SAVED_MOVE)
        assertThat(destructiveActionFor(resumableFailure, SenderDestructiveIntent.START_NEW))
            .isEqualTo(SenderDestructiveAction.REPLACE_SAVED_MOVE)

        val nonResumableSaved = SenderState.Failed("restart both", hasSavedMove = true)
        assertThat(destructiveActionFor(nonResumableSaved, SenderDestructiveIntent.START_NEW))
            .isEqualTo(SenderDestructiveAction.REPLACE_SAVED_MOVE)
        assertThat(destructiveActionFor(nonResumableSaved, SenderDestructiveIntent.CANCEL_SAVED))
            .isEqualTo(SenderDestructiveAction.DELETE_SAVED_MOVE)

        assertThat(destructiveActionFor(SenderState.Done(1, 0, unknown = 1),
            SenderDestructiveIntent.COMPLETE)).isEqualTo(SenderDestructiveAction.DISCARD_UNCERTAIN_MOVE)
        assertThat(destructiveActionFor(SenderState.Done(1, 0, notSent = 1),
            SenderDestructiveIntent.COMPLETE)).isEqualTo(SenderDestructiveAction.DISCARD_UNCERTAIN_MOVE)

        // Every result is receipt-terminal, so Done only finishes an already-terminal lineage.
        assertThat(destructiveActionFor(SenderState.Done(1, 1), SenderDestructiveIntent.COMPLETE)).isNull()
        assertThat(destructiveActionFor(SenderState.Home, SenderDestructiveIntent.START_NEW)).isNull()
        assertThat(destructiveActionFor(SenderState.Failed("nothing saved"),
            SenderDestructiveIntent.START_NEW)).isNull()
    }

    @Test
    fun `disclosure explicitly states all three destructive consequences`() {
        assertThat(DESTRUCTIVE_MOVE_WARNING).contains("Saved transfer data on this phone will be deleted")
        assertThat(DESTRUCTIVE_MOVE_WARNING).contains("Resuming this move will become impossible")
        assertThat(DESTRUCTIVE_MOVE_WARNING).contains("other phone will not be undone")
    }

    @Test
    fun `dismissal is non-destructive and confirmation executes exactly once`() {
        val gate = SenderDestructiveActionGate()
        var executions = 0

        gate.request(SenderDestructiveAction.DELETE_SAVED_MOVE) { executions++ }
        assertThat(gate.pendingAction).isEqualTo(SenderDestructiveAction.DELETE_SAVED_MOVE)
        gate.dismiss()
        assertThat(gate.pendingAction).isNull()
        assertThat(executions).isEqualTo(0)
        gate.confirm()
        assertThat(executions).isEqualTo(0)

        gate.request(SenderDestructiveAction.REPLACE_SAVED_MOVE) { executions++ }
        gate.confirm()
        gate.confirm()
        assertThat(gate.pendingAction).isNull()
        assertThat(executions).isEqualTo(1)
    }
}
