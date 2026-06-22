/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.transfer

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemResult
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.transport.SecureChannel
import com.ventouxlabs.portage.transport.TransportException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream

private class ScriptedChannel(vararg incoming: ProtocolMessage?) : SecureChannel {
    private val queue = ArrayDeque(incoming.toList())
    val sent = mutableListOf<ProtocolMessage>()
    var closed = false

    override suspend fun send(message: ProtocolMessage) { sent += message }
    override suspend fun receive(): ProtocolMessage? =
        if (queue.isEmpty()) null else queue.removeFirst()
    override fun close() { closed = true }
}

private class BytesExport(
    override val kind: ItemKind,
    override val displayName: String,
    override val group: String,
    private val payload: ByteArray,
) : ExportProvider {
    override suspend fun available() = payload.isNotEmpty()
    override suspend fun exportTo(sink: OutputStream) = sink.write(payload)
}

class TransferEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hello = ProtocolMessage.Hello("0.1.0", "test-recv")

    private suspend fun stage(vararg payloads: Pair<ItemKind, ByteArray>): StagedManifest =
        ManifestBuilder(
            payloads.map { (kind, bytes) -> BytesExport(kind, kind.wire, "G", bytes) },
            tmp.newFolder(),
            "sender",
        ).build()

    private fun ack(id: Int, status: ItemStatus = ItemStatus.OK) =
        ProtocolMessage.ItemAck(ItemResult(id, status))

    @Test
    fun `happy path streams both items and returns the receiver's results`() = runTest {
        val staged = stage(
            ItemKind.CONTACTS_VCF to "vcardvcard".toByteArray(),
            ItemKind.CALL_LOG to "callscalls".toByteArray(),
        )
        val channel = ScriptedChannel(
            hello,
            ProtocolMessage.Select(want = listOf(1, 2)),
            ack(1), ack(2),
            ProtocolMessage.BatchAck(listOf(ItemResult(1, ItemStatus.OK), ItemResult(2, ItemStatus.OK))),
        )
        val events = mutableListOf<TransferEngine.Event>()

        val results = TransferEngine().run(channel, staged) { events += it }

        // MANIFEST went out first, then per-item BEGIN/DATA/END, then BATCH_END.
        assertThat(channel.sent.first()).isInstanceOf(ProtocolMessage.Manifest::class.java)
        val begins = channel.sent.filterIsInstance<ProtocolMessage.ItemBegin>()
        assertThat(begins.map { it.itemId }).containsExactly(1, 2).inOrder()
        val data = channel.sent.filterIsInstance<ProtocolMessage.ItemData>()
        assertThat(data.first { it.itemId == 1 }.bytes).isEqualTo("vcardvcard".toByteArray())
        val ends = channel.sent.filterIsInstance<ProtocolMessage.ItemEnd>()
        assertThat(ends.map { it.itemId }).containsExactly(1, 2).inOrder()
        assertThat(ends[0].sha256).isEqualTo(staged.items[0].meta.sha256)
        val batchEnd = channel.sent.filterIsInstance<ProtocolMessage.BatchEnd>().single()
        assertThat(batchEnd.sent).containsExactly(1, 2).inOrder()

        assertThat(results.map { it.status }).containsExactly(ItemStatus.OK, ItemStatus.OK)
        assertThat(events.filterIsInstance<TransferEngine.Event.ItemAcked>()).hasSize(2)
    }

    @Test
    fun `large items are chunked with increasing seq and reassemble exactly`() = runTest {
        val big = ByteArray(150_000) { (it % 117).toByte() }
        val staged = stage(ItemKind.CONTACTS_VCF to big)
        val channel = ScriptedChannel(
            hello,
            ProtocolMessage.Select(want = listOf(1)),
            ack(1),
            ProtocolMessage.BatchAck(listOf(ItemResult(1, ItemStatus.OK))),
        )

        TransferEngine(chunkSize = 60_000).run(channel, staged) { }

        val data = channel.sent.filterIsInstance<ProtocolMessage.ItemData>()
        assertThat(data).hasSize(3)
        assertThat(data.map { it.seq }).containsExactly(0, 1, 2).inOrder()
        val reassembled = data.flatMap { it.bytes.toList() }.toByteArray()
        assertThat(reassembled).isEqualTo(big)
    }

    @Test
    fun `only the selected subset is sent`() = runTest {
        val staged = stage(
            ItemKind.CONTACTS_VCF to "a".toByteArray(),
            ItemKind.CALL_LOG to "b".toByteArray(),
        )
        val channel = ScriptedChannel(
            hello,
            ProtocolMessage.Select(want = listOf(2)),
            ack(2),
            ProtocolMessage.BatchAck(listOf(ItemResult(2, ItemStatus.OK))),
        )

        TransferEngine().run(channel, staged) { }

        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemBegin>().map { it.itemId })
            .containsExactly(2)
    }

    @Test
    fun `a failed item ack never aborts the batch`() = runTest {
        val staged = stage(
            ItemKind.CONTACTS_VCF to "a".toByteArray(),
            ItemKind.CALL_LOG to "b".toByteArray(),
        )
        val channel = ScriptedChannel(
            hello,
            ProtocolMessage.Select(want = listOf(1, 2)),
            ack(1, ItemStatus.WRITE_ERROR), ack(2),
            ProtocolMessage.BatchAck(
                listOf(ItemResult(1, ItemStatus.WRITE_ERROR), ItemResult(2, ItemStatus.OK)),
            ),
        )

        val results = TransferEngine().run(channel, staged) { }

        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemBegin>()).hasSize(2)
        assertThat(results.map { it.status })
            .containsExactly(ItemStatus.WRITE_ERROR, ItemStatus.OK).inOrder()
    }

    @Test
    fun `requested ids that were never staged are ignored`() = runTest {
        val staged = stage(ItemKind.CONTACTS_VCF to "a".toByteArray())
        val channel = ScriptedChannel(
            hello,
            ProtocolMessage.Select(want = listOf(1, 99)),
            ack(1),
            ProtocolMessage.BatchAck(listOf(ItemResult(1, ItemStatus.OK))),
        )

        TransferEngine().run(channel, staged) { }

        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemBegin>().map { it.itemId })
            .containsExactly(1)
    }

    @Test
    fun `a missing BATCH_ACK falls back to the per-item acks`() = runTest {
        val staged = stage(ItemKind.CONTACTS_VCF to "a".toByteArray())
        val channel = ScriptedChannel(
            hello,
            ProtocolMessage.Select(want = listOf(1)),
            ack(1),
            null, // receiver closed without BATCH_ACK
        )

        val results = TransferEngine().run(channel, staged) { }

        assertThat(results).containsExactly(ItemResult(1, ItemStatus.OK))
    }

    @Test
    fun `keepalive PINGs are tolerated at any receive point`() = runTest {
        val staged = stage(ItemKind.CONTACTS_VCF to "a".toByteArray())
        val channel = ScriptedChannel(
            ProtocolMessage.Ping,
            hello,
            ProtocolMessage.Ping,
            ProtocolMessage.Select(want = listOf(1)),
            ack(1),
            ProtocolMessage.BatchAck(listOf(ItemResult(1, ItemStatus.OK))),
        )

        val results = TransferEngine().run(channel, staged) { }

        assertThat(results.single().status).isEqualTo(ItemStatus.OK)
    }

    @Test
    fun `anything but HELLO first is a transport failure`() = runTest {
        val staged = stage(ItemKind.CONTACTS_VCF to "a".toByteArray())
        val channel = ScriptedChannel(ProtocolMessage.Select(want = listOf(1)))

        val thrown = runCatching { TransferEngine().run(channel, staged) { } }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(TransportException::class.java)
    }
}
