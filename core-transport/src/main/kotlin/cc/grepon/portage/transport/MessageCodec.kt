/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.transport

import cc.grepon.portage.model.MessageType
import cc.grepon.portage.model.ProtocolMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/** Serializes [ProtocolMessage] to/from bytes for the transport. */
interface MessageCodec {
    fun encode(message: ProtocolMessage): ByteArray
    fun decode(bytes: ByteArray): ProtocolMessage
}

/**
 * Wire form: `byte t || CBOR(body)` where `t` is [MessageType.t] (PROTOCOL.md §3). Using
 * explicit per-type serializers (not polymorphic config) keeps the framing trivial and the
 * discriminator a single byte. Unknown CBOR keys are ignored for forward compatibility.
 */
@OptIn(ExperimentalSerializationApi::class)
class CborMessageCodec : MessageCodec {

    private val cbor = Cbor { ignoreUnknownKeys = true }

    override fun encode(message: ProtocolMessage): ByteArray {
        val body: ByteArray = when (message) {
            is ProtocolMessage.Hello -> cbor.encodeToByteArray(ProtocolMessage.Hello.serializer(), message)
            is ProtocolMessage.Manifest -> cbor.encodeToByteArray(ProtocolMessage.Manifest.serializer(), message)
            is ProtocolMessage.Select -> cbor.encodeToByteArray(ProtocolMessage.Select.serializer(), message)
            is ProtocolMessage.ItemBegin -> cbor.encodeToByteArray(ProtocolMessage.ItemBegin.serializer(), message)
            is ProtocolMessage.ItemData -> cbor.encodeToByteArray(ProtocolMessage.ItemData.serializer(), message)
            is ProtocolMessage.ItemEnd -> cbor.encodeToByteArray(ProtocolMessage.ItemEnd.serializer(), message)
            is ProtocolMessage.ItemAck -> cbor.encodeToByteArray(ProtocolMessage.ItemAck.serializer(), message)
            is ProtocolMessage.BatchEnd -> cbor.encodeToByteArray(ProtocolMessage.BatchEnd.serializer(), message)
            is ProtocolMessage.BatchAck -> cbor.encodeToByteArray(ProtocolMessage.BatchAck.serializer(), message)
            ProtocolMessage.Ping -> cbor.encodeToByteArray(ProtocolMessage.Ping.serializer(), ProtocolMessage.Ping)
        }
        return ByteArray(1 + body.size).also {
            it[0] = message.type.t.toByte()
            body.copyInto(it, 1)
        }
    }

    override fun decode(bytes: ByteArray): ProtocolMessage {
        require(bytes.isNotEmpty()) { "empty message frame" }
        val t = bytes[0].toInt()
        val type = MessageType.entries.firstOrNull { it.t == t }
            ?: throw IllegalArgumentException("unknown message type byte: $t")
        val body = bytes.copyOfRange(1, bytes.size)
        return when (type) {
            MessageType.HELLO -> cbor.decodeFromByteArray(ProtocolMessage.Hello.serializer(), body)
            MessageType.MANIFEST -> cbor.decodeFromByteArray(ProtocolMessage.Manifest.serializer(), body)
            MessageType.SELECT -> cbor.decodeFromByteArray(ProtocolMessage.Select.serializer(), body)
            MessageType.ITEM_BEGIN -> cbor.decodeFromByteArray(ProtocolMessage.ItemBegin.serializer(), body)
            MessageType.ITEM_DATA -> cbor.decodeFromByteArray(ProtocolMessage.ItemData.serializer(), body)
            MessageType.ITEM_END -> cbor.decodeFromByteArray(ProtocolMessage.ItemEnd.serializer(), body)
            MessageType.ITEM_ACK -> cbor.decodeFromByteArray(ProtocolMessage.ItemAck.serializer(), body)
            MessageType.BATCH_END -> cbor.decodeFromByteArray(ProtocolMessage.BatchEnd.serializer(), body)
            MessageType.BATCH_ACK -> cbor.decodeFromByteArray(ProtocolMessage.BatchAck.serializer(), body)
            MessageType.PING -> ProtocolMessage.Ping
        }
    }
}
