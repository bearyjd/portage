package com.ventouxlabs.portage.recv.transfer

import com.ventouxlabs.portage.lineage.CheckpointKey
import com.ventouxlabs.portage.lineage.LineageRepository
import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.model.ReceiptPhase
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.recv.testItemMeta
import com.ventouxlabs.portage.recv.testManifest
import com.ventouxlabs.portage.transport.SecureChannel
import com.ventouxlabs.portage.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ReceiverCheckpointTest {
    @get:Rule val tmp = TemporaryFolder()
    private val bytes = "a file worth keeping".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val meta = testItemMeta(1, ItemKind.USER_FILE, bytes.size.toLong(), hash, "file", "Files")

    private class Channel(messages: List<ProtocolMessage>, private val failReceipt: Boolean = false) : SecureChannel {
        private val messages = ArrayDeque(messages)
        val sent = mutableListOf<ProtocolMessage>()
        override suspend fun receive(): ProtocolMessage? = messages.removeFirstOrNull()
        override suspend fun send(message: ProtocolMessage) {
            if (failReceipt && message is ProtocolMessage.ItemAck) throw TransportException("disconnect after verified receipt")
            sent += message
        }
        override fun close() = Unit
    }

    private fun frames(withBytes: Boolean = true) = buildList {
        add(ProtocolMessage.ItemBegin(meta.itemId, meta.kind, meta.size, 128))
        if (withBytes) add(ProtocolMessage.ItemData(meta.itemId, 0, bytes))
        add(ProtocolMessage.ItemEnd(meta.itemId, meta.sha256))
        add(ProtocolMessage.BatchEnd(listOf(meta.itemId), "done"))
    }

    @Test fun `twenty receipt disconnects retain verified bytes without claiming apply`() = runTest {
        repeat(20) {
            val root = tmp.newFolder()
            lateinit var key: CheckpointKey
            LineageRepository(root).use { store ->
                val id = store.startNewMove().id
                store.establishResumeCredential(id)
                store.saveManifest(testManifest("phone", listOf(meta), meta.size, id))
                key = CheckpointKey.from(id, meta)
                try {
                    ItemStreamReceiver(store.stagingDir, lineageRepository = store, lineageId = id).run(
                        Channel(frames(), failReceipt = true), mapOf(1 to meta),
                        { _, _ -> error("apply must not happen before the receipt has been sent") }, {},
                    )
                    fail("expected disconnect")
                } catch (_: TransportException) { }
            }
            LineageRepository(root).use { store ->
                assertEquals(ReceiptPhase.RECEIVED_VERIFIED, store.checkpoint(key)?.phase)
                assertArrayEquals(bytes, store.verifiedStaged(key)?.readBytes())
            }
        }
    }

    @Test fun `twenty interrupted provider calls recover as unknown and replay verified staging`() = runTest {
        repeat(20) {
            val root = tmp.newFolder()
            lateinit var key: CheckpointKey
            LineageRepository(root).use { store ->
                val id = store.startNewMove().id
                store.establishResumeCredential(id)
                store.saveManifest(testManifest("phone", listOf(meta), meta.size, id))
                key = CheckpointKey.from(id, meta)
                try {
                    ItemStreamReceiver(store.stagingDir, lineageRepository = store, lineageId = id).run(
                        Channel(frames()), mapOf(1 to meta), { _, _ -> throw CancellationException("process interrupted") }, {},
                    )
                    fail("expected interruption")
                } catch (_: CancellationException) { }
            }
            LineageRepository(root).use { store ->
                assertEquals(ReceiptPhase.UNKNOWN_INTERRUPTED, store.checkpoint(key)?.phase)
                var applies = 0
                val channel = Channel(frames(withBytes = false))
                val results = ItemStreamReceiver(store.stagingDir, lineageRepository = store, lineageId = key.lineageId, resumeItems = setOf(1)).run(
                    channel, mapOf(1 to meta), { _, source ->
                        applies++
                        assertArrayEquals(bytes, source.readBytes())
                        ApplyOutcome(ItemStatus.OK)
                    }, {},
                )
                assertEquals(1, applies)
                assertEquals(ReceiptPhase.RECEIVED_VERIFIED, channel.sent.filterIsInstance<ProtocolMessage.ItemAck>().single().result.phase)
                assertEquals(ReceiptPhase.APPLIED_DURABLE, results.single().phase)
            }
        }
    }

    @Test fun `malformed occurrence has zero events files and provider calls`() = runTest {
        for (id in listOf("", "A".repeat(32), "f".repeat(31), "g".repeat(32), "../data")) {
            val staging = tmp.newFolder()
            var effects = 0
            try {
                ItemStreamReceiver(staging).run(Channel(frames()), mapOf(1 to meta.copy(occurrenceId = id)),
                    { _, _ -> effects++; ApplyOutcome(ItemStatus.OK) }, { effects++ })
                fail("malformed occurrence accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, effects)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `authenticated cancel commits local deletion before acknowledging`() = runTest {
        LineageRepository(tmp.newFolder()).use { store ->
            val id = store.startNewMove().id
            store.establishResumeCredential(id)
            store.saveManifest(testManifest("phone", listOf(meta), meta.size, id))
            val channel = Channel(listOf(ProtocolMessage.Cancel(id)))
            try {
                ItemStreamReceiver(store.stagingDir, lineageRepository = store, lineageId = id).run(channel, mapOf(1 to meta),
                    { _, _ -> error("cancel must not apply") }, {})
                fail("expected cancellation")
            } catch (_: TransportException) { }
            assertNull(store.active())
            assertEquals(ProtocolMessage.CancelAck(id), channel.sent.single())
            assertFalse(store.tombstones().single().peerDeleted)
        }
    }
}
