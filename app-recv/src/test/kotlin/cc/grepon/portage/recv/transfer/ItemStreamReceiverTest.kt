/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.transfer

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemMeta
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.ProtocolMessage
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.transport.SecureChannel
import cc.grepon.portage.transport.TransportException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.security.MessageDigest

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private class ScriptedChannel(vararg incoming: ProtocolMessage?) : SecureChannel {
    private val queue = ArrayDeque(incoming.toList())
    val sent = mutableListOf<ProtocolMessage>()
    override suspend fun send(message: ProtocolMessage) { sent += message }
    override suspend fun receive(): ProtocolMessage? =
        if (queue.isEmpty()) null else queue.removeFirst()
    override fun close() = Unit
}

class ItemStreamReceiverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = "BEGIN:VCARD...END:VCARD".toByteArray()
    private val meta = ItemMeta(1, ItemKind.CONTACTS_VCF, payload.size.toLong(), sha256(payload), "Contacts", "People")

    private fun itemFrames(meta: ItemMeta, bytes: ByteArray, chunk: Int = 8): List<ProtocolMessage> {
        val frames = mutableListOf<ProtocolMessage>(
            ProtocolMessage.ItemBegin(meta.itemId, meta.kind, meta.size, chunk),
        )
        var seq = 0
        bytes.toList().chunked(chunk).forEach { piece ->
            frames += ProtocolMessage.ItemData(meta.itemId, seq++, piece.toByteArray())
        }
        frames += ProtocolMessage.ItemEnd(meta.itemId, sha256(bytes))
        return frames
    }

    private fun receiver() = ItemStreamReceiver(tmp.newFolder())

    @Test
    fun `happy path stages, verifies, acks, applies, and batch-acks`() = runTest {
        val frames = itemFrames(meta, payload) + ProtocolMessage.BatchEnd(listOf(1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())
        val applied = mutableListOf<ByteArray>()

        val results = receiver().run(
            channel = channel,
            expected = mapOf(1 to meta),
            apply = { _, source: InputStream ->
                applied += source.readBytes()
                ApplyOutcome(ItemStatus.OK, "applied 1, skipped 0")
            },
            onEvent = { },
        )

        assertThat(applied.single()).isEqualTo(payload) // byte-exact through staging
        val ack = channel.sent.filterIsInstance<ProtocolMessage.ItemAck>().single()
        assertThat(ack.result.status).isEqualTo(ItemStatus.OK)
        val batchAck = channel.sent.filterIsInstance<ProtocolMessage.BatchAck>().single()
        assertThat(batchAck.results).isEqualTo(results)
        assertThat(results.single().status).isEqualTo(ItemStatus.OK)
        assertThat(results.single().detail).isEqualTo("applied 1, skipped 0")
    }

    @Test
    fun `a corrupted payload is HASH_MISMATCH and never reaches apply`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, "x".repeat(payload.size).toByteArray()),
            ProtocolMessage.ItemEnd(1, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ ->
            applyCalled = true
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.HASH_MISMATCH)
    }

    @Test
    fun `a failed item never aborts the batch — the next item still applies`() = runTest {
        val meta2 = ItemMeta(2, ItemKind.CALL_LOG, 5L, sha256("calls".toByteArray()), "Calls", "History")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, "garbage!".toByteArray()),
            ProtocolMessage.ItemEnd(1, "0".repeat(64)),
        ) + itemFrames(meta2, "calls".toByteArray()) + ProtocolMessage.BatchEnd(listOf(1, 2), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta, 2 to meta2), { m, _ ->
            ApplyOutcome(ItemStatus.OK, "ok ${m.itemId}")
        }) { }

        assertThat(results.map { it.status })
            .containsExactly(ItemStatus.HASH_MISMATCH, ItemStatus.OK).inOrder()
    }

    @Test
    fun `the receiver's own byte cap refuses an item even when manifest and wire agree`() = runTest {
        // PROTOCOL.md §5: receiver-enforced max item size REGARDLESS of manifest claims.
        val bigMeta = ItemMeta(1, ItemKind.CONTACTS_VCF, 100, "a".repeat(64), "Contacts", "People")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, bigMeta.kind, bigMeta.size, 8), // wire agrees: 100 bytes
            ProtocolMessage.ItemData(1, 0, ByteArray(100)),
            ProtocolMessage.ItemEnd(1, bigMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = ItemStreamReceiver(tmp.newFolder(), maxItemBytes = 4)
            .run(channel, mapOf(1 to bigMeta), { _, _ ->
                applyCalled = true
                ApplyOutcome(ItemStatus.OK)
            }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `an item whose size disagrees with the manifest is refused as OVERSIZE`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size + 999, 8), // liar
            ProtocolMessage.ItemData(1, 0, payload),
            ProtocolMessage.ItemEnd(1, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ ->
            applyCalled = true
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `more bytes than advertised flips the item to OVERSIZE mid-stream`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, payload),
            ProtocolMessage.ItemData(1, 1, payload), // double delivery
            ProtocolMessage.ItemEnd(1, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }

        assertThat(results.single().status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `an unrequested item id is drained and SKIPPED without touching apply`() = runTest {
        val rogue = ItemMeta(7, ItemKind.SETTINGS, 4L, sha256("evil".toByteArray()), "X", "Y")
        val frames = itemFrames(rogue, "evil".toByteArray()) +
            itemFrames(meta, payload) +
            ProtocolMessage.BatchEnd(listOf(7, 1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())
        val appliedKinds = mutableListOf<ItemKind>()

        val results = receiver().run(channel, mapOf(1 to meta), { m, _ ->
            appliedKinds += m.kind
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(appliedKinds).containsExactly(ItemKind.CONTACTS_VCF)
        assertThat(results.first { it.itemId == 7 }.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(results.first { it.itemId == 1 }.status).isEqualTo(ItemStatus.OK)
    }

    @Test
    fun `a kind that disagrees with the manifest is refused — the wire can't relabel items`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.SETTINGS, meta.size, 8), // manifest says contacts
            ProtocolMessage.ItemData(1, 0, payload),
            ProtocolMessage.ItemEnd(1, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ ->
            applyCalled = true
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.UNKNOWN_KIND)
    }

    @Test
    fun `selected items the sender never delivered are reported SKIPPED`() = runTest {
        val meta2 = ItemMeta(2, ItemKind.CALL_LOG, 5L, "f".repeat(64), "Calls", "History")
        val frames = itemFrames(meta, payload) + ProtocolMessage.BatchEnd(listOf(1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta, 2 to meta2), { _, _ ->
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(results.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.SKIPPED)
    }

    @Test
    fun `a dropped connection mid-item is a TransportException, not a hang or partial apply`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, payload.copyOf(8)),
            null, // connection lost
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false
        val staging = tmp.newFolder()

        val thrown = runCatching {
            ItemStreamReceiver(staging).run(channel, mapOf(1 to meta), { _, _ ->
                applyCalled = true
                ApplyOutcome(ItemStatus.OK)
            }) { }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(TransportException::class.java)
        assertThat(applyCalled).isFalse()
        assertThat(staging.listFiles().orEmpty()).isEmpty() // partials never survive
    }

    @Test
    fun `staging files are deleted after a successful run too`() = runTest {
        val staging = tmp.newFolder()
        val frames = itemFrames(meta, payload) + ProtocolMessage.BatchEnd(listOf(1), "done")

        ItemStreamReceiver(staging).run(
            ScriptedChannel(*frames.toTypedArray()),
            mapOf(1 to meta),
            { _, _ -> ApplyOutcome(ItemStatus.OK) },
        ) { }

        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a sender spraying endless unrequested items hits the item-count cap`() = runTest {
        // PROTOCOL.md §5: the receiver enforces a max item count regardless of manifest
        // claims. Stream far more ITEM_BEGINs than were selected and never send BATCH_END.
        val frames = mutableListOf<ProtocolMessage>()
        repeat(50) { n ->
            frames += ProtocolMessage.ItemBegin(1000 + n, ItemKind.SETTINGS, 1, 8)
            frames += ProtocolMessage.ItemEnd(1000 + n, "0".repeat(64))
        }
        val channel = ScriptedChannel(*frames.toTypedArray())

        val thrown = runCatching {
            receiver().run(channel, mapOf(1 to meta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(TransportException::class.java)
        assertThat(thrown?.message).contains("item-count")
    }

    @Test
    fun `apply throwing is contained as a WRITE_ERROR result`() = runTest {
        val frames = itemFrames(meta, payload) + ProtocolMessage.BatchEnd(listOf(1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ ->
            throw IllegalStateException("provider blew up")
        }) { }

        assertThat(results.single().status).isEqualTo(ItemStatus.WRITE_ERROR)
        // The receipt ack already went out OK; the apply failure rides BATCH_ACK.
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.BatchAck>().single().results.single().status)
            .isEqualTo(ItemStatus.WRITE_ERROR)
    }
}
