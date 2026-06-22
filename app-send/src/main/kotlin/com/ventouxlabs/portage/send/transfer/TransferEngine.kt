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
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.transport.SecureChannel
import com.ventouxlabs.portage.transport.TransportException

/**
 * Sender side of the manifest-first protocol (PROTOCOL.md §4): HELLO ← · MANIFEST → ·
 * SELECT ← · then per selected item BEGIN/DATA×n/END → ACK ←, then BATCH_END → BATCH_ACK ←.
 *
 * A failed item ack NEVER aborts the batch (§5); only transport-level anomalies (wrong
 * message where the protocol demands one, dead channel mid-stream) throw [TransportException].
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
     * per-item results — the receiver's BATCH_ACK when it arrives, otherwise the
     * accumulated ITEM_ACKs (a receiver that closes right after the last ack is fine).
     */
    suspend fun run(
        channel: SecureChannel,
        staged: StagedManifest,
        onEvent: (Event) -> Unit,
    ): List<ItemResult> {
        val helloMsg = receiveSkippingPing(channel)
        if (helloMsg !is ProtocolMessage.Hello) {
            throw TransportException("expected HELLO, got ${helloMsg?.javaClass?.simpleName ?: "end of stream"}")
        }

        channel.send(ProtocolMessage.Manifest(staged.manifest))

        val select = receiveSkippingPing(channel)
        if (select !is ProtocolMessage.Select) {
            throw TransportException("expected SELECT, got ${select?.javaClass?.simpleName ?: "end of stream"}")
        }
        onEvent(Event.SelectReceived(select.want))

        val itemAcks = mutableListOf<ItemResult>()
        val sentIds = mutableListOf<Int>()
        // Sender-driven, sequential, in manifest order; unknown requested ids are ignored.
        for (item in staged.items) {
            if (item.meta.itemId !in select.want) continue
            sendItem(channel, item, onEvent)
            sentIds += item.meta.itemId

            val ackMsg = receiveSkippingPing(channel)
            if (ackMsg !is ProtocolMessage.ItemAck) {
                throw TransportException("expected ITEM_ACK, got ${ackMsg?.javaClass?.simpleName ?: "end of stream"}")
            }
            itemAcks += ackMsg.result
            onEvent(Event.ItemAcked(ackMsg.result))
            // Any non-OK status is the receiver's per-item verdict — carry on (§5).
        }

        channel.send(
            ProtocolMessage.BatchEnd(
                sent = sentIds,
                summary = "sent ${sentIds.size} of ${staged.items.size} advertised items",
            ),
        )

        return when (val batchAck = receiveSkippingPing(channel)) {
            is ProtocolMessage.BatchAck -> batchAck.results
            null -> itemAcks // receiver closed after acking everything — acceptable
            else -> throw TransportException("expected BATCH_ACK, got ${batchAck.javaClass.simpleName}")
        }
    }

    private suspend fun sendItem(
        channel: SecureChannel,
        item: StagedItem,
        onEvent: (Event) -> Unit,
    ) {
        val meta = item.meta
        onEvent(Event.ItemStarted(meta.itemId))
        channel.send(ProtocolMessage.ItemBegin(meta.itemId, meta.kind, meta.size, chunkSize))

        var seq = 0
        var sent = 0L
        item.file.inputStream().use { input ->
            val buffer = ByteArray(chunkSize)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                channel.send(ProtocolMessage.ItemData(meta.itemId, seq, buffer.copyOf(read)))
                seq++
                sent += read
                onEvent(Event.ItemProgressed(meta.itemId, sent, meta.size))
            }
        }

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
