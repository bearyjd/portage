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

import com.ventouxlabs.portage.model.ItemResult
import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.ManifestValidation
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.model.ReceiptPhase
import com.ventouxlabs.portage.transport.SecureChannel
import com.ventouxlabs.portage.transport.TransportException
import kotlinx.coroutines.CancellationException
import java.io.IOException

class TransferCancelledException(val peerDeletionConfirmed: Boolean) : Exception("Move cancelled")

/**
 * Sender's authenticated lineage bootstrap, manifest selection, and phased receipt exchange.
 * Per-item failures do not abort the batch. Interruption after selection produces unknown
 * outcomes; bootstrap and selection violations fail before payload streaming.
 */
class TransferEngine(private val chunkSize: Int = DEFAULT_CHUNK_BYTES) {

    sealed interface Event {
        data class SelectReceived(val want: List<Int>) : Event
        data class ItemStarted(val itemId: Int) : Event
        data class ItemProgressed(val itemId: Int, val bytesSent: Long, val totalBytes: Long) : Event
        data class ItemAcked(val result: ItemResult) : Event
    }

    /**
     * Drive one whole transfer over an already-handshaken [channel]. Returns the final
     * per-item final results. Receipt acknowledgements never prove application; a missing
     * or invalid final acknowledgement leaves every selected occurrence unknown.
     */
    suspend fun run(
        channel: SecureChannel,
        staged: StagedManifest,
        lineageMessage: ProtocolMessage,
        onLineageAcknowledged: () -> Unit = {},
        isCancellationRequested: () -> Boolean = { false },
        isCancelAlreadySent: () -> Boolean = { false },
        onAwaitingReply: (Boolean) -> Unit = {},
        onPeerCancel: () -> Unit = {},
        onEvent: (Event) -> Unit,
    ): List<ItemResult> {
        ManifestValidation.requireValid(staged.manifest)
        val lineageId = staged.manifest.lineageId
        require(when (lineageMessage) {
            is ProtocolMessage.LineageInit -> lineageMessage.lineageId == lineageId &&
                lineageMessage.resumeCredential.size == 32
            is ProtocolMessage.LineageResume -> lineageMessage.lineageId == lineageId
            else -> false
        }) { "invalid lineage bootstrap" }
        suspend fun receive(): ProtocolMessage? {
            val message = try {
                onAwaitingReply(true)
                receiveSkippingPing(channel)
            } finally {
                onAwaitingReply(false)
            }
            if (message is ProtocolMessage.CancelAck && isCancellationRequested()) {
                throw TransferCancelledException(message.lineageId == lineageId)
            }
            if (message is ProtocolMessage.Cancel) {
                if (message.lineageId != lineageId) throw TransportException("cancel lineage mismatch")
                onPeerCancel()
                channel.send(ProtocolMessage.CancelAck(lineageId))
                throw TransferCancelledException(true)
            }
            return message
        }
        suspend fun checkCancelled() {
            if (!isCancellationRequested()) return
            if (!isCancelAlreadySent()) channel.send(ProtocolMessage.Cancel(lineageId))
            // Earlier receipts can already be in flight; only the matching authenticated
            // CANCEL_ACK confirms peer deletion. The caller bounds this exchange.
            while (true) {
                when (val message = receive()) {
                    is ProtocolMessage.CancelAck -> {
                        if (message.lineageId != lineageId) throw TransferCancelledException(false)
                        throw TransferCancelledException(true)
                    }
                    is ProtocolMessage.ItemAck, is ProtocolMessage.BatchAck -> continue
                    else -> throw TransferCancelledException(false)
                }
            }
        }
        val helloMsg = receive()
        if (helloMsg !is ProtocolMessage.Hello) {
            throw TransportException("expected HELLO, got ${helloMsg?.javaClass?.simpleName ?: "end of stream"}")
        }

        if (isCancelAlreadySent()) checkCancelled()
        channel.send(lineageMessage)
        val lineageAck = receive()
        if (lineageAck !is ProtocolMessage.LineageAck || lineageAck.lineageId != lineageId) {
            throw TransportException("expected matching LINEAGE_ACK")
        }
        checkCancelled()
        onLineageAcknowledged()
        channel.send(ProtocolMessage.Manifest(staged.manifest))

        val select = receive()
        if (select !is ProtocolMessage.Select) {
            throw TransportException("expected SELECT, got ${select?.javaClass?.simpleName ?: "end of stream"}")
        }
        val byId = staged.items.associateBy { it.meta.itemId }
        if (select.want.toSet().size != select.want.size || select.want.any { it !in byId }) {
            throw TransportException("SELECT contains duplicate or unknown items")
        }
        if (select.resume.map { it.itemId }.toSet().size != select.resume.size ||
            select.resume.any { it.itemId !in select.want ||
                it.offset != byId.getValue(it.itemId).meta.size }
        ) throw TransportException("invalid resume points")
        val complete = select.resume.filter { it.offset == byId.getValue(it.itemId).meta.size }
            .map { it.itemId }.toSet()
        if (complete.isNotEmpty() && lineageMessage !is ProtocolMessage.LineageResume) {
            throw TransportException("verified resume requires the existing lineage credential")
        }
        onEvent(Event.SelectReceived(select.want))

        val sentIds = mutableListOf<Int>()
        val receipts = mutableMapOf<Int, ItemResult>()
        val selected = staged.items.filter { it.meta.itemId in select.want }
        try {
            for (item in selected) {
                checkCancelled()
                sendItem(channel, item, item.meta.itemId in complete, ::checkCancelled, onEvent)
                sentIds += item.meta.itemId

                val ackMsg = receive()
                if (ackMsg !is ProtocolMessage.ItemAck || !validReceipt(ackMsg.result, item.meta, final = false)) {
                    throw TransportException("expected matching receipt-only ITEM_ACK")
                }
                onEvent(Event.ItemAcked(ackMsg.result))
                receipts[item.meta.itemId] = ackMsg.result
            }

            checkCancelled()
            channel.send(
                ProtocolMessage.BatchEnd(
                    sent = sentIds,
                    summary = "sent ${sentIds.size} of ${staged.items.size} advertised items",
                ),
            )

            val batchAck = receive()
            if (batchAck !is ProtocolMessage.BatchAck ||
                batchAck.results.size != selected.size ||
                batchAck.results.map { it.itemId }.toSet() != select.want.toSet() ||
                batchAck.results.any { result ->
                    !validReceipt(result, byId.getValue(result.itemId).meta, final = true) ||
                        (result.phase == ReceiptPhase.APPLIED_DURABLE &&
                            receipts[result.itemId]?.phase != ReceiptPhase.RECEIVED_VERIFIED)
                }
            ) return unknownResults(selected)
            return batchAck.results
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: TransferCancelledException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure !is TransportException && failure !is IOException) throw failure
            return unknownResults(selected)
        }
    }

    private fun validReceipt(result: ItemResult, meta: ItemMeta, final: Boolean): Boolean {
        if (result.itemId != meta.itemId || result.occurrenceId != meta.occurrenceId) return false
        return when (result.status) {
            ItemStatus.OK -> result.phase == if (final) ReceiptPhase.APPLIED_DURABLE else ReceiptPhase.RECEIVED_VERIFIED
            ItemStatus.UNKNOWN_INTERRUPTED -> final && result.phase == ReceiptPhase.UNKNOWN_INTERRUPTED
            else -> result.phase == ReceiptPhase.FAILED
        }
    }

    private fun unknownResults(items: List<StagedItem>): List<ItemResult> = items.map {
        ItemResult(it.meta.itemId, ItemStatus.UNKNOWN_INTERRUPTED,
            "Final application was not confirmed; resume this move to review it.",
            ReceiptPhase.UNKNOWN_INTERRUPTED, it.meta.occurrenceId)
    }

    private suspend fun sendItem(
        channel: SecureChannel,
        item: StagedItem,
        receivedWholeFile: Boolean,
        checkCancelled: suspend () -> Unit,
        onEvent: (Event) -> Unit,
    ) {
        val meta = item.meta
        if (!item.file.isFile || item.file.length() != meta.size ||
            item.file.inputStream().use { sha256Hex(it) } != meta.sha256
        ) throw TransportException("Prepared bytes changed; start a new move")
        onEvent(Event.ItemStarted(meta.itemId))
        channel.send(ProtocolMessage.ItemBegin(meta.itemId, meta.kind, meta.size, chunkSize))

        var seq = 0
        var sent = 0L
        if (!receivedWholeFile) item.file.inputStream().use { input ->
            val buffer = ByteArray(chunkSize)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                channel.send(ProtocolMessage.ItemData(meta.itemId, seq, buffer.copyOf(read)))
                seq++
                sent += read
                onEvent(Event.ItemProgressed(meta.itemId, sent, meta.size))
            }
        }
        if (receivedWholeFile) onEvent(Event.ItemProgressed(meta.itemId, meta.size, meta.size))

        channel.send(ProtocolMessage.ItemEnd(meta.itemId, meta.sha256))
    }

    private suspend fun receiveSkippingPing(channel: SecureChannel): ProtocolMessage? {
        while (true) {
            val message = channel.receive() ?: return null
            if (message !is ProtocolMessage.Ping) return message
        }
    }

    private companion object {
        /**
         * One ITEM_DATA per Noise frame; Noise caps plaintext at 65 519 B, so 60 KiB
         * leaves room for the CBOR envelope (PROTOCOL.md §3 says "64 KiB default, tune
         * on-device" — 64 KiB itself would not fit).
         */
        const val DEFAULT_CHUNK_BYTES = 60 * 1024
    }
}
