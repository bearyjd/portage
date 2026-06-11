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

import cc.grepon.portage.model.ItemMeta
import cc.grepon.portage.model.ItemResult
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.ProtocolMessage
import cc.grepon.portage.providers.ApplyOutcome
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
) {

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
        try {
            stream@ while (true) {
                val message = receiveSkippingPing(channel)
                    ?: throw TransportException("connection lost mid-transfer")
                when (message) {
                    is ProtocolMessage.ItemBegin -> {
                        if (++begun > maxItems) {
                            throw TransportException("sender exceeded the item-count cap")
                        }
                        val result = receiveOneItem(channel, message, expected[message.itemId], apply, onEvent)
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
        apply: suspend (ItemMeta, InputStream) -> ApplyOutcome,
        onEvent: (Event) -> Unit,
    ): ItemResult {
        onEvent(Event.ItemStarted(begin.itemId))

        // Refuse BEFORE staging a byte; the stream is still drained to stay in sync.
        var failure: ItemResult? = when {
            meta == null ->
                ItemResult(begin.itemId, ItemStatus.SKIPPED, "not requested")
            begin.kind != meta.kind ->
                ItemResult(begin.itemId, ItemStatus.UNKNOWN_KIND, "kind disagrees with the manifest")
            begin.size != meta.size ->
                ItemResult(begin.itemId, ItemStatus.OVERSIZE, "size disagrees with the manifest")
            begin.size > maxItemBytes ->
                ItemResult(begin.itemId, ItemStatus.OVERSIZE, "exceeds the receiver's per-item cap")
            else -> null
        }

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
                        if ((meta != null && received > meta.size) || received > maxItemBytes) {
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

    private suspend fun receiveSkippingPing(channel: SecureChannel): ProtocolMessage? {
        while (true) {
            val message = channel.receive() ?: return null
            if (message !is ProtocolMessage.Ping) return message
        }
    }

    private companion object {
        /** Tier-0 items are text; anything past this is not a parity payload. */
        const val DEFAULT_MAX_ITEM_BYTES = 64L * 1024 * 1024

        /** A few unrequested/duplicate items are tolerated (drained + reported), no more. */
        const val UNREQUESTED_ITEM_SLACK = 8
    }
}
