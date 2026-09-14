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

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemResult
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.model.ReceiptPhase
import com.ventouxlabs.portage.model.ResumePoint
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.transport.SecureChannel
import com.ventouxlabs.portage.transport.TransportException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import java.io.ByteArrayInputStream

private class ScriptedChannel(vararg incoming: ProtocolMessage?) : SecureChannel {
    private val queue = ArrayDeque(incoming.toList())
    val sent = mutableListOf<ProtocolMessage>()
    override suspend fun send(message: ProtocolMessage) { sent += message }
    override suspend fun receive(): ProtocolMessage? = if (queue.isEmpty()) null else queue.removeFirst()
    override fun close() = Unit
}

private class BytesExport(private val payload: ByteArray) : ExportProvider {
    override val kind = ItemKind.USER_FILE
    override val displayName = "File"
    override val group = "Files"
    override suspend fun available() = true
    override suspend fun exportTo(sink: OutputStream) = sink.write(payload)
}

class TransferEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val lineageId = "a".repeat(32)
    private val hello = ProtocolMessage.Hello("0.1.0", "recv")
    private val lineageAck = ProtocolMessage.LineageAck(lineageId)
    private val bootstrap get() = ProtocolMessage.LineageInit(lineageId, ByteArray(32) { 7 })
    private fun occurrence(id: Int) = id.toString(16).padStart(32, '0')
    private fun result(id: Int, status: ItemStatus = ItemStatus.OK) =
        ItemResult(id, status, occurrenceId = occurrence(id))
    private fun receipt(id: Int, status: ItemStatus = ItemStatus.OK) = ProtocolMessage.ItemAck(
        result(id, status).copy(phase = if (status == ItemStatus.OK) ReceiptPhase.RECEIVED_VERIFIED else ReceiptPhase.FAILED),
    )
    private suspend fun stage(vararg bytes: ByteArray): StagedManifest {
        var id = 0
        return ManifestBuilder(bytes.map(::BytesExport), tmp.newFolder(), "sender", lineageId,
            newOccurrenceId = { occurrence(++id) }).build()
    }

    @Test fun `bootstrap then selected items then exact final receipts`() = runTest {
        val staged = stage("one".toByteArray(), "two".toByteArray())
        val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1, 2)),
            receipt(1), receipt(2), ProtocolMessage.BatchAck(listOf(result(2), result(1))))
        val events = mutableListOf<TransferEngine.Event>()
        val results = TransferEngine().run(channel, staged, bootstrap) { events += it }
        assertThat(channel.sent.first()).isEqualTo(bootstrap)
        assertThat(channel.sent[1]).isInstanceOf(ProtocolMessage.Manifest::class.java)
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemBegin>().map { it.itemId }).containsExactly(1, 2).inOrder()
        assertThat(results.map { it.phase }).containsExactly(ReceiptPhase.APPLIED_DURABLE, ReceiptPhase.APPLIED_DURABLE)
        assertThat(events.filterIsInstance<TransferEngine.Event.ItemAcked>().map { it.result.phase })
            .containsExactly(ReceiptPhase.RECEIVED_VERIFIED, ReceiptPhase.RECEIVED_VERIFIED)
    }

    @Test fun `large payload chunks reassemble exactly`() = runTest {
        val bytes = ByteArray(150_000) { (it % 117).toByte() }
        val staged = stage(bytes)
        val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1)),
            receipt(1), ProtocolMessage.BatchAck(listOf(result(1))))
        TransferEngine(60_000).run(channel, staged, bootstrap) { }
        val chunks = channel.sent.filterIsInstance<ProtocolMessage.ItemData>()
        assertThat(chunks.map { it.seq }).containsExactly(0, 1, 2).inOrder()
        assertThat(chunks.flatMap { it.bytes.toList() }.toByteArray()).isEqualTo(bytes)
    }

    @Test fun `only selected subset sent and failed receipt does not abort next item`() = runTest {
        val staged = stage(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(2, 3)),
            receipt(2, ItemStatus.WRITE_ERROR), receipt(3),
            ProtocolMessage.BatchAck(listOf(result(2, ItemStatus.WRITE_ERROR), result(3))))
        val results = TransferEngine().run(channel, staged, bootstrap) { }
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemBegin>().map { it.itemId }).containsExactly(2, 3)
        assertThat(results.map { it.status }).containsExactly(ItemStatus.WRITE_ERROR, ItemStatus.OK).inOrder()
    }

    @Test fun `dropped final batch receipt stays unknown in twenty runs`() = runTest {
        repeat(20) {
            val staged = stage(byteArrayOf(1), byteArrayOf(2))
            val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1, 2)), receipt(1), receipt(2), null)
            val results = TransferEngine().run(channel, staged, bootstrap) { }
            assertThat(results.map { it.status }).containsExactly(ItemStatus.UNKNOWN_INTERRUPTED, ItemStatus.UNKNOWN_INTERRUPTED)
            assertThat(results.map { it.occurrenceId }).containsExactly(occurrence(1), occurrence(2))
        }
    }

    @Test fun `final receipts reject duplicates omissions foreign indices occurrences and receipt only phases`() = runTest {
        val invalid = listOf(
            listOf(result(1), result(1)),
            listOf(result(1)),
            listOf(result(1), result(99)),
            listOf(result(1), result(2).copy(occurrenceId = occurrence(1))),
            listOf(result(1), result(2).copy(phase = ReceiptPhase.RECEIVED_VERIFIED)),
            listOf(result(1), result(2).copy(status = ItemStatus.WRITE_ERROR)),
        )
        for (results in invalid) {
            val staged = stage(byteArrayOf(1), byteArrayOf(2))
            val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1, 2)),
                receipt(1), receipt(2), ProtocolMessage.BatchAck(results))
            assertThat(TransferEngine().run(channel, staged, bootstrap) { }.map { it.phase })
                .containsExactly(ReceiptPhase.UNKNOWN_INTERRUPTED, ReceiptPhase.UNKNOWN_INTERRUPTED)
        }
    }

    @Test fun `item receipts reject wrong index occurrence or premature applied phase`() = runTest {
        for (invalid in listOf(result(1), receipt(2).result, receipt(1).result.copy(occurrenceId = "b".repeat(32)))) {
            val staged = stage(byteArrayOf(1))
            val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1)),
                ProtocolMessage.ItemAck(invalid), ProtocolMessage.BatchAck(listOf(result(1))))
            val events = mutableListOf<TransferEngine.Event>()
            assertThat(TransferEngine().run(channel, staged, bootstrap) { events += it }.single().phase)
                .isEqualTo(ReceiptPhase.UNKNOWN_INTERRUPTED)
            assertThat(events.filterIsInstance<TransferEngine.Event.ItemAcked>()).isEmpty()
        }
    }

    @Test fun `bad bootstrap and malformed selection fail before streaming`() = runTest {
        for (messages in listOf(
            arrayOf<ProtocolMessage>(ProtocolMessage.Select(listOf(1))),
            arrayOf(hello, ProtocolMessage.LineageAck("b".repeat(32))),
            arrayOf(hello, lineageAck, ProtocolMessage.Select(listOf(1, 1))),
            arrayOf(hello, lineageAck, ProtocolMessage.Select(listOf(1, 99))),
        )) {
            val channel = ScriptedChannel(*messages)
            val thrown = runCatching { TransferEngine().run(channel, stage(byteArrayOf(1)), bootstrap) { } }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(TransportException::class.java)
            assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemBegin>()).isEmpty()
        }
    }

    @Test fun `whole file resume keeps begin end and receipt but suppresses bytes`() = runTest {
        val staged = stage(byteArrayOf(1, 2, 3))
        val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1), listOf(ResumePoint(1, 3))),
            receipt(1), ProtocolMessage.BatchAck(listOf(result(1))))
        val results = TransferEngine().run(channel, staged, ProtocolMessage.LineageResume(lineageId)) { }
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemData>()).isEmpty()
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemBegin>()).hasSize(1)
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemEnd>()).hasSize(1)
        assertThat(results.single().phase).isEqualTo(ReceiptPhase.APPLIED_DURABLE)
    }

    @Test fun `partial resume requests and changed saved payload fail closed`() = runTest {
        val staged = stage(byteArrayOf(1, 2, 3))
        val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1), listOf(ResumePoint(1, 1))),
            receipt(1), ProtocolMessage.BatchAck(listOf(result(1))))
        val failure = runCatching {
            TransferEngine().run(channel, staged, ProtocolMessage.LineageResume(lineageId)) { }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(TransportException::class.java)
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemData>()).isEmpty()
        staged.items.single().file.writeBytes(byteArrayOf(3, 2, 1))
        val changed = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1)))
        assertThat(TransferEngine().run(changed, staged, bootstrap) { }.single().phase).isEqualTo(ReceiptPhase.PREPARED)
        assertThat(changed.sent.filterIsInstance<ProtocolMessage.ItemBegin>()).isEmpty()
    }

    @Test fun `cancel claims peer deletion only after exact authenticated acknowledgement`() = runTest {
        for (reply in listOf(ProtocolMessage.CancelAck(lineageId), ProtocolMessage.CancelAck("b".repeat(32)), null)) {
            val staged = stage(byteArrayOf(1))
            val channel = ScriptedChannel(hello, lineageAck, reply)
            val thrown = runCatching { TransferEngine().run(channel, staged, bootstrap,
                isCancellationRequested = { true }) { } }.exceptionOrNull() as TransferCancelledException
            assertThat(thrown.peerDeletionConfirmed).isEqualTo(reply == ProtocolMessage.CancelAck(lineageId))
            assertThat(channel.sent.last()).isEqualTo(ProtocolMessage.Cancel(lineageId))
            assertThat(channel.sent.filterIsInstance<ProtocolMessage.Manifest>()).isEmpty()
        }
    }

    @Test fun `peer cancel invokes local deletion before acknowledgement`() = runTest {
        val staged = stage(byteArrayOf(1))
        val channel = ScriptedChannel(hello, ProtocolMessage.Cancel(lineageId))
        var deleted = false
        val thrown = runCatching { TransferEngine().run(channel, staged, bootstrap,
            onPeerCancel = {
                assertThat(channel.sent.filterIsInstance<ProtocolMessage.CancelAck>()).isEmpty()
                deleted = true
            }) { } }.exceptionOrNull()
        assertThat(deleted).isTrue()
        assertThat(thrown).isInstanceOf(TransferCancelledException::class.java)
        assertThat((thrown as TransferCancelledException).peerDeletionConfirmed).isFalse()
        assertThat(thrown.peerInitiated).isTrue()
        assertThat(channel.sent.last()).isEqualTo(ProtocolMessage.CancelAck(lineageId))
    }

    @Test fun `keepalive messages are tolerated`() = runTest {
        val staged = stage(byteArrayOf(1))
        val channel = ScriptedChannel(ProtocolMessage.Ping, hello, ProtocolMessage.Ping, lineageAck,
            ProtocolMessage.Ping, ProtocolMessage.Select(listOf(1)), receipt(1), ProtocolMessage.Ping,
            ProtocolMessage.BatchAck(listOf(result(1))))
        assertThat(TransferEngine().run(channel, staged, bootstrap) { }.single().status).isEqualTo(ItemStatus.OK)
    }

    @Test fun `missing or invalid final receipt preserves known failed items`() = runTest {
        for (final in listOf(null, ProtocolMessage.BatchAck(listOf(result(1), result(1))))) {
            val staged = stage(byteArrayOf(1), byteArrayOf(2))
            val knownFailure = receipt(1, ItemStatus.WRITE_ERROR).result.copy(detail = "destination denied")
            val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1, 2)),
                ProtocolMessage.ItemAck(knownFailure), receipt(2), final)
            val results = TransferEngine().run(channel, staged, bootstrap) { }
            assertThat(results[0]).isEqualTo(knownFailure)
            assertThat(results[1].phase).isEqualTo(ReceiptPhase.UNKNOWN_INTERRUPTED)
        }
    }

    @Test fun `mid-batch interruption preserves failed verified and never-started truth separately`() = runTest {
        val staged = stage(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4))
        val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1, 2, 3, 4)),
            receipt(1, ItemStatus.WRITE_ERROR), receipt(2), null)
        val results = TransferEngine().run(channel, staged, bootstrap) { }
        assertThat(results.map { it.phase }).containsExactly(ReceiptPhase.FAILED,
            ReceiptPhase.UNKNOWN_INTERRUPTED, ReceiptPhase.UNKNOWN_INTERRUPTED, ReceiptPhase.PREPARED).inOrder()
        assertThat(results.last().status).isNotEqualTo(ItemStatus.OK)
    }

    @Test fun `payload validation executes on the injected file IO thread`() = runTest {
        val staged = stage(byteArrayOf(1, 2, 3))
        val threads = mutableListOf<String>()
        val wrappedFile = object : File(staged.items.single().file.absolutePath) {
            override fun length(): Long {
                threads += Thread.currentThread().name
                return super.length()
            }
        }
        val checked = staged.copy(items = listOf(staged.items.single().copy(file = wrappedFile)))
        val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1)),
            receipt(1), ProtocolMessage.BatchAck(listOf(result(1))))
        Executors.newSingleThreadExecutor { Thread(it, "sender-file-io") }.asCoroutineDispatcher().use { io ->
            TransferEngine().run(channel, checked, bootstrap, ioDispatcher = io) { }
        }
        assertThat(threads).isNotEmpty()
        assertThat(threads.toSet()).containsExactly("sender-file-io")
    }

    @Test fun `cancellation immediately after opening a payload still closes its stream`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val staged = stage(bytes)
        var closed = false
        var opened = false
        val stream = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val channel = ScriptedChannel(hello, lineageAck, ProtocolMessage.Select(listOf(1)))
        lateinit var sending: Job
        val engine = TransferEngine(openPayload = {
            opened = true
            sending.cancel()
            stream
        })
        sending = launch(start = CoroutineStart.LAZY) {
            engine.run(channel, staged, bootstrap, ioDispatcher = StandardTestDispatcher(testScheduler)) { }
        }
        sending.start()
        advanceUntilIdle()
        assertThat(opened).isTrue()
        assertThat(sending.isCancelled).isTrue()
        assertThat(closed).isTrue()
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.ItemData>()).isEmpty()
    }
}
