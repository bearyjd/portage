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

/** Where one selected item is in its receive→apply lifecycle. */
enum class ItemPhase { PENDING, RECEIVING, APPLYING, DONE, FAILED }

/** Per-item progress row shown while transferring. [detail] is the provider's summary line. */
data class ItemProgress(
    val itemId: Int,
    val displayName: String,
    val phase: ItemPhase = ItemPhase.PENDING,
    val detail: String? = null,
)

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

    /** Streaming + applying selected items, tracked per item. */
    data class Transferring(val items: List<ItemProgress>) : ReceiverState {
        val total: Int get() = items.size
        val completed: Int get() = items.count { it.phase == ItemPhase.DONE || it.phase == ItemPhase.FAILED }
    }

    /** Done summary: what moved, what to do next. */
    data class Done(val moved: Int, val skipped: Int) : ReceiverState

    /** Fail-closed terminal state with a user-facing reason. */
    data class Failed(val reason: String) : ReceiverState
}
