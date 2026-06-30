/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.checklist

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.Tier
import com.ventouxlabs.portage.model.TransferManifest

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

    const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    const val WRITE_CONTACTS = "android.permission.WRITE_CONTACTS"
    const val READ_CALENDAR = "android.permission.READ_CALENDAR"
    const val WRITE_CALENDAR = "android.permission.WRITE_CALENDAR"
    const val WRITE_CALL_LOG = "android.permission.WRITE_CALL_LOG"

    /**
     * Default check state. Pre-check only Tier 0 (always works, no privilege setup) and not SMS
     * (needs the default-SMS-app handoff). Tier 1 items (settings, APK install) are shown
     * but OPT-IN — PRP §7: "everything in Tier 0 works without ever seeing [Tier 1]".
     */
    fun defaultChecked(meta: ItemMeta): Boolean =
        meta.kind.tier == Tier.TIER0 && meta.kind != ItemKind.SMS && meta.kind != ItemKind.MMS

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

    /** The distinct kinds among currently-checked items — drives review-time capability hints. */
    fun selectedKinds(groups: List<ChecklistGroup>): Set<ItemKind> =
        groups.flatMap { it.items }.filter { it.checked }.map { it.meta.kind }.toSet()

    /**
     * Runtime permissions needed to apply the selected public-provider items. Contacts requests
     * read as well as write because apply performs exact-record deduplication. Call log deliberately
     * remains write-only; its retry journal avoids expanding that privacy boundary.
     */
    fun requiredApplyPermissions(groups: List<ChecklistGroup>): List<String> {
        val kinds = selectedKinds(groups)
        return buildList {
            if (ItemKind.CONTACTS_VCF in kinds) addAll(listOf(READ_CONTACTS, WRITE_CONTACTS))
            if (ItemKind.CALENDAR_ICS in kinds) addAll(listOf(READ_CALENDAR, WRITE_CALENDAR))
            if (ItemKind.CALL_LOG in kinds) add(WRITE_CALL_LOG)
        }
    }

    /**
     * Whether to offer the one-tap "Modify system settings" grant on the review screen: the user
     * selected SETTINGS — whose SAFE Settings.System cut applies at Tier 0 through that special
     * access — but the receiver doesn't hold it yet, so those system keys would silently self-skip
     * on apply. Tier-1 Secure/Global keys ride the WRITE_SECURE_SETTINGS grant instead and are NOT
     * gated by this. Granting before "Bring it over" lets the system keys apply in the SAME pass —
     * [com.ventouxlabs.portage.providers.settings.AndroidSystemSettingsStore] re-checks canWrite() at
     * apply time, so no second transfer is needed.
     *
     * Residual (intentional): the review screen sees only the SETTINGS *kind*, not the snapshot's
     * individual keys, so on the rare sender whose SAFE snapshot is entirely Secure/Global (zero
     * Settings.System keys) this over-prompts. Harmless — the access is legitimate for the app and
     * the nudge auto-hides once held; the apply path simply has nothing Tier-0 to write.
     */
    fun systemSettingsGrantNeeded(groups: List<ChecklistGroup>, canWriteSystem: Boolean): Boolean =
        !canWriteSystem && ItemKind.SETTINGS in selectedKinds(groups)

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
        ItemKind.SMS, ItemKind.MMS, ItemKind.APP_INVENTORY, ItemKind.SETTINGS, ItemKind.WALLPAPER,
        ItemKind.SOUND_FILE, ItemKind.SOUND_SELECTION, ItemKind.BLUETOOTH_DEVICES,
        ItemKind.APP_BACKUP_RELAY, ItemKind.USER_FILE,
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
