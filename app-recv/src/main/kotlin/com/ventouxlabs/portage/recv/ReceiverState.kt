/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.relay.RelayRestorePrompt
import com.ventouxlabs.portage.recv.checklist.ChecklistGroup
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt

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

    /**
     * The checklist: grouped items with sane defaults, awaiting "Bring it over".
     * [absentKinds] are shown disabled — "not on the old phone" — never hidden.
     */
    data class Reviewing(
        val senderName: String,
        val groups: List<ChecklistGroup>,
        val absentKinds: List<ItemKind> = emptyList(),
    ) : ReceiverState

    /** Streaming + applying selected items, tracked per item. */
    data class Transferring(val items: List<ItemProgress>) : ReceiverState

    /**
     * Done summary: what moved, what to do next. [installActions] is the per-app reinstall
     * list produced when App Inventory was applied (empty otherwise) — the receiver presents
     * these as one-tap install deep links; it never installs anything silently (PRP §2).
     * [repairEntries] is the bonded-Bluetooth roster surfaced when BLUETOOTH_DEVICES was applied
     * (empty otherwise) — a "re-pair each here" checklist; Phase 1 only displays, never bonds.
     * [relayPrompts] are the guided "open this in <app> and enter your passphrase" reminders surfaced
     * when an APP_BACKUP_RELAY item was applied (empty otherwise) — portage hands the OPAQUE file to
     * the user / target app and NEVER imports it or holds the passphrase (PRP-06 §5).
     * [apkInstallPrompts] are the Tier-0 APK install rows surfaced when an APK item was applied and fell
     * back to the `PackageInstaller` path (empty otherwise) — one tap per app fires the system
     * install-confirm UI over already-sealed sessions (ADR-006 D3/D6). An APK that was installed silently,
     * skipped (already installed / would downgrade), or surfaced as "incompatible on this device" via the
     * inventory store list produces NO row here.
     */
    data class Done(
        val moved: Int,
        val skipped: Int,
        val installActions: List<InstallAction> = emptyList(),
        val repairEntries: List<RePairEntry> = emptyList(),
        val relayPrompts: List<RelayRestorePrompt> = emptyList(),
        val apkInstallPrompts: List<ApkInstallPrompt> = emptyList(),
    ) : ReceiverState

    /** Fail-closed terminal state with a user-facing reason. */
    data class Failed(val reason: String) : ReceiverState
}
