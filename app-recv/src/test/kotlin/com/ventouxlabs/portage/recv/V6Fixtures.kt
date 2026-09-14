package com.ventouxlabs.portage.recv

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.model.TransferManifest

const val TEST_LINEAGE = "0123456789abcdef0123456789abcdef"

fun testItemMeta(
    itemId: Int,
    kind: ItemKind,
    size: Long,
    sha256: String,
    displayName: String,
    group: String,
    occurrenceId: String = "%032x".format(itemId),
    wireSchemaVersion: Int = 1,
): ItemMeta = ItemMeta(itemId, kind, size, sha256, displayName, group, occurrenceId, wireSchemaVersion)

fun testManifest(
    senderName: String,
    items: List<ItemMeta>,
    totalBytes: Long,
    lineageId: String = TEST_LINEAGE,
): TransferManifest = TransferManifest(senderName, items, totalBytes, lineageId)

/** Existing provider tests get the same real v6 bootstrap prefix as the production channel. */
fun withLineageBootstrap(messages: List<ProtocolMessage?>): List<ProtocolMessage?> =
    if (messages.firstOrNull() is ProtocolMessage.Manifest)
        listOf(ProtocolMessage.LineageInit((messages.first() as ProtocolMessage.Manifest).manifest.lineageId, ByteArray(32) { 7 })) + messages
    else messages
