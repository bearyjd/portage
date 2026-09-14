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
}
