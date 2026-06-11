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
import cc.grepon.portage.model.TransferManifest
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
            meta(4, ItemKind.SETTINGS, "System"),
        ),
        totalBytes = 4,
    )

    @Test
    fun `groups preserve first-seen order and contain their items`() {
        val groups = ReceiverChecklist.build(manifest)
        assertThat(groups.map { it.title }).containsExactly("People", "Messages", "System").inOrder()
        assertThat(groups.first().items).hasSize(2)
    }

    @Test
    fun `Tier-0 non-SMS items are pre-checked, SMS and Tier-1 are opt-in`() {
        val groups = ReceiverChecklist.build(manifest)
        val byKind = groups.flatMap { it.items }.associateBy { it.meta.kind }
        assertThat(byKind.getValue(ItemKind.CONTACTS_VCF).checked).isTrue() // Tier 0
        assertThat(byKind.getValue(ItemKind.CALENDAR_ICS).checked).isTrue() // Tier 0
        assertThat(byKind.getValue(ItemKind.SMS).checked).isFalse()         // Tier 0, but handoff
        assertThat(byKind.getValue(ItemKind.SETTINGS).checked).isFalse()    // Tier 1, opt-in
    }

    @Test
    fun `selectedIds is the Tier-0 non-SMS set by default`() {
        val groups = ReceiverChecklist.build(manifest)
        assertThat(ReceiverChecklist.selectedIds(groups)).containsExactly(1, 2)
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
    fun `hasSelection is false only when nothing is checked`() {
        val none = ReceiverChecklist.build(manifest)
            .map { g -> g.copy(items = g.items.map { it.copy(checked = false) }) }
        assertThat(ReceiverChecklist.hasSelection(none)).isFalse()
        assertThat(ReceiverChecklist.hasSelection(ReceiverChecklist.build(manifest))).isTrue()
    }
}
