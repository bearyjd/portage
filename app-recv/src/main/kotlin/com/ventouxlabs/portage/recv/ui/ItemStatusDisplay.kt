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

import com.ventouxlabs.portage.model.ItemStatus

/**
 * How a per-item verdict is worded on screen. Pure string mapping, kept apart from the composables
 * so it stays unit-testable (see `ItemStatusDisplayTest` — there is no Robolectric in this repo, so
 * a pure seam is the only JVM-testable surface the Done screen has).
 */

internal fun statusWord(status: ItemStatus): String = when (status) {
    ItemStatus.OK -> "MOVED"
    ItemStatus.SKIPPED -> "SKIPPED"
    ItemStatus.HASH_MISMATCH -> "DAMAGED"
    ItemStatus.WRITE_ERROR -> "NOT SAVED"
    ItemStatus.UNKNOWN_KIND -> "UNKNOWN"
    ItemStatus.OVERSIZE -> "TOO BIG"
}

internal fun statusReason(status: ItemStatus): String? = when (status) {
    ItemStatus.OK -> null
    ItemStatus.SKIPPED -> "This phone chose to leave it."
    ItemStatus.HASH_MISMATCH -> "Didn't arrive intact — sending it again usually fixes this."
    ItemStatus.WRITE_ERROR -> "This phone couldn't save it — worth sending again."
    ItemStatus.UNKNOWN_KIND ->
        "This phone's portage doesn't know this kind of item — update portage here, then send again."
    ItemStatus.OVERSIZE -> "Too big to carry — this phone caps what one item can bring."
}

internal fun isTerminal(status: ItemStatus): Boolean = when (status) {
    ItemStatus.OK, ItemStatus.HASH_MISMATCH, ItemStatus.WRITE_ERROR -> false
    ItemStatus.SKIPPED, ItemStatus.UNKNOWN_KIND, ItemStatus.OVERSIZE -> true
}

/** "REINSTALL · 3 APPS" — the count decides singular vs plural. */
internal fun sectionHeading(label: String, count: Int, unit: String, units: String): String =
    "$label · $count ${if (count == 1) unit else units}"
