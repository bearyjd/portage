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
     * Default check state. Pre-check only Tier 0 (always works, no privilege setup) and not SMS
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

    /** The checked items' metadata in display order — what the transfer screen tracks. */
    fun selectedMetas(groups: List<ChecklistGroup>): List<ItemMeta> =
        groups.flatMap { it.items }.filter { it.checked }.map { it.meta }

    /** Whether anything is selected (gates the "Bring it over" action). */
    fun hasSelection(groups: List<ChecklistGroup>): Boolean =
        groups.any { group -> group.items.any { it.checked } }

    /**
     * Every kind a sender normally offers: the five Tier-0 domains plus SETTINGS, whose
     * wire kind is tagged TIER1 but whose SAFE Settings.System cut ships at Tier 0
     * (the allowlist, not the tag, is the boundary). APK — true Tier-1 batch install —
     * is excluded. Display-only: this list never feeds SELECT or the apply path.
     */
    private val EXPECTED_KINDS = listOf(
        ItemKind.CONTACTS_VCF, ItemKind.CALENDAR_ICS, ItemKind.CALL_LOG,
        ItemKind.SMS, ItemKind.APP_INVENTORY, ItemKind.SETTINGS,
    )

    /**
     * Kinds the sender did NOT advertise. The checklist shows these as disabled rows —
     * "not on the old phone" — rather than silently omitting them (devils-advocate:
     * unavailable items become grayed, not missing).
     */
    fun absentKinds(manifest: TransferManifest): List<ItemKind> {
        val present = manifest.items.map { it.kind }.toSet()
        return EXPECTED_KINDS.filter { it !in present }
    }
}
