/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv

import cc.grepon.portage.recv.checklist.ChecklistGroup

/** The receiver's single screen state (portage-prp-prompt.md §7). */
sealed interface ReceiverState {
    /** Landing: explain the flow, offer "Scan". */
    data object Idle : ReceiverState

    /** Camera up, looking for the pairing QR. */
    data object Scanning : ReceiverState

    /** QR decoded; running the Noise handshake + receiving the manifest. */
    data object Pairing : ReceiverState

    /** The checklist: grouped items with sane defaults, awaiting "Bring it over". */
    data class Reviewing(val senderName: String, val groups: List<ChecklistGroup>) : ReceiverState

    /** Streaming + applying selected items. */
    data class Transferring(val completed: Int, val total: Int) : ReceiverState

    /** Done summary: what moved, what to do next. */
    data class Done(val moved: Int, val skipped: Int) : ReceiverState

    /** Fail-closed terminal state with a user-facing reason. */
    data class Failed(val reason: String) : ReceiverState
}
