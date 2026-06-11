/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.checklist

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemMeta
import cc.grepon.portage.model.Tier
import cc.grepon.portage.model.TransferManifest

/** One selectable line in the receiver checklist. */
data class ChecklistItem(val meta: ItemMeta, val checked: Boolean)

/** A category of items (manifest `group`), rendered as a section. */
data class ChecklistGroup(val title: String, val items: List<ChecklistItem>)

/**
 * Pure logic behind the receiver checklist (portage-prp-prompt.md §7): group the sender's
 * manifest by category and pre-check the sane defaults. SMS is the one Tier-0 item NOT
 * pre-checked — restoring it requires the default-SMS-app handoff, which the user should
 * opt into deliberately (DEVILS_ADVOCATE.md Q4). Everything is immutable copy-on-write.
 */
object ReceiverChecklist {

    /**
     * Default check state. Pre-check only Tier 0 (always works, no Shizuku) and not SMS
     * (needs the default-SMS-app handoff). Tier 1 items (settings, APK install) are shown
     * but OPT-IN — PRP §7: "everything in Tier 0 works without ever seeing [Tier 1]".
     */
    fun defaultChecked(meta: ItemMeta): Boolean =
        meta.kind.tier == Tier.TIER0 && meta.kind != ItemKind.SMS

    /** Build the grouped checklist from a manifest, preserving first-seen group order. */
    fun build(manifest: TransferManifest): List<ChecklistGroup> =
        manifest.items
            .groupBy { it.group }
            .map { (group, items) ->
                ChecklistGroup(
                    title = group,
                    items = items.map { ChecklistItem(it, defaultChecked(it)) },
                )
            }

    /** Toggle one item by id, returning a new list (no mutation). */
    fun toggle(groups: List<ChecklistGroup>, itemId: Int): List<ChecklistGroup> =
        groups.map { group ->
            group.copy(
                items = group.items.map { item ->
                    if (item.meta.itemId == itemId) item.copy(checked = !item.checked) else item
                },
            )
        }

    /** The set of currently-checked item ids — what SELECT will request. */
    fun selectedIds(groups: List<ChecklistGroup>): Set<Int> =
        groups.flatMap { it.items }.filter { it.checked }.map { it.meta.itemId }.toSet()

    /** Whether anything is selected (gates the "Bring it over" action). */
    fun hasSelection(groups: List<ChecklistGroup>): Boolean =
        groups.any { group -> group.items.any { it.checked } }
}
