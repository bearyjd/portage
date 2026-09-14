package com.ventouxlabs.portage.lineage

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.ManifestValidation
import com.ventouxlabs.portage.model.ReceiptPhase
import com.ventouxlabs.portage.model.TransferManifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class LineageRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private val bytes = "same payload".toByteArray()
    private fun meta(id: Int = 1, occurrence: String = "%032x".format(id)) = ItemMeta(
        id, ItemKind.USER_FILE, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "file", "Files", occurrence,
    )
    private fun LineageRepository.manifest(vararg items: ItemMeta): TransferManifest = TransferManifest(
        "phone", items.toList(), items.sumOf { it.size }, checkNotNull(active()).id,
    ).also(::saveManifest)

    @Test fun `new moves consume exactly 16 random bytes and reopening never generates identity`() {
        val requests = mutableListOf<Int>()
        val random = object : SecureRandom() {
            private var serial = 1
            override fun nextBytes(bytes: ByteArray) { requests += bytes.size; bytes.fill((serial++).toByte()) }
        }
        val root = tmp.newFolder()
        val ids = mutableSetOf<String>()
        LineageRepository(root, { now }, random).use { store ->
            repeat(100) { ids += store.startNewMove().id }
        }
        assertEquals(100, ids.size)
        assertEquals(List(100) { 16 }, requests)
        LineageRepository(root, { now }, random).use { assertTrue(it.active()?.id in ids) }
        assertEquals(100, requests.size)
    }

    @Test fun `credential persists once and cannot be replaced by an initial QR`() {
        val root = tmp.newFolder()
        var id = ""
        lateinit var secret: ByteArray
        LineageRepository(root, { now }).use {
            id = it.startNewMove().id
            secret = it.establishResumeCredential(id)
            assertEquals(32, secret.size)
            assertArrayEquals(secret, it.establishResumeCredential(id))
            assertEquals(CredentialState.PENDING, it.active()?.credentialState)
            assertThrows(IllegalStateException::class.java) { it.credentialForResume() }
            it.confirmResumeCredential(id)
            assertThrows(IllegalArgumentException::class.java) { it.acceptInitial("f".repeat(32), ByteArray(32)) }
        }
        LineageRepository(root, { now }).use {
            assertEquals(id, it.active()?.id)
            assertArrayEquals(secret, it.credentialForResume())
        }
    }

    @Test fun `identical bytes have separate keys and a new lineage cannot read the previous one`() {
        LineageRepository(tmp.newFolder(), { now }).use {
            val id = it.startNewMove().id
            it.manifest(meta(1), meta(2))
            val first = CheckpointKey.from(id, meta(1))
            val second = CheckpointKey.from(id, meta(2))
            it.transition(first, ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED)
            assertEquals(ReceiptPhase.PREPARED, it.checkpoint(second)?.phase)
            it.startNewMove()
            assertThrows(IllegalArgumentException::class.java) { it.checkpoint(first) }
        }
    }

    @Test fun `restarts preserve receipt truth at every boundary in twenty repetitions`() {
        for (boundary in listOf(ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED, ReceiptPhase.APPLYING, ReceiptPhase.APPLIED_DURABLE)) {
            repeat(20) {
                val root = tmp.newFolder()
                lateinit var key: CheckpointKey
                LineageRepository(root, { now }).use { store ->
                    val id = store.startNewMove().id
                    store.manifest(meta())
                    key = CheckpointKey.from(id, meta())
                    if (boundary != ReceiptPhase.PREPARED) store.transition(key, ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED)
                    if (boundary == ReceiptPhase.APPLYING || boundary == ReceiptPhase.APPLIED_DURABLE) store.transition(key, ReceiptPhase.RECEIVED_VERIFIED, ReceiptPhase.APPLYING)
                    if (boundary == ReceiptPhase.APPLIED_DURABLE) store.transition(key, ReceiptPhase.APPLYING, ReceiptPhase.APPLIED_DURABLE)
                }
                LineageRepository(root, { now }).use { store ->
                    val expected = if (boundary == ReceiptPhase.APPLYING) ReceiptPhase.UNKNOWN_INTERRUPTED else boundary
                    assertEquals(expected, store.checkpoint(key)?.phase)
                    assertFalse(store.canSkipApply(key))
                }
            }
        }
    }

    @Test fun `staging requires exact hash size and key and expires precisely at 24 hours`() {
        LineageRepository(tmp.newFolder(), { now }).use {
            val id = it.startNewMove().id
            it.manifest(meta())
            val key = CheckpointKey.from(id, meta())
            it.stagingDir.mkdirs()
            val file = File(it.stagingDir, "item.bin").apply { writeBytes(bytes) }
            it.transition(key, ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED, file)
            assertEquals(file, it.verifiedStaged(key))
            file.writeBytes(ByteArray(bytes.size))
            assertNull(it.verifiedStaged(key))
            file.writeBytes(bytes)
            now += LineageRepository.STAGING_TTL_MS - 1
            assertEquals(file, it.verifiedStaged(key))
            now++
            assertNull(it.verifiedStaged(key))
            assertFalse(file.exists())
        }
    }

    @Test fun `offline peer purges at 30 days exactly and viewing never renews`() {
        val root = tmp.newFolder()
        LineageRepository(root, { now }).use {
            val id = it.startNewMove().id
            it.establishResumeCredential(id)
            it.confirmResumeCredential(id)
        }
        now += LineageRepository.LINEAGE_TTL_MS - 1
        LineageRepository(root, { now }).use { assertNotNull(it.active()); assertEquals(32, it.credentialForResume().size) }
        now++
        LineageRepository(root, { now }).use {
            assertNull(it.active())
            assertThrows(IllegalStateException::class.java) { it.credentialForResume() }
            assertEquals("expired", it.tombstones().single().reason)
            assertFalse(it.tombstones().single().peerDeleted)
        }
    }

    @Test fun `cancellation tombstones and removes credential before remote deletion can be claimed`() {
        val root = tmp.newFolder()
        lateinit var id: String
        LineageRepository(root, { now }).use {
            id = it.startNewMove().id
            val secret = it.establishResumeCredential(id)
            it.cancel(id)
            assertFalse(it.tombstones().single().peerDeleted)
            assertFalse(File(root, "lineage.json").readText().contains(secret.joinToString("") { b -> "%02x".format(b) }))
            it.markPeerDeleted(id)
        }
        LineageRepository(root, { now }).use {
            assertNull(it.active())
            assertTrue(it.tombstones().single().peerDeleted)
            assertThrows(IllegalArgumentException::class.java) { it.acceptInitial(id, ByteArray(32)) }
        }
    }

    @Test fun `illegal transitions and stale compare-and-set fail closed`() {
        LineageRepository(tmp.newFolder(), { now }).use {
            val id = it.startNewMove().id
            it.manifest(meta())
            val key = CheckpointKey.from(id, meta())
            assertThrows(IllegalArgumentException::class.java) { it.transition(key, ReceiptPhase.PREPARED, ReceiptPhase.APPLIED_DURABLE) }
            it.transition(key, ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED)
            assertThrows(IllegalArgumentException::class.java) { it.transition(key, ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED) }
        }
    }

    @Test fun `corrupt unknown and cross-lineage snapshots never open as empty`() {
        for (content in listOf("garbage", "{\"version\":99}", "{\"version\":1,\"unexpected\":true}")) {
            val root = tmp.newFolder()
            File(root, "lineage.json").writeText(content)
            assertThrows(Exception::class.java) { LineageRepository(root, { now }) }
        }
    }

    @Test fun `malformed and duplicate occurrences fail before checkpoint or staging access`() {
        for (occurrence in listOf("", "f".repeat(31), "f".repeat(33), "A".repeat(32), "z".repeat(32), "../payload")) {
            assertThrows(IllegalArgumentException::class.java) { CheckpointKey.from("0".repeat(32), meta(occurrence = occurrence)) }
        }
        val manifest = TransferManifest("phone", listOf(meta(1), meta(2, meta(1).occurrenceId)), bytes.size * 2L, "0".repeat(32))
        assertThrows(IllegalArgumentException::class.java) { ManifestValidation.requireIdentities(manifest) }
    }

    @Test fun `snapshot parse and validation failures never expose secret content or causes`() {
        val secret = "ab".repeat(32)
        for (corrupt in listOf(
            "{\"version\":2,\"active\":{\"credentialHex\":\"$secret\",\"bad\":",
            "{\"version\":2,\"active\":\"$secret\",\"checkpoints\":[],\"tombstones\":[]}",
            "{\"version\":2,\"active\":null,\"checkpoints\":[],\"tombstones\":[],\"$secret\":true}",
        )) {
            val root = tmp.newFolder()
            File(root, "lineage.json").writeText(corrupt)
            val error = assertThrows(SnapshotLoadException::class.java) { LineageRepository(root, { now }) }
            assertEquals(SnapshotLoadException().message, error.message)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.stackTraceToString().contains(secret))
        }
        val unreadable = tmp.newFolder()
        File(unreadable, "lineage.json").mkdir()
        val error = assertThrows(SnapshotLoadException::class.java) { LineageRepository(unreadable, { now }) }
        assertNull(error.cause)
        val notDirectory = File(tmp.newFolder(), secret).apply { writeText("not a directory") }
        val directoryError = assertThrows(SnapshotLoadException::class.java) { LineageRepository(notDirectory, { now }) }
        assertNull(directoryError.cause)
        assertFalse(directoryError.stackTraceToString().contains(secret))
        val locked = tmp.newFolder()
        LineageRepository(locked, { now }).use {
            val lockError = assertThrows(LineageBusyException::class.java) { LineageRepository(locked, { now }) }
            assertNull(lockError.cause)
            assertNull(it.active())
        }
        LineageRepository(locked, { now }).use { assertNull(it.active()) }
    }

    @Test fun `pending bootstrap survives both delivery crash boundaries without generating a replacement`() {
        val requests = mutableListOf<Int>()
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                requests += bytes.size
                bytes.fill(requests.size.toByte())
            }
        }
        val senderRoot = tmp.newFolder()
        val receiverRoot = tmp.newFolder()
        lateinit var id: String
        lateinit var secret: ByteArray
        LineageRepository(senderRoot, { now }, random).use {
            id = it.startNewMove().id
            secret = it.establishResumeCredential(id)
        }
        // Sender died before delivering INIT. Reopen must offer NEW with these same pending bytes.
        LineageRepository(senderRoot, { now }, random).use { sender ->
            assertEquals(CredentialState.PENDING, sender.active()?.credentialState)
            assertThrows(IllegalStateException::class.java) { sender.credentialForResume() }
            assertArrayEquals(secret, sender.pendingBootstrapCredential(id))
            assertArrayEquals(secret, sender.establishResumeCredential(id))
            LineageRepository(receiverRoot, { now }).use { receiver -> receiver.acceptInitial(id, secret) }
        }
        // Receiver persisted INIT, but sender died before receiving ACK. Both restart; NEW resend is
        // allowed only with the same lineage and credential, then the matching ACK confirms sender.
        LineageRepository(senderRoot, { now }, random).use { sender ->
            LineageRepository(receiverRoot, { now }).use { receiver ->
                assertArrayEquals(secret, receiver.credentialForResume())
                assertThrows(IllegalArgumentException::class.java) { receiver.acceptInitial(id, ByteArray(32)) }
                assertThrows(IllegalArgumentException::class.java) { receiver.acceptInitial("f".repeat(32), secret) }
                receiver.acceptInitial(id, sender.pendingBootstrapCredential(id))
                sender.confirmResumeCredential(id)
                assertArrayEquals(receiver.credentialForResume(), sender.credentialForResume())
            }
        }
        LineageRepository(senderRoot, { now }, random).use {
            assertEquals(CredentialState.ESTABLISHED, it.active()?.credentialState)
            assertArrayEquals(secret, it.credentialForResume())
            assertThrows(IllegalArgumentException::class.java) { it.establishResumeCredential(id) }
            assertThrows(IllegalArgumentException::class.java) { it.pendingBootstrapCredential(id) }
        }
        assertEquals(listOf(16, 32), requests)
    }

    @Test fun `prepared manifest and every staged filename become visible in one atomic snapshot`() {
        val root = tmp.newFolder()
        lateinit var manifest: TransferManifest
        LineageRepository(root, { now }).use { store ->
            val id = store.startNewMove().id
            val items = listOf(meta(1), meta(2))
            manifest = TransferManifest("phone", items, items.sumOf { it.size }, id)
            store.stagingDir.mkdirs()
            val files = items.associate { it.itemId to File(store.stagingDir, "${it.occurrenceId}.bin").apply { writeBytes(bytes) } }
            assertThrows(IllegalArgumentException::class.java) { store.savePreparedManifest(manifest, files - 2) }
            assertNull(store.active()?.manifest)
            assertFalse(checkNotNull(store.active()).prepared)
            files.getValue(2).writeBytes(ByteArray(bytes.size))
            assertThrows(IllegalArgumentException::class.java) { store.savePreparedManifest(manifest, files) }
            assertNull(store.active()?.manifest)
            files.getValue(2).writeBytes(bytes)
            store.savePreparedManifest(manifest, files)
            assertTrue(checkNotNull(store.active()).prepared)
        }
        LineageRepository(root, { now }).use { store ->
            assertEquals(manifest, store.active()?.manifest)
            assertTrue(checkNotNull(store.active()).prepared)
            manifest.items.forEach { assertArrayEquals(bytes, store.stagedFile(CheckpointKey.from(manifest.lineageId, it))?.readBytes()) }
        }
    }

    @Test fun `crashes around prepared snapshot replacement reopen as old or complete new state`() {
        for (crashAfterReplace in listOf(false, true)) repeat(20) {
            val root = tmp.newFolder()
            var armed = false
            val crash = { if (armed) throw SimulatedCrash() }
            lateinit var manifest: TransferManifest
            LineageRepository(root, { now }, beforeSnapshotReplace = if (crashAfterReplace) ({}) else crash,
                afterSnapshotReplace = if (crashAfterReplace) crash else ({})).use { store ->
                val id = store.startNewMove().id
                val items = listOf(meta(1), meta(2), meta(3))
                manifest = TransferManifest("phone", items, items.sumOf { it.size }, id)
                store.stagingDir.mkdirs()
                val files = items.associate { it.itemId to File(store.stagingDir, "${it.occurrenceId}.bin").apply { writeBytes(bytes) } }
                armed = true
                assertThrows(SimulatedCrash::class.java) { store.savePreparedManifest(manifest, files) }
            }
            LineageRepository(root, { now }).use { store ->
                val active = checkNotNull(store.active())
                assertEquals(crashAfterReplace, active.prepared)
                if (crashAfterReplace) {
                    assertEquals(manifest, active.manifest)
                    manifest.items.forEach { assertNotNull(store.stagedFile(CheckpointKey.from(active.id, it))) }
                } else {
                    assertNull(active.manifest)
                    manifest.items.forEach { assertNull(store.checkpoint(CheckpointKey.from(active.id, it))) }
                }
            }
        }
    }

    @Test fun `cancelled preparation and incomplete metadata never advertise a prepared move`() {
        val root = tmp.newFolder()
        LineageRepository(root, { now }).use { store ->
            val id = store.startNewMove().id
            val manifest = store.manifest(meta(1), meta(2))
            store.stagingDir.mkdirs()
            val file = File(store.stagingDir, "one.bin").apply { writeBytes(bytes) }
            store.prepare(CheckpointKey.from(id, meta(1)), file)
            assertFalse(checkNotNull(store.active()).prepared)
            val files = mapOf(1 to file, 2 to File(store.stagingDir, "two.bin").apply { writeBytes(bytes) })
            var hashChecks = 0
            assertThrows(SimulatedCrash::class.java) {
                store.savePreparedManifest(manifest, files) { if (++hashChecks == 5) throw SimulatedCrash() }
            }
            assertFalse(checkNotNull(store.active()).prepared)
        }
        LineageRepository(root, { now }).use { assertFalse(checkNotNull(it.active()).prepared) }
    }

    @Test fun `a prepared snapshot missing one staged filename is rejected on reopen`() {
        val root = tmp.newFolder()
        LineageRepository(root, { now }).use { store ->
            val id = store.startNewMove().id
            val manifest = TransferManifest("phone", listOf(meta()), bytes.size.toLong(), id)
            store.stagingDir.mkdirs()
            val file = File(store.stagingDir, "item.bin").apply { writeBytes(bytes) }
            store.savePreparedManifest(manifest, mapOf(1 to file))
        }
        val file = File(root, "lineage.json")
        file.writeText(file.readText().replace("\"stagedName\":\"item.bin\"", "\"stagedName\":null"))
        val error = assertThrows(SnapshotLoadException::class.java) { LineageRepository(root, { now }) }
        assertNull(error.cause)
    }

    @Test fun `stale lineage reconciliation cannot change or expire a later move`() {
        val root = tmp.newFolder()
        LineageRepository(root, { now }).use { store ->
            val first = store.startNewMove().id
            val second = store.startNewMove().id
            store.manifest(meta())
            val key = CheckpointKey.from(second, meta())
            store.transition(key, ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED)
            store.transition(key, ReceiptPhase.RECEIVED_VERIFIED, ReceiptPhase.APPLYING)
            val before = File(root, "lineage.json").readText()
            assertFalse(store.reconcileInterrupted(first))
            assertEquals(before, File(root, "lineage.json").readText())
            assertEquals(ReceiptPhase.APPLYING, store.checkpoint(key)?.phase)
            assertTrue(store.reconcileInterrupted(second))
            assertEquals(ReceiptPhase.UNKNOWN_INTERRUPTED, store.checkpoint(key)?.phase)
            val reconciled = File(root, "lineage.json").readText()
            now += LineageRepository.LINEAGE_TTL_MS
            assertFalse(store.reconcileInterrupted(first))
            assertEquals(reconciled, File(root, "lineage.json").readText())
            assertThrows(IllegalArgumentException::class.java) { store.reconcileInterrupted("bad") }
        }
    }

    @Test fun `paused staged validation does not block active or cancellation and cannot return stale bytes`() {
        for (verifiedOnly in listOf(false, true)) {
            val enteredRead = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val pauseOnce = AtomicBoolean(true)
            val workers = Executors.newFixedThreadPool(3)
            val store = LineageRepository(tmp.newFolder(), { now }, beforeStagedRead = {
                if (pauseOnce.compareAndSet(true, false)) {
                    enteredRead.countDown()
                    check(releaseRead.await(10, TimeUnit.SECONDS))
                }
            })
            try {
                val id = store.startNewMove().id
                store.manifest(meta())
                val key = CheckpointKey.from(id, meta())
                store.stagingDir.mkdirs()
                val file = File(store.stagingDir, "item.bin").apply { writeBytes(bytes) }
                store.transition(key, ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED, file)
                val result = workers.submit<File?> {
                    if (verifiedOnly) store.verifiedStaged(key) else store.stagedFile(key)
                }
                assertTrue(enteredRead.await(2, TimeUnit.SECONDS))
                assertEquals(id, workers.submit<ActiveLineage?> { store.active() }.get(2, TimeUnit.SECONDS)?.id)
                workers.submit { store.cancel(id) }.get(2, TimeUnit.SECONDS)
                assertNull(store.active())
                // Validation is deliberately still paused here: cancellation did not wait for it.
                assertEquals(1L, releaseRead.count)
                releaseRead.countDown()
                assertNull(result.get(2, TimeUnit.SECONDS))
            } finally {
                releaseRead.countDown()
                workers.shutdownNow()
                workers.awaitTermination(2, TimeUnit.SECONDS)
                store.close()
            }
        }
    }

    @Test fun `paused preparation cannot commit after cancellation or a new move`() {
        for (replaceMove in listOf(false, true)) {
            val enteredRead = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val pauseOnce = AtomicBoolean(true)
            val workers = Executors.newFixedThreadPool(2)
            val store = LineageRepository(tmp.newFolder(), { now }, beforeStagedRead = {
                if (pauseOnce.compareAndSet(true, false)) {
                    enteredRead.countDown()
                    check(releaseRead.await(10, TimeUnit.SECONDS))
                }
            })
            try {
                val id = store.startNewMove().id
                val manifest = TransferManifest("phone", listOf(meta()), bytes.size.toLong(), id)
                store.stagingDir.mkdirs()
                val file = File(store.stagingDir, "item.bin").apply { writeBytes(bytes) }
                val preparing = workers.submit { store.savePreparedManifest(manifest, mapOf(1 to file)) }
                assertTrue(enteredRead.await(2, TimeUnit.SECONDS))
                val replacement = workers.submit<ActiveLineage?> {
                    if (replaceMove) store.startNewMove() else { store.cancel(id); null }
                }.get(2, TimeUnit.SECONDS)
                assertEquals(1L, releaseRead.count)
                // Keep bytes available after cancellation to ensure the final snapshot fence,
                // rather than a missing-file error, rejects the old preparation result.
                file.writeBytes(bytes)
                releaseRead.countDown()
                val error = assertThrows(ExecutionException::class.java) { preparing.get(2, TimeUnit.SECONDS) }
                assertTrue(error.cause is IllegalStateException)
                assertEquals(replacement?.id, store.active()?.id)
                assertNull(store.active()?.manifest)
                assertFalse(store.active()?.prepared ?: false)
            } finally {
                releaseRead.countDown()
                workers.shutdownNow()
                workers.awaitTermination(2, TimeUnit.SECONDS)
                store.close()
            }
        }
    }

    @Test fun `any intervening snapshot commit invalidates staged validation without blocking it`() {
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val workers = Executors.newSingleThreadExecutor()
        val store = LineageRepository(tmp.newFolder(), { now }, beforeStagedRead = {
            if (pauseOnce.compareAndSet(true, false)) {
                enteredRead.countDown()
                check(releaseRead.await(10, TimeUnit.SECONDS))
            }
        })
        try {
            val id = store.startNewMove().id
            store.establishResumeCredential(id)
            store.manifest(meta())
            val key = CheckpointKey.from(id, meta())
            store.stagingDir.mkdirs()
            val file = File(store.stagingDir, "item.bin").apply { writeBytes(bytes) }
            store.transition(key, ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED, file)
            val validating = workers.submit<File?> { store.verifiedStaged(key) }
            assertTrue(enteredRead.await(2, TimeUnit.SECONDS))
            store.authenticated(id)
            releaseRead.countDown()
            assertNull(validating.get(2, TimeUnit.SECONDS))
            assertEquals(ReceiptPhase.RECEIVED_VERIFIED, store.checkpoint(key)?.phase)
        } finally {
            releaseRead.countDown()
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
            store.close()
        }
    }

    @Test fun `verified staging cancellation callback runs after a paused read resumes`() {
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val workers = Executors.newSingleThreadExecutor()
        val store = LineageRepository(tmp.newFolder(), { now }, beforeStagedRead = {
            enteredRead.countDown()
            check(releaseRead.await(10, TimeUnit.SECONDS))
        })
        try {
            val id = store.startNewMove().id
            store.manifest(meta())
            val key = CheckpointKey.from(id, meta())
            store.stagingDir.mkdirs()
            val file = File(store.stagingDir, "item.bin").apply { writeBytes(bytes) }
            store.transition(key, ReceiptPhase.PREPARED, ReceiptPhase.RECEIVED_VERIFIED, file)
            val validating = workers.submit<File?> {
                store.verifiedStaged(key) { if (cancelled.get()) throw SimulatedCrash() }
            }
            assertTrue(enteredRead.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            releaseRead.countDown()
            val error = assertThrows(ExecutionException::class.java) { validating.get(2, TimeUnit.SECONDS) }
            assertTrue(error.cause is SimulatedCrash)
            assertEquals(ReceiptPhase.RECEIVED_VERIFIED, store.checkpoint(key)?.phase)
        } finally {
            releaseRead.countDown()
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
            store.close()
        }
    }

    @Test fun `caller manifest mutations cannot bypass the immutable snapshot fence`() {
        LineageRepository(tmp.newFolder(), { now }).use { store ->
            val id = store.startNewMove().id
            val items = mutableListOf(meta(1), meta(2))
            store.saveManifest(TransferManifest("phone", items, bytes.size * 2L, id))
            items.clear()
            assertEquals(2, store.active()?.manifest?.items?.size)
            (checkNotNull(store.active()?.manifest).items as MutableList<*>).clear()
            assertEquals(2, store.active()?.manifest?.items?.size)
        }
    }

    private class SimulatedCrash : RuntimeException("simulated process interruption")
}
