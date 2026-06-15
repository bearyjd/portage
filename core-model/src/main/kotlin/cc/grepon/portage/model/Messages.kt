/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/**
 * Integer message-type discriminator carried as field `t` on the wire.
 * Order/values are FROZEN per protocol version — see docs/prp/PROTOCOL.md §4.
 */
@Serializable
enum class MessageType(val t: Int) {
    HELLO(0),
    MANIFEST(1),
    SELECT(2),
    ITEM_BEGIN(3),
    ITEM_DATA(4),
    ITEM_END(5),
    ITEM_ACK(6),
    BATCH_END(7),
    BATCH_ACK(8),
    PING(9),
}

/** Receiver's per-item verdict. Anything other than [OK] never aborts the batch. */
@Serializable
enum class ItemStatus { OK, SKIPPED, HASH_MISMATCH, WRITE_ERROR, UNKNOWN_KIND, OVERSIZE }

/** A request to resume a partially-received item from a byte offset. */
@Serializable
data class ResumePoint(val itemId: Int, val offset: Long)

@Serializable
data class ItemResult(val itemId: Int, val status: ItemStatus, val detail: String? = null)

/**
 * Closed set of application messages. The CBOR codec (in :core-transport) maps each to a
 * map with integer key `t` = [type].t and ignores unknown keys for forward compat.
 */
sealed interface ProtocolMessage {
    val type: MessageType

    @Serializable
    data class Hello(val appVersion: String, val osFingerprint: String) : ProtocolMessage {
        override val type get() = MessageType.HELLO
    }

    @Serializable
    data class Manifest(val manifest: TransferManifest) : ProtocolMessage {
        override val type get() = MessageType.MANIFEST
    }

    @Serializable
    data class Select(val want: List<Int>, val resume: List<ResumePoint> = emptyList()) : ProtocolMessage {
        override val type get() = MessageType.SELECT
    }

    @Serializable
    data class ItemBegin(
        val itemId: Int,
        val kind: ItemKind,
        val size: Long,
        val chunkSize: Int,
    ) : ProtocolMessage {
        override val type get() = MessageType.ITEM_BEGIN
    }

    @Serializable
    @OptIn(ExperimentalSerializationApi::class)
    data class ItemData(
        val itemId: Int,
        val seq: Int,
        // @ByteString → compact CBOR byte string (major type 2). WITHOUT it kotlinx CBOR encodes a
        // ByteArray as a CBOR array of integers (~2x for text bytes), so a single 60 KiB chunk
        // overflowed the 65535-byte u16 frame cap — sender threw "frame exceeds u16 cap", receiver
        // saw "connection lost mid item". Found on-device 2026-06-14 (C1 contacts — the first large
        // item ever sent over the wire; small items always fit even at 2x, which is why it hid).
        @ByteString val bytes: ByteArray,
    ) : ProtocolMessage {
        override val type get() = MessageType.ITEM_DATA

        // ByteArray needs structural equals/hashCode for value semantics.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ItemData) return false
            return itemId == other.itemId && seq == other.seq && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int =
            (itemId * 31 + seq) * 31 + bytes.contentHashCode()
    }

    @Serializable
    data class ItemEnd(val itemId: Int, val sha256: String) : ProtocolMessage {
        override val type get() = MessageType.ITEM_END
    }

    @Serializable
    data class ItemAck(val result: ItemResult) : ProtocolMessage {
        override val type get() = MessageType.ITEM_ACK
    }

    @Serializable
    data class BatchEnd(val sent: List<Int>, val summary: String) : ProtocolMessage {
        override val type get() = MessageType.BATCH_END
    }

    @Serializable
    data class BatchAck(val results: List<ItemResult>) : ProtocolMessage {
        override val type get() = MessageType.BATCH_ACK
    }

    @Serializable
    data object Ping : ProtocolMessage {
        override val type get() = MessageType.PING
    }
}
