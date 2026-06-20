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
import cc.grepon.portage.model.ItemResult
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.ProtocolMessage
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.apk.ApkContainerValidation
import cc.grepon.portage.transport.SecureChannel
import cc.grepon.portage.transport.TransportException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Receiver side of the per-item stream (PROTOCOL.md §4-5): for each ITEM_BEGIN, stage the
 * DATA chunks to a generated cacheDir file with an incremental sha256, verify against BOTH
 * the wire's ITEM_END hash and the manifest's advertised hash/size, ack receipt, then run
 * the apply callback — apply results ride BATCH_ACK, never ITEM_ACK.
 *
 * The receiver enforces its OWN limits regardless of manifest claims (§5): per-item byte
 * cap, size/kind agreement with the manifest, monotonic chunk sequence. A failing item is
 * drained (to stay frame-synchronized) and reported per-item — it NEVER aborts the batch.
 * Only a dead channel or a protocol-order violation throws [TransportException].
 *
 * Staged files hold personal data: every payload is deleted after its apply, and the whole
 * staging dir is swept in a finally — partials never survive the session.
 */
class ItemStreamReceiver(
    private val stagingDir: File,
    private val maxItemBytes: Long = DEFAULT_MAX_ITEM_BYTES,
    // Per-kind cap OVERRIDES, applied by ItemKind. The default 64 MiB ceiling fits structured Tier-0
    // payloads, but an opaque app-backup relay item (PRP-06) routinely exceeds it, so the relay kind
    // ALONE gets a raised, still-finite ceiling here. Every kind NOT in this map keeps [maxItemBytes]
    // — the raised relay cap MUST NOT leak into the Tier-0/PII item paths (PRP-06 §5).
    private val maxBytesByKind: Map<ItemKind, Long> = emptyMap(),
    // Usable-space probe for the staging volume, seam-injected so tests can simulate a near-full disk
    // deterministically (ADR-006 AC-16). Production reads the real free space; the gate is APK-only.
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {

    /** The effective per-item byte cap for [kind]: its override if any, else the default. */
    private fun capFor(kind: ItemKind): Long = maxBytesByKind[kind] ?: maxItemBytes

    sealed interface Event {
        data class ItemStarted(val itemId: Int) : Event
        data class ItemProgressed(val itemId: Int, val bytesReceived: Long, val totalBytes: Long) : Event
        data class ItemApplying(val itemId: Int) : Event
        data class ItemFinished(val result: ItemResult) : Event
    }

    suspend fun run(
        channel: SecureChannel,
        expected: Map<Int, ItemMeta>,
        apply: suspend (ItemMeta, InputStream) -> ApplyOutcome,
        onEvent: (Event) -> Unit,
    ): List<ItemResult> {
        stagingDir.mkdirs()
        val results = linkedMapOf<Int, ItemResult>()
        // PROTOCOL.md §5: the receiver enforces a max item count regardless of manifest
        // claims — the selected set is known, so anything much past it is abuse, and this
        // closes the one otherwise-unbounded loop (security review 2026-06-11, MEDIUM).
        val maxItems = expected.size + UNREQUESTED_ITEM_SLACK
        var begun = 0
        // Running sum of ACCEPTED APK-item declared sizes, bounded by MAX_APK_TOTAL_BYTES (ADR-006
        // D4 / AC-17). APK-only: no other kind is aggregate-bounded. An item is added only after it
        // clears every up-front gate, so a rejected item never consumes budget.
        val apkBudget = ApkAggregateBudget()
        try {
            stream@ while (true) {
                val message = receiveSkippingPing(channel)
                    ?: throw TransportException("connection lost mid-transfer")
                when (message) {
                    is ProtocolMessage.ItemBegin -> {
                        if (++begun > maxItems) {
                            throw TransportException("sender exceeded the item-count cap")
                        }
                        val result = receiveOneItem(channel, message, expected[message.itemId], apkBudget, apply, onEvent)
                        results[message.itemId] = result
                        onEvent(Event.ItemFinished(result))
                    }
                    is ProtocolMessage.BatchEnd -> break@stream
                    else -> throw TransportException(
                        "expected ITEM_BEGIN or BATCH_END, got ${message.javaClass.simpleName}",
                    )
                }
            }

            // Selected items the sender never delivered are reported, not forgotten.
            for ((itemId, _) in expected) {
                if (itemId !in results) {
                    val result = ItemResult(itemId, ItemStatus.SKIPPED, "not delivered by sender")
                    results[itemId] = result
                    onEvent(Event.ItemFinished(result))
                }
            }

            val final = results.values.toList()
            channel.send(ProtocolMessage.BatchAck(final))
            return final
        } finally {
            runCatching { stagingDir.listFiles()?.forEach { it.delete() } }
        }
    }

    private suspend fun receiveOneItem(
        channel: SecureChannel,
        begin: ProtocolMessage.ItemBegin,
        meta: ItemMeta?,
        apkBudget: ApkAggregateBudget,
        apply: suspend (ItemMeta, InputStream) -> ApplyOutcome,
        onEvent: (Event) -> Unit,
    ): ItemResult {
        onEvent(Event.ItemStarted(begin.itemId))

        // The cap is resolved from the manifest-agreed kind (begin.kind is cross-checked against
        // meta.kind first, so a relay raise can't be claimed by mislabeling a PII item).
        val itemCap = capFor(begin.kind)
        val isApk = begin.kind == ItemKind.APK

        // Refuse BEFORE staging a byte; the stream is still drained to stay in sync. The two
        // APK-only gates (aggregate budget, free space) run LAST, after the manifest/kind/size/
        // per-item-cap agreement, so they only ever judge an otherwise-valid APK item — and never
        // touch any non-APK kind (ADR-006 AC-16/AC-17).
        var failure: ItemResult? = when {
            // Floor: a negative declared size clears every numeric guard (cap, aggregate, free-space)
            // and would poison the APK aggregate budget. Reject as OVERSIZE before any other check
            // so downstream gates always operate on non-negative sizes (security review 2026-06-20).
            begin.size < 0L ->
                ItemResult(begin.itemId, ItemStatus.OVERSIZE, "negative declared size")
            meta == null ->
                ItemResult(begin.itemId, ItemStatus.SKIPPED, "not requested")
            begin.kind != meta.kind ->
                ItemResult(begin.itemId, ItemStatus.UNKNOWN_KIND, "kind disagrees with the manifest")
            begin.size != meta.size ->
                ItemResult(begin.itemId, ItemStatus.OVERSIZE, "size disagrees with the manifest")
            begin.size > itemCap ->
                ItemResult(begin.itemId, ItemStatus.OVERSIZE, "exceeds the receiver's per-item cap")
            // AC-17: this APK item's declared size would push the running APK total past the
            // aggregate ceiling. OVERSIZE — it is a size-bound refusal, just at the batch scope.
            isApk && apkBudget.wouldExceed(begin.size) ->
                ItemResult(begin.itemId, ItemStatus.OVERSIZE, "exceeds the aggregate APK byte budget")
            // AC-16: fail CLOSED if the staging volume can't hold the double-stage (cacheDir item
            // file -> split files -> pm session). Not a size-cap breach, so WRITE_ERROR, the kind's
            // existing local-staging failure status. Runs AFTER begin.size == meta.size agreement
            // so hasRoomToStage consults the validated size — the ordering is load-bearing.
            isApk && !hasRoomToStage(begin.size) ->
                ItemResult(begin.itemId, ItemStatus.WRITE_ERROR, "not enough free space to stage this APK")
            else -> null
        }

        // The item cleared every up-front gate — commit its size to the APK aggregate budget so the
        // NEXT APK item is judged against the running total (only accepted items consume budget).
        if (failure == null && isApk) apkBudget.add(begin.size)

        // Generated name — display fields are NEVER paths (THREAT_MODEL, path traversal).
        val file = File(stagingDir, "stage-${begin.itemId}.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        var nextSeq = 0
        var endSha: String? = null

        val sink: OutputStream? = if (failure == null) file.outputStream() else null
        try {
            chunks@ while (true) {
                val message = receiveSkippingPing(channel)
                    ?: throw TransportException("connection lost mid-item")
                when (message) {
                    is ProtocolMessage.ItemData -> {
                        if (failure != null) continue@chunks // drain mode
                        if (message.itemId != begin.itemId || message.seq != nextSeq) {
                            failure = ItemResult(begin.itemId, ItemStatus.WRITE_ERROR, "stream out of order")
                            continue@chunks
                        }
                        received += message.bytes.size
                        // Bound on-disk bytes by BOTH the manifest and the receiver's own
                        // cap, so staging stays bounded even if one guard ever regresses.
                        if ((meta != null && received > meta.size) || received > itemCap) {
                            failure = ItemResult(begin.itemId, ItemStatus.OVERSIZE, "more bytes than advertised")
                            continue@chunks
                        }
                        nextSeq++
                        sink?.write(message.bytes)
                        digest.update(message.bytes)
                        if (meta != null) onEvent(Event.ItemProgressed(begin.itemId, received, meta.size))
                    }
                    is ProtocolMessage.ItemEnd -> {
                        if (message.itemId == begin.itemId) endSha = message.sha256
                        break@chunks
                    }
                    else -> throw TransportException(
                        "expected ITEM_DATA or ITEM_END, got ${message.javaClass.simpleName}",
                    )
                }
            }
        } finally {
            runCatching { sink?.close() }
        }

        val receipt = failure ?: verifyStaged(begin.itemId, meta, digest, endSha, received)
        if (receipt != null) {
            runCatching { file.delete() }
            channel.send(ProtocolMessage.ItemAck(receipt))
            return receipt
        }

        // Receipt verified — ack it, then apply; the apply verdict rides BATCH_ACK (§4).
        channel.send(ProtocolMessage.ItemAck(ItemResult(begin.itemId, ItemStatus.OK)))
        onEvent(Event.ItemApplying(begin.itemId))
        val outcome = try {
            file.inputStream().use { apply(checkNotNull(meta), it) }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            ApplyOutcome(ItemStatus.WRITE_ERROR, t.message ?: "apply failed")
        } finally {
            runCatching { file.delete() }
        }
        return ItemResult(begin.itemId, outcome.status, outcome.detail)
    }

    /** Null = verified; otherwise the receipt failure to ack. */
    private fun verifyStaged(
        itemId: Int,
        meta: ItemMeta?,
        digest: MessageDigest,
        endSha: String?,
        received: Long,
    ): ItemResult? {
        if (meta == null) return ItemResult(itemId, ItemStatus.SKIPPED, "not requested")
        // Plain equality is fine here: these are integrity hashes inside the mutually
        // authenticated AEAD channel — no observer exists for a timing side channel.
        val computed = digest.digest().joinToString("") { "%02x".format(it) }
        return when {
            endSha == null ->
                ItemResult(itemId, ItemStatus.HASH_MISMATCH, "ITEM_END item id mismatch")
            computed == endSha && computed == meta.sha256 && received == meta.size -> null
            else ->
                ItemResult(itemId, ItemStatus.HASH_MISMATCH, "staged bytes do not match the advertised hash")
        }
    }

    /**
     * AC-16 free-space gate: the staging volume must hold the double-stage — the cacheDir item file
     * PLUS the split files the apply session re-materializes from it — so we require usable space of
     * at least `2 * size`. `floor(usable / 2) >= size` is equivalent to `usable >= 2 * size` for
     * even usable, and slightly stricter for odd usable — it never admits when `usable < 2 * size`.
     * Reads the seam-injected [freeSpace] so tests can simulate a near-full disk deterministically.
     */
    private fun hasRoomToStage(size: Long): Boolean {
        val usable = freeSpace(stagingDir)
        return usable / 2 >= size
    }

    private suspend fun receiveSkippingPing(channel: SecureChannel): ProtocolMessage? {
        while (true) {
            val message = channel.receive() ?: return null
            if (message !is ProtocolMessage.Ping) return message
        }
    }

    /**
     * Running APK-only aggregate budget (ADR-006 AC-17). Sums the declared sizes of ACCEPTED APK
     * items in one batch and refuses the item that would carry the total past
     * [ApkContainerValidation.MAX_APK_TOTAL_BYTES]. Single-coroutine by construction — items are
     * processed serially — so plain mutation is safe. APK-scoped: the caller only consults this for
     * [ItemKind.APK], leaving every other kind unbounded in aggregate (as before).
     */
    private class ApkAggregateBudget {
        private var acceptedBytes = 0L

        /**
         * True if adding [size] to the running total would breach the aggregate ceiling.
         * The negative-size check makes the budget poison-proof regardless of gate ordering:
         * a negative [size] always returns true (rejected), never underflows the subtraction.
         */
        fun wouldExceed(size: Long): Boolean =
            size < 0L || size > ApkContainerValidation.MAX_APK_TOTAL_BYTES - acceptedBytes

        /**
         * Commit an ACCEPTED APK item's declared size to the running total.
         * [size] must be non-negative — the caller's negative-floor gate guarantees this, and
         * [require] enforces it defensively so a future gate reorder cannot silently corrupt state.
         */
        fun add(size: Long) {
            require(size >= 0L) { "APK budget add called with negative size: $size" }
            acceptedBytes += size
        }
    }

    private companion object {
        /** Tier-0 items are text; anything past this is not a parity payload. */
        const val DEFAULT_MAX_ITEM_BYTES = 64L * 1024 * 1024

        /** A few unrequested/duplicate items are tolerated (drained + reported), no more. */
        const val UNREQUESTED_ITEM_SLACK = 8
    }
}
