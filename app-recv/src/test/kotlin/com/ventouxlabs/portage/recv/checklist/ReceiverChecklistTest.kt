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
import com.ventouxlabs.portage.model.TransferManifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReceiverChecklistTest {

    private fun meta(id: Int, kind: ItemKind, group: String) =
        ItemMeta(id, kind, size = 1, sha256 = "x", displayName = kind.wire, group = group)

    private val manifest = TransferManifest(
        senderName = "old phone",
        items = listOf(
            meta(1, ItemKind.CONTACTS_VCF, "People"),
            meta(2, ItemKind.CALENDAR_ICS, "People"),
            meta(3, ItemKind.SMS, "Messages"),
            meta(4, ItemKind.MMS, "Messages"),
            meta(5, ItemKind.SETTINGS, "System"),
        ),
        totalBytes = 5,
    )

    @Test
    fun `groups preserve first-seen order and contain their items`() {
        val groups = ReceiverChecklist.build(manifest)
        assertThat(groups.map { it.title }).containsExactly("People", "Messages", "System").inOrder()
        assertThat(groups.first().items).hasSize(2)
    }

    @Test
    fun `Tier-0 non-message-role items are pre-checked, SMS MMS and Tier-1 are opt-in`() {
        val groups = ReceiverChecklist.build(manifest)
        val byKind = groups.flatMap { it.items }.associateBy { it.meta.kind }
        assertThat(byKind.getValue(ItemKind.CONTACTS_VCF).checked).isTrue() // Tier 0
        assertThat(byKind.getValue(ItemKind.CALENDAR_ICS).checked).isTrue() // Tier 0
        assertThat(byKind.getValue(ItemKind.SMS).checked).isFalse()         // Tier 0, but handoff
        assertThat(byKind.getValue(ItemKind.MMS).checked).isFalse()         // Tier 0, but handoff
        assertThat(byKind.getValue(ItemKind.SETTINGS).checked).isFalse()    // Tier 1, opt-in
    }

    @Test
    fun `selectedIds is the Tier-0 non-SMS set by default`() {
        val groups = ReceiverChecklist.build(manifest)
        assertThat(ReceiverChecklist.selectedIds(groups)).containsExactly(1, 2)
    }

    @Test
    fun `selected provider kinds map to the receiver permissions needed before apply`() {
        val groups = ReceiverChecklist.build(
            TransferManifest(
                senderName = "old phone",
                items = listOf(
                    meta(1, ItemKind.CONTACTS_VCF, "People"),
                    meta(2, ItemKind.CALENDAR_ICS, "Calendar"),
                    meta(3, ItemKind.CALL_LOG, "History"),
                ),
                totalBytes = 3,
            ),
        )

        assertThat(ReceiverChecklist.requiredApplyPermissions(groups)).containsExactly(
            ReceiverChecklist.READ_CONTACTS,
            ReceiverChecklist.WRITE_CONTACTS,
            ReceiverChecklist.READ_CALENDAR,
            ReceiverChecklist.WRITE_CALENDAR,
            ReceiverChecklist.WRITE_CALL_LOG,
        ).inOrder()
    }

    @Test
    fun `unselected kinds do not request their receiver permissions`() {
        val groups = ReceiverChecklist.toggle(
            ReceiverChecklist.build(
                TransferManifest(
                    senderName = "old phone",
                    items = listOf(meta(1, ItemKind.CONTACTS_VCF, "People")),
                    totalBytes = 1,
                ),
            ),
            1,
        )

        assertThat(ReceiverChecklist.requiredApplyPermissions(groups)).isEmpty()
    }

    @Test
    fun `toggle flips exactly one item immutably`() {
        val groups = ReceiverChecklist.build(manifest)
        val afterOptInSms = ReceiverChecklist.toggle(groups, itemId = 3)
        assertThat(ReceiverChecklist.selectedIds(afterOptInSms)).containsExactly(1, 2, 3)
        // Original is untouched (no mutation).
        assertThat(ReceiverChecklist.selectedIds(groups)).containsExactly(1, 2)
    }

    @Test
    fun `absent Tier-0 kinds are reported so the UI can gray them, not hide them`() {
        val absent = ReceiverChecklist.absentKinds(manifest)
        // Manifest has contacts/calendar/sms/settings — call log, inventory, wallpaper, sound
        // files/selection, the bonded-Bluetooth roster, app-backup relay, and user files are missing.
        assertThat(absent).containsExactly(
            ItemKind.CALL_LOG, ItemKind.APP_INVENTORY, ItemKind.WALLPAPER, ItemKind.SOUND_FILE,
            ItemKind.SOUND_SELECTION, ItemKind.BLUETOOTH_DEVICES, ItemKind.APP_BACKUP_RELAY,
            ItemKind.USER_FILE,
        ).inOrder()
    }

    @Test
    fun `a manifest advertising everything has no absent kinds`() {
        val full = TransferManifest(
            senderName = "s",
            items = listOf(
                meta(1, ItemKind.CONTACTS_VCF, "g"), meta(2, ItemKind.CALENDAR_ICS, "g"),
                meta(3, ItemKind.CALL_LOG, "g"), meta(4, ItemKind.SMS, "g"),
                meta(5, ItemKind.MMS, "g"), meta(6, ItemKind.APP_INVENTORY, "g"),
                meta(7, ItemKind.SETTINGS, "g"), meta(8, ItemKind.WALLPAPER, "g"),
                meta(9, ItemKind.SOUND_FILE, "g"), meta(10, ItemKind.SOUND_SELECTION, "g"),
                meta(11, ItemKind.BLUETOOTH_DEVICES, "g"), meta(12, ItemKind.APP_BACKUP_RELAY, "g"),
                meta(13, ItemKind.USER_FILE, "g"),
            ),
            totalBytes = 13,
        )
        assertThat(ReceiverChecklist.absentKinds(full)).isEmpty()
    }

    @Test
    fun `selectedMetas returns checked items in display order`() {
        val groups = ReceiverChecklist.build(manifest)
        assertThat(ReceiverChecklist.selectedMetas(groups).map { it.itemId })
            .containsExactly(1, 2).inOrder()
    }

    @Test
    fun `hasSelection is false only when nothing is checked`() {
        val none = ReceiverChecklist.build(manifest)
            .map { g -> g.copy(items = g.items.map { it.copy(checked = false) }) }
        assertThat(ReceiverChecklist.hasSelection(none)).isFalse()
        assertThat(ReceiverChecklist.hasSelection(ReceiverChecklist.build(manifest))).isTrue()
    }

    @Test
    fun `selectedKinds reflects only the checked items' kinds`() {
        val groups = ReceiverChecklist.build(manifest) // contacts + calendar checked; SMS + MMS + SETTINGS opt-in
        assertThat(ReceiverChecklist.selectedKinds(groups))
            .containsExactly(ItemKind.CONTACTS_VCF, ItemKind.CALENDAR_ICS)
        val withSettings = ReceiverChecklist.toggle(groups, itemId = 5) // opt SETTINGS in
        assertThat(ReceiverChecklist.selectedKinds(withSettings))
            .containsExactly(ItemKind.CONTACTS_VCF, ItemKind.CALENDAR_ICS, ItemKind.SETTINGS)
    }

    @Test
    fun `systemSettingsGrantNeeded only when SETTINGS is selected and canWrite is false`() {
        val base = ReceiverChecklist.build(manifest) // SETTINGS not checked by default (Tier-1 opt-in)
        // SETTINGS unselected → never prompt, regardless of canWrite.
        assertThat(ReceiverChecklist.systemSettingsGrantNeeded(base, canWriteSystem = false)).isFalse()
        assertThat(ReceiverChecklist.systemSettingsGrantNeeded(base, canWriteSystem = true)).isFalse()

        val withSettings = ReceiverChecklist.toggle(base, itemId = 5) // opt SETTINGS in
        // SETTINGS selected but access missing → prompt.
        assertThat(ReceiverChecklist.systemSettingsGrantNeeded(withSettings, canWriteSystem = false)).isTrue()
        // SETTINGS selected and access already held → no prompt.
        assertThat(ReceiverChecklist.systemSettingsGrantNeeded(withSettings, canWriteSystem = true)).isFalse()
    }

    @Test
    fun `localCalendarWillBeCreated only when calendar is selected and the phone has none`() {
        val base = ReceiverChecklist.build(manifest) // CALENDAR_ICS IS checked by default (Tier 0)

        // The disclosure that #159 exists for: calendar selected, phone has zero calendars.
        assertThat(ReceiverChecklist.localCalendarWillBeCreated(base, hasWritableCalendar = false)).isTrue()
        // Phone already has one → nothing will be created, so say nothing.
        assertThat(ReceiverChecklist.localCalendarWillBeCreated(base, hasWritableCalendar = true)).isFalse()

        // Calendar unchecked → never disclose, even on a phone with no calendar: unchecking is
        // exactly the escape hatch the disclosure offers, so it must not keep nagging afterwards.
        val withoutCalendar = ReceiverChecklist.toggle(base, itemId = 2)
        assertThat(ReceiverChecklist.selectedKinds(withoutCalendar)).doesNotContain(ItemKind.CALENDAR_ICS)
        assertThat(ReceiverChecklist.localCalendarWillBeCreated(withoutCalendar, hasWritableCalendar = false))
            .isFalse()
    }
}
