package com.ventouxlabs.portage.lineage

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.ManifestValidation
import com.ventouxlabs.portage.model.ReceiptPhase
import com.ventouxlabs.portage.model.TransferManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom

/** Only validated, complete occurrence identities can address a checkpoint. */
@Serializable
data class CheckpointKey private constructor(
    val lineageId: String,
    val occurrenceId: String,
    val kind: ItemKind,
    val wireSchemaVersion: Int,
    val size: Long,
    val sha256: String,
) {
    init { validate() }

    fun validate() {
        ManifestValidation.requireIdentity(lineageId)
        ManifestValidation.requireItem(ItemMeta(0, kind, size, sha256, "", "", occurrenceId, wireSchemaVersion))
    }

    companion object {
        fun from(lineageId: String, meta: ItemMeta): CheckpointKey {
            ManifestValidation.requireItem(meta)
            return CheckpointKey(lineageId, meta.occurrenceId, meta.kind, meta.wireSchemaVersion, meta.size, meta.sha256)
        }
    }
}

@Serializable
data class Checkpoint(
    val key: CheckpointKey,
    val phase: ReceiptPhase = ReceiptPhase.PREPARED,
    val stagedName: String? = null,
    val detail: String? = null,
)

data class ActiveLineage(val id: String, val lastAuthenticatedAtMillis: Long, val manifest: TransferManifest?)

@Serializable
data class Tombstone(val lineageId: String, val atMillis: Long, val reason: String, val peerDeleted: Boolean = false)

@Serializable
private data class ActiveRecord(
    val id: String,
    val lastAuthenticatedAtMillis: Long,
    val credentialHex: String?,
    val manifest: TransferManifest? = null,
)

@Serializable
private data class Snapshot(
    val version: Int,
    val active: ActiveRecord?,
    val checkpoints: List<Checkpoint>,
    val tombstones: List<Tombstone>,
)

/**
 * App-private single-writer store. Production must place [directory] under noBackupFilesDir.
 * A lock fences concurrent instances; fsync + atomic replacement commits each complete snapshot.
 * Unknown/corrupt snapshots are errors. A leftover temporary file is never recovery authority.
 */
