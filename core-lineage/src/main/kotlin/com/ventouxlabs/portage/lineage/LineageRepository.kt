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
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
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

@Serializable
enum class CredentialState { NONE, PENDING, ESTABLISHED }

data class ActiveLineage(
    val id: String,
    val lastAuthenticatedAtMillis: Long,
    val manifest: TransferManifest?,
    val credentialState: CredentialState = CredentialState.NONE,
    /** True only when a complete sender manifest and all staging identities committed together. */
    val prepared: Boolean = false,
)

/** Deliberately has no original cause: serializer failures can embed plaintext snapshot secrets. */
class SnapshotLoadException : IllegalStateException("Saved move state could not be loaded. Reset local move data before starting a new move.")

/** Temporary ownership contention; retry after the previous repository/session closes. */
class LineageBusyException : IllegalStateException("Saved move state is still in use. Try again after the previous session closes.")

@Serializable
data class Tombstone(val lineageId: String, val atMillis: Long, val reason: String, val peerDeleted: Boolean = false)

@Serializable
private data class ActiveRecord(
    val id: String,
    val lastAuthenticatedAtMillis: Long,
    val credentialHex: String?,
    val credentialState: CredentialState,
    val prepared: Boolean,
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
    // Crash-test seams bracket the atomic rename. Production leaves both inert.
    private val beforeSnapshotReplace: () -> Unit = {},
    private val afterSnapshotReplace: () -> Unit = {},
    // Validation-test seam; invoked without the repository monitor before each staged read.
    private val beforeStagedRead: () -> Unit = {},
) : AutoCloseable {
    val stagingDir = File(directory, "staging")
    private val snapshotFile = File(directory, "lineage.json")
    private val pendingFile = File(directory, "lineage.pending")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val ownershipPath: String
    private val lockChannel: FileChannel
    private val writerLock: java.nio.channels.FileLock
    private var state: Snapshot
    private var closed = false

    init {
        ownershipPath = try { directory.canonicalPath } catch (_: Exception) { throw SnapshotLoadException() }
        synchronized(ownedDirectories) {
            if (!ownedDirectories.add(ownershipPath)) throw LineageBusyException()
        }
        lockChannel = try {
            require(directory.mkdirs() || directory.isDirectory)
            FileChannel.open(File(directory, "writer.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (_: Exception) {
            releaseProcessOwnership()
            throw SnapshotLoadException()
        }
        try {
            writerLock = try {
                lockChannel.tryLock() ?: throw LineageBusyException()
            } catch (_: OverlappingFileLockException) {
                throw LineageBusyException()
            }
            state = loadSnapshot()
            // A pre-rename crash may leave sensitive pending bytes, but cannot commit them.
            if (pendingFile.exists()) check(pendingFile.delete()) { "cannot remove uncommitted snapshot" }
            purgeExpired()
            reconcileInterruptedAtStartup()
        } catch (t: Throwable) {
            runCatching { lockChannel.close() }
            releaseProcessOwnership()
            if (t is LineageBusyException) throw t
            if (t is Exception) throw SnapshotLoadException()
            throw t
        }
    }

    @Synchronized fun active(): ActiveLineage? {
        purgeExpired()
        return state.active?.let {
            ActiveLineage(it.id, it.lastAuthenticatedAtMillis, it.manifest?.let(::copyManifest), it.credentialState, it.prepared)
        }
    }

    /** Called only for the user's explicit Start a new move action. Exactly 16 random bytes. */
    @Synchronized fun startNewMove(): ActiveLineage {
        purgeExpired()
        state.active?.let { terminate(it.id, "new_move") }
        val id = randomBytes(16).toHex()
        check(state.tombstones.none { it.lineageId == id }) { "random lineage collision" }
        commit(state.copy(active = ActiveRecord(id, nowMillis(), null, CredentialState.NONE, false), checkpoints = emptyList()))
        return checkNotNull(active())
    }

    @Synchronized fun newOccurrenceId(): String {
        requireActive()
        val id = randomBytes(16).toHex()
        check(state.checkpoints.none { it.key.occurrenceId == id }) { "random occurrence collision" }
        return id
    }

    /** Prepare once, inside the initial authenticated channel; pending retries reuse these bytes. */
    @Synchronized fun establishResumeCredential(lineageId: String): ByteArray {
        val active = requireActive(lineageId)
        require(active.credentialState != CredentialState.ESTABLISHED) { "resume credential is already established" }
        if (active.credentialState == CredentialState.PENDING) return checkNotNull(active.credentialHex).fromHex()
        val secret = randomBytes(32)
        try {
            commit(state.copy(active = active.copy(credentialHex = secret.toHex(), credentialState = CredentialState.PENDING)))
        } catch (t: Throwable) {
            secret.fill(0)
            throw t
        }
        return secret
    }

    /** Borrow a copy for an authenticated NEW bootstrap resend; never creates a replacement. */
    @Synchronized fun pendingBootstrapCredential(lineageId: String): ByteArray {
        val active = requireActive(lineageId)
        require(active.credentialState == CredentialState.PENDING) { "no pending bootstrap credential" }
        return checkNotNull(active.credentialHex).fromHex()
    }

    /** Sender calls this only after validating the matching authenticated LINEAGE_ACK. */
    @Synchronized fun confirmResumeCredential(lineageId: String) {
        val active = requireActive(lineageId)
        require(active.credentialState != CredentialState.NONE) { "no pending credential to confirm" }
        if (active.credentialState == CredentialState.ESTABLISHED) return
        commit(state.copy(active = active.copy(credentialState = CredentialState.ESTABLISHED)))
    }

    /** Initial receiver adoption. Existing active state cannot be replaced by a scanned QR. */
    @Synchronized fun acceptInitial(lineageId: String, credential: ByteArray) {
        ManifestValidation.requireIdentity(lineageId)
        require(credential.size == 32) { "resume credential must be 32 bytes" }
        purgeExpired()
        require(state.tombstones.none { it.lineageId == lineageId }) { "lineage is revoked" }
        val current = state.active
        if (current != null) {
            require(current.id == lineageId && current.manifest == null && current.credentialState == CredentialState.ESTABLISHED) {
                "an active move already exists; explicitly start a new move first"
            }
            val existing = checkNotNull(current.credentialHex).fromHex()
            try {
                require(MessageDigest.isEqual(existing, credential)) { "bootstrap credential mismatch" }
            } finally { existing.fill(0) }
            return
        }
        // The receiver already observed the secret held by the authenticated sender. Persist
        // ESTABLISHED before ACK so a receiver crash immediately after sending it can resume.
        commit(state.copy(active = ActiveRecord(lineageId, nowMillis(), credential.toHex(), CredentialState.ESTABLISHED, false), checkpoints = emptyList()))
    }

    @Synchronized fun credentialForResume(): ByteArray {
        val active = requireActive()
        check(active.credentialState == CredentialState.ESTABLISHED) { "resume credential has not been acknowledged" }
        return checkNotNull(active.credentialHex) { "resume credential unavailable; start a new move" }.fromHex()
    }

    @Synchronized fun authenticated(lineageId: String) {
        val active = requireActive(lineageId)
        require(active.credentialHex != null) { "lineage has no authenticated credential" }
        commit(state.copy(active = active.copy(lastAuthenticatedAtMillis = nowMillis())))
    }

    @Synchronized fun saveManifest(manifest: TransferManifest) {
        val requested = copyManifest(manifest)
        ManifestValidation.requireValid(requested)
        val active = requireActive(requested.lineageId)
        require(active.manifest == null || active.manifest == requested) { "resumed manifest changed occurrence identity" }
        if (active.manifest == requested) return
        commit(state.copy(
            active = active.copy(manifest = requested),
            checkpoints = requested.items.map { Checkpoint(CheckpointKey.from(requested.lineageId, it)) },
        ))
    }

    /**
     * Publish sender preparation in one transaction. No manifest is visible until every
     * owned staged file has been validated and its filename is present in the same snapshot.
     * A callback can cancel hashing before any commit; callers own/clean their staging files.
     */
    fun savePreparedManifest(
        manifest: TransferManifest,
        stagedFiles: Map<Int, File>,
        checkCancelled: () -> Unit = {},
    ) {
        // Copy caller collections before releasing the monitor so the validated input itself
        // cannot change while file I/O is in progress.
        val requested = copyManifest(manifest)
        val files = stagedFiles.toMap()
        val observed = synchronized(this) {
            ManifestValidation.requireValid(requested)
            val active = requireActive(requested.lineageId)
            require(active.manifest == null || active.manifest == requested) { "prepared manifest changed occurrence identity" }
            require(files.keys == requested.items.map { it.itemId }.toSet()) { "prepared staging must cover the exact manifest" }
            state
        }
        val prepared = requested.items.map { meta ->
            checkCancelled()
            val file = files.getValue(meta.itemId)
            val name = stagedName(file)
            val key = CheckpointKey.from(requested.lineageId, meta)
            require(matchesStagedBytes(file, key, checkCancelled)) { "prepared bytes do not match the manifest" }
            checkCancelled()
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            Checkpoint(key, stagedName = name)
        }
        checkCancelled()
        if (prepared.isNotEmpty()) FileChannel.open(stagingDir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        checkCancelled()
        synchronized(this) {
            check(!closed && state === observed && state.active?.id == requested.lineageId) { "saved move changed during preparation" }
            val active = requireActive(requested.lineageId)
            check(state === observed && elapsed(active.lastAuthenticatedAtMillis) < STAGING_TTL_MS) { "saved move expired during preparation" }
            if (active.prepared) {
                require(state.checkpoints.map { it.key to it.stagedName }.toSet() == prepared.map { it.key to it.stagedName }.toSet()) {
                    "prepared staging identity changed"
                }
                return
            }
            require(state.checkpoints.all { it.phase == ReceiptPhase.PREPARED }) { "cannot replace in-progress checkpoints" }
            // Only this bounded snapshot transaction holds the writer monitor; all payload
            // hashing/fsync above occurs outside it and cannot delay cancel or active().
            commit(state.copy(active = active.copy(manifest = requested, prepared = true), checkpoints = prepared))
        }
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
    fun stagedFile(key: CheckpointKey, checkCancelled: () -> Unit = {}): File? =
        validateStagedFile(key, requireVerified = false, checkCancelled)

    fun verifiedStaged(key: CheckpointKey, checkCancelled: () -> Unit = {}): File? =
        validateStagedFile(key, requireVerified = true, checkCancelled)

    private fun validateStagedFile(key: CheckpointKey, requireVerified: Boolean, checkCancelled: () -> Unit): File? {
        val (observed, file) = synchronized(this) {
            val record = checkpoint(key) ?: return null
            if (requireVerified && (record.phase == ReceiptPhase.PREPARED || record.phase == ReceiptPhase.FAILED)) return null
            val active = requireActive(key.lineageId)
            if (elapsed(active.lastAuthenticatedAtMillis) >= STAGING_TTL_MS) return null
            val file = record.stagedName?.let { File(stagingDir, it) } ?: return null
            state to file
        }
        require(file.canonicalFile.parentFile == stagingDir.canonicalFile) { "invalid staged path" }
        val valid = matchesStagedBytes(file, key, checkCancelled)
        checkCancelled()
        return synchronized(this) {
            // No stale validation result survives any intervening state commit or owner close.
            if (closed || state !== observed || state.active?.id != key.lineageId) return null
            purgeExpired()
            if (state !== observed || state.active?.id != key.lineageId ||
                elapsed(checkNotNull(state.active).lastAuthenticatedAtMillis) >= STAGING_TTL_MS) return null
            file.takeIf { valid }
        }
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

    /** A stale session's teardown must not mutate the current move, including its expiry. */
    @Synchronized fun reconcileInterrupted(lineageId: String): Boolean {
        ManifestValidation.requireIdentity(lineageId)
        check(!closed) { "lineage repository closed" }
        if (state.active?.id != lineageId) return false
        purgeExpired()
        if (state.active?.id != lineageId) return false
        val revised = state.checkpoints.map {
            if (it.key.lineageId == lineageId && it.phase == ReceiptPhase.APPLYING)
                it.copy(phase = ReceiptPhase.UNKNOWN_INTERRUPTED, detail = "apply interrupted; durable outcome unproven") else it
        }
        val changed = revised != state.checkpoints
        if (changed) commit(state.copy(checkpoints = revised))
        return changed
    }

    private fun reconcileInterruptedAtStartup() {
        state.active?.id?.let(::reconcileInterrupted)
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            try { writerLock.release() } finally {
                try { lockChannel.close() } finally { releaseProcessOwnership() }
            }
        }
    }

    private fun releaseProcessOwnership() {
        synchronized(ownedDirectories) { ownedDirectories.remove(ownershipPath) }
    }

    private fun elapsed(since: Long): Long = (nowMillis() - since).coerceAtLeast(0)
    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    private fun copyManifest(manifest: TransferManifest): TransferManifest = manifest.copy(items = manifest.items.toList())

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

    private fun matchesStagedBytes(file: File, key: CheckpointKey, checkCancelled: () -> Unit): Boolean {
        checkCancelled()
        if (!file.isFile || file.length() != key.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                try {
                    while (true) {
                        checkCancelled()
                        beforeStagedRead()
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                } finally { buffer.fill(0) }
            }
        } catch (_: IOException) {
            // A concurrent cancel may unlink the file before open/read. Unavailable bytes
            // cannot authorize resume; the final snapshot fence still protects all returns.
            return false
        }
        return digest.digest().toHex() == key.sha256
    }

    private fun loadSnapshot(): Snapshot = try {
        if (snapshotFile.exists()) {
            require(snapshotFile.length() <= MAX_SNAPSHOT_BYTES)
            json.decodeFromString<Snapshot>(snapshotFile.readText()).also(::validateSnapshot)
        } else Snapshot(SNAPSHOT_VERSION, null, emptyList(), emptyList())
    } catch (_: Exception) {
        throw SnapshotLoadException()
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
            beforeSnapshotReplace()
            Files.move(pendingFile.toPath(), snapshotFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            state = next
            afterSnapshotReplace()
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { bytes.fill(0) }
    }

    private fun validateSnapshot(snapshot: Snapshot) {
        require(snapshot.version == SNAPSHOT_VERSION) { "unsupported lineage snapshot version" }
        require(snapshot.checkpoints.size <= ManifestValidation.MAX_ITEMS && snapshot.tombstones.size <= MAX_TOMBSTONES)
        val active = snapshot.active
        if (active == null) require(snapshot.checkpoints.isEmpty()) { "orphan checkpoints" }
        active?.let {
            ManifestValidation.requireIdentity(it.id)
            require(it.lastAuthenticatedAtMillis >= 0)
            require(it.credentialHex == null || it.credentialHex.matches(Regex("[0-9a-f]{64}"))) { "invalid stored credential" }
            require((it.credentialState == CredentialState.NONE) == (it.credentialHex == null)) { "credential state disagrees with saved secret" }
            it.manifest?.let { m -> ManifestValidation.requireValid(m); require(m.lineageId == it.id) }
            if (it.prepared) {
                val manifest = checkNotNull(it.manifest) { "prepared lineage has no manifest" }
                require(snapshot.checkpoints.map { c -> c.key }.toSet() == manifest.items.map { item -> CheckpointKey.from(it.id, item) }.toSet()) {
                    "prepared manifest has incomplete checkpoints"
                }
                require(snapshot.checkpoints.all { c -> c.stagedName != null }) { "prepared manifest has incomplete staging metadata" }
            }
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
        // Do not open/close a second descriptor for a file already locked in this JVM:
        // some platforms release all process locks when any descriptor for that file closes.
        // The OS lock separately fences writers in other processes.
        private val ownedDirectories = mutableSetOf<String>()
        private const val SNAPSHOT_VERSION = 2
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