class LineageRepository(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    val stagingDir = File(directory, "staging")
    private val snapshotFile = File(directory, "lineage.json")
    private val pendingFile = File(directory, "lineage.pending")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val lockChannel: FileChannel
    private val writerLock: java.nio.channels.FileLock
    private var state: Snapshot
    private var closed = false

    init {
        require(directory.mkdirs() || directory.isDirectory) { "cannot create lineage directory" }
        lockChannel = FileChannel.open(File(directory, "writer.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            writerLock = checkNotNull(lockChannel.tryLock()) { "lineage store already has a writer" }
            state = if (snapshotFile.exists()) {
                require(snapshotFile.length() <= MAX_SNAPSHOT_BYTES) { "lineage snapshot exceeds size limit" }
                json.decodeFromString<Snapshot>(snapshotFile.readText()).also(::validateSnapshot)
            } else Snapshot(1, null, emptyList(), emptyList())
            // A pre-rename crash may leave sensitive pending bytes, but cannot commit them.
            if (pendingFile.exists()) check(pendingFile.delete()) { "cannot remove uncommitted snapshot" }
            purgeExpired()
            reconcileInterrupted()
        } catch (t: Throwable) {
            lockChannel.close()
            throw t
        }
    }

    @Synchronized fun active(): ActiveLineage? {
        purgeExpired()
        return state.active?.let { ActiveLineage(it.id, it.lastAuthenticatedAtMillis, it.manifest) }
    }

    /** Called only for the user's explicit Start a new move action. Exactly 16 random bytes. */
    @Synchronized fun startNewMove(): ActiveLineage {
        purgeExpired()
        state.active?.let { terminate(it.id, "new_move") }
        val id = randomBytes(16).toHex()
        check(state.tombstones.none { it.lineageId == id }) { "random lineage collision" }
        commit(state.copy(active = ActiveRecord(id, nowMillis(), null), checkpoints = emptyList()))
        return checkNotNull(active())
    }

    @Synchronized fun newOccurrenceId(): String {
        requireActive()
        val id = randomBytes(16).toHex()
        check(state.checkpoints.none { it.key.occurrenceId == id }) { "random occurrence collision" }
        return id
    }

    /** Establish once, inside the authenticated initial channel. Never replaces a lost secret. */
    @Synchronized fun establishResumeCredential(lineageId: String): ByteArray {
        val active = requireActive(lineageId)
        active.credentialHex?.let { return it.fromHex() }
        val secret = randomBytes(32)
        try {
            commit(state.copy(active = active.copy(credentialHex = secret.toHex())))
        } catch (t: Throwable) {
            secret.fill(0)
            throw t
        }
        return secret
    }

    /** Initial receiver adoption. Existing active state cannot be replaced by a scanned QR. */
    @Synchronized fun acceptInitial(lineageId: String, credential: ByteArray) {
        ManifestValidation.requireIdentity(lineageId)
        require(credential.size == 32) { "resume credential must be 32 bytes" }
        purgeExpired()
        require(state.tombstones.none { it.lineageId == lineageId }) { "lineage is revoked" }
        val current = state.active
        require(current == null) { "an active move already exists; explicitly start a new move first" }
        commit(state.copy(active = ActiveRecord(lineageId, nowMillis(), credential.toHex()), checkpoints = emptyList()))
    }

    @Synchronized fun credentialForResume(): ByteArray =
        checkNotNull(requireActive().credentialHex) { "resume credential unavailable; start a new move" }.fromHex()

    @Synchronized fun authenticated(lineageId: String) {
        val active = requireActive(lineageId)
        require(active.credentialHex != null) { "lineage has no authenticated credential" }
        commit(state.copy(active = active.copy(lastAuthenticatedAtMillis = nowMillis())))
    }

    @Synchronized fun saveManifest(manifest: TransferManifest) {
        ManifestValidation.requireValid(manifest)
        val active = requireActive(manifest.lineageId)
        require(active.manifest == null || active.manifest == manifest) { "resumed manifest changed occurrence identity" }
        if (active.manifest == manifest) return
        commit(state.copy(
            active = active.copy(manifest = manifest),
            checkpoints = manifest.items.map { Checkpoint(CheckpointKey.from(manifest.lineageId, it)) },
        ))
    }

    @Synchronized fun prepare(key: CheckpointKey, stagedFile: File? = null): Checkpoint {
        validateKey(key)
        val old = state.checkpoints.singleOrNull { it.key == key }
        require(old == null || old.phase == ReceiptPhase.PREPARED) { "checkpoint is already past preparation" }
        return put(Checkpoint(key, stagedName = stagedFile?.let(::stagedName) ?: old?.stagedName))
    }

    @Synchronized fun checkpoint(key: CheckpointKey): Checkpoint? {
        validateKey(key)
        return state.checkpoints.singleOrNull { it.key == key }
    }

    @Synchronized fun transition(
        key: CheckpointKey,
        expected: ReceiptPhase,
        next: ReceiptPhase,
        stagedFile: File? = null,
        detail: String? = null,
    ): Checkpoint {
        validateKey(key)
        val old = checkNotNull(state.checkpoints.singleOrNull { it.key == key }) { "unknown checkpoint" }
        require(old.phase == expected) { "checkpoint compare-and-set failed: expected $expected, was ${old.phase}" }
        require(legalTransition(expected, next)) { "illegal checkpoint transition $expected -> $next" }
        return put(old.copy(phase = next, stagedName = stagedFile?.let(::stagedName) ?: old.stagedName, detail = detail))
    }

    /** Exact-key prepared sender bytes can be recovered too. Every read re-hashes the entire file. */
    @Synchronized fun stagedFile(key: CheckpointKey): File? {
        val record = checkpoint(key) ?: return null
        val active = requireActive(key.lineageId)
        if (elapsed(active.lastAuthenticatedAtMillis) >= STAGING_TTL_MS) return null
        val file = record.stagedName?.let { File(stagingDir, it) } ?: return null
        require(file.canonicalFile.parentFile == stagingDir.canonicalFile) { "invalid staged path" }
        if (!file.isFile || file.length() != key.size) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return file.takeIf { digest.digest().toHex() == key.sha256 }
    }

    @Synchronized fun verifiedStaged(key: CheckpointKey): File? {
        val record = checkpoint(key) ?: return null
        if (record.phase == ReceiptPhase.PREPARED || record.phase == ReceiptPhase.FAILED) return null
        return stagedFile(key)
    }

    /** No provider evidence is available in PR 0a, so no APPLIED_DURABLE row skips apply. */
    fun canSkipApply(@Suppress("UNUSED_PARAMETER") key: CheckpointKey): Boolean {
        checkpoint(key)
        return false
    }

    @Synchronized fun finish(lineageId: String) = terminate(lineageId, "finished")
    @Synchronized fun cancel(lineageId: String) = terminate(lineageId, "cancelled")

    @Synchronized fun markPeerDeleted(lineageId: String) {
        ManifestValidation.requireIdentity(lineageId)
        check(state.tombstones.any { it.lineageId == lineageId }) { "local revocation must precede peer acknowledgement" }
        commit(state.copy(tombstones = state.tombstones.map { if (it.lineageId == lineageId) it.copy(peerDeleted = true) else it }))
    }

    @Synchronized fun tombstones(): List<Tombstone> = state.tombstones.toList()

    @Synchronized fun purgeExpired() {
        check(!closed) { "lineage repository closed" }
        val active = state.active ?: run { deleteStaging(); return }
        if (elapsed(active.lastAuthenticatedAtMillis) >= LINEAGE_TTL_MS) {
            terminate(active.id, "expired")
        } else if (elapsed(active.lastAuthenticatedAtMillis) >= STAGING_TTL_MS) {
            deleteStaging()
        }
    }

    @Synchronized fun reconcileInterrupted() {
        val revised = state.checkpoints.map {
            if (it.phase == ReceiptPhase.APPLYING) it.copy(phase = ReceiptPhase.UNKNOWN_INTERRUPTED, detail = "apply interrupted; durable outcome unproven") else it
        }
        if (revised != state.checkpoints) commit(state.copy(checkpoints = revised))
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; writerLock.release(); lockChannel.close() }
    }

    private fun elapsed(since: Long): Long = (nowMillis() - since).coerceAtLeast(0)
    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun requireActive(lineageId: String? = null): ActiveRecord {
        lineageId?.let(ManifestValidation::requireIdentity)
        purgeExpired()
        val active = checkNotNull(state.active) { "no active lineage" }
        require(lineageId == null || active.id == lineageId) { "cross-lineage access rejected" }
        return active
    }

    private fun validateKey(key: CheckpointKey) {
        key.validate()
        val active = requireActive(key.lineageId)
        active.manifest?.let { manifest ->
            require(manifest.items.any { CheckpointKey.from(active.id, it) == key }) { "checkpoint not in authenticated manifest" }
        }
    }

    private fun stagedName(file: File): String {
        require(file.canonicalFile.parentFile == stagingDir.canonicalFile) { "staging must be owned by this lineage store" }
        return file.name.also { require(it.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "invalid staged name" } }
    }

    private fun put(checkpoint: Checkpoint): Checkpoint {
        require(state.checkpoints.none { it.key.occurrenceId == checkpoint.key.occurrenceId && it.key != checkpoint.key }) { "occurrence identity changed" }
        commit(state.copy(checkpoints = state.checkpoints.filterNot { it.key == checkpoint.key } + checkpoint))
        return checkpoint
    }

    private fun terminate(lineageId: String, reason: String) {
        ManifestValidation.requireIdentity(lineageId)
        if (state.tombstones.any { it.lineageId == lineageId }) {
            if (state.active == null) deleteStaging()
            return
        }
        require(state.active?.id == lineageId) { "cannot revoke another lineage" }
        // Commit revocation first. No acknowledgement or deletion happens on failed commit.
        commit(state.copy(active = null, checkpoints = emptyList(), tombstones =
            (state.tombstones + Tombstone(lineageId, nowMillis(), reason)).takeLast(MAX_TOMBSTONES)))
        deleteStaging()
    }

    private fun deleteStaging() {
        stagingDir.listFiles()?.forEach { check(it.deleteRecursively()) { "cannot delete expired staged bytes" } }
    }

    private fun commit(next: Snapshot) {
        check(!closed) { "lineage repository closed" }
        validateSnapshot(next)
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= MAX_SNAPSHOT_BYTES) { "lineage snapshot exceeds size limit" }
            FileOutputStream(pendingFile).use { it.write(bytes); it.fd.sync() }
            Files.move(pendingFile.toPath(), snapshotFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            state = next
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { bytes.fill(0) }
    }

    private fun validateSnapshot(snapshot: Snapshot) {
        require(snapshot.version == 1) { "unsupported lineage snapshot version" }
        require(snapshot.checkpoints.size <= ManifestValidation.MAX_ITEMS && snapshot.tombstones.size <= MAX_TOMBSTONES)
        val active = snapshot.active
        if (active == null) require(snapshot.checkpoints.isEmpty()) { "orphan checkpoints" }
        active?.let {
            ManifestValidation.requireIdentity(it.id)
            require(it.lastAuthenticatedAtMillis >= 0)
            require(it.credentialHex == null || it.credentialHex.matches(Regex("[0-9a-f]{64}"))) { "invalid stored credential" }
            it.manifest?.let { m -> ManifestValidation.requireValid(m); require(m.lineageId == it.id) }
        }
        snapshot.tombstones.forEach { ManifestValidation.requireIdentity(it.lineageId) }
        require(snapshot.tombstones.none { it.lineageId == active?.id }) { "revoked active lineage" }
        require(snapshot.checkpoints.map { it.key.occurrenceId }.toSet().size == snapshot.checkpoints.size) { "duplicate checkpoint occurrence" }
        snapshot.checkpoints.forEach {
            it.key.validate()
            require(it.key.lineageId == active?.id) { "cross-lineage checkpoint" }
            require(it.stagedName == null || it.stagedName.matches(Regex("[A-Za-z0-9._-]{1,160}")) && it.stagedName != "." && it.stagedName != "..")
            active?.manifest?.let { m -> require(m.items.any { item -> CheckpointKey.from(active.id, item) == it.key }) }
        }
    }

    companion object {
        const val LINEAGE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val STAGING_TTL_MS = 24L * 60 * 60 * 1000
        private const val MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
        private const val MAX_TOMBSTONES = 1000

        fun legalTransition(from: ReceiptPhase, to: ReceiptPhase): Boolean = when (from) {
            ReceiptPhase.PREPARED -> to == ReceiptPhase.RECEIVED_VERIFIED || to == ReceiptPhase.FAILED
            ReceiptPhase.RECEIVED_VERIFIED -> to == ReceiptPhase.APPLYING || to == ReceiptPhase.UNKNOWN_INTERRUPTED || to == ReceiptPhase.FAILED
            ReceiptPhase.APPLYING -> to == ReceiptPhase.APPLIED_DURABLE || to == ReceiptPhase.FAILED || to == ReceiptPhase.UNKNOWN_INTERRUPTED
            ReceiptPhase.APPLIED_DURABLE, ReceiptPhase.FAILED, ReceiptPhase.UNKNOWN_INTERRUPTED -> to == ReceiptPhase.PREPARED
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
