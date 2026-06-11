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
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.PairingPayload
import cc.grepon.portage.model.ProtocolMessage
import cc.grepon.portage.model.TransferManifest
import cc.grepon.portage.providers.contacts.ContactRecord
import cc.grepon.portage.providers.contacts.ContactsApplyProvider
import cc.grepon.portage.providers.contacts.ContactsExportProvider
import cc.grepon.portage.providers.contacts.ContactsStore
import cc.grepon.portage.providers.contacts.LabeledValue
import cc.grepon.portage.transport.NoiseSecureChannelFactory
import cc.grepon.portage.transport.SecureChannel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.security.MessageDigest

/** In-memory ContactsStore for both ends of the smoke test. */
private class MemoryContactsStore(
    private val contacts: MutableList<ContactRecord> = mutableListOf(),
) : ContactsStore {
    val inserted = mutableListOf<ContactRecord>()
    override fun count(): Int = contacts.size
    override fun readAll(): List<ContactRecord> = contacts.toList()
    override fun insert(record: ContactRecord): Boolean {
        inserted += record
        return true
    }
}

/**
 * The whole Tier-0 path for one contact, over the REAL transport: ContactsExportProvider →
 * vCard staging → NoisePSK_XX handshake on TCP loopback → ITEM stream → sha256-verified
 * staging → ContactsApplyProvider on the receiving side. The sender half is scripted inline
 * (app-recv cannot depend on the app-send module), mirroring TransferEngine's frame order.
 */
class LoopbackTransferSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun payload(port: Int, psk: ByteArray, sid: ByteArray) = PairingPayload(
        psk = psk.copyOf(),
        sid = sid.copyOf(),
        ip = listOf("127.0.0.1"),
        port = port,
        expiresAtEpochSeconds = Long.MAX_VALUE / 2,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test(timeout = 60_000L)
    fun `one contact travels export → wire → apply intact`() = runBlocking {
        // --- Sender-side data: one contact exported to vCard bytes.
        val ada = ContactRecord(
            displayName = "Ada Lovelace",
            givenName = "Ada",
            familyName = "Lovelace",
            phones = listOf(LabeledValue("+15551234567", "CELL")),
            emails = listOf(LabeledValue("ada@example.org", "HOME")),
        )
        val exportBytes = ByteArrayOutputStream().also {
            ContactsExportProvider(MemoryContactsStore(mutableListOf(ada))).exportTo(it)
        }.toByteArray()
        val meta = ItemMeta(
            itemId = 1,
            kind = ItemKind.CONTACTS_VCF,
            size = exportBytes.size.toLong(),
            sha256 = sha256(exportBytes),
            displayName = "Contacts",
            group = "People",
        )

        // --- Real Noise handshake over TCP loopback (the QR's PSK/sid, both sides).
        val port = freePort()
        val psk = ByteArray(PairingPayload.PSK_BYTES) { (it + 7).toByte() }
        val sid = ByteArray(PairingPayload.SID_BYTES) { (it * 5).toByte() }
        val factory = NoiseSecureChannelFactory()

        val senderSide = async(Dispatchers.IO) {
            val channel = factory.acceptAsSender(payload(port, psk, sid))
            channel.use { runSenderScript(it, meta, exportBytes) }
        }
        val recvChannel = factory.connectAsReceiver(payload(port, psk, sid))

        // --- Receiver: HELLO → manifest → SELECT → live item stream → apply.
        val receiverStore = MemoryContactsStore()
        val applyProvider = ContactsApplyProvider(receiverStore)
        val results = recvChannel.use { channel ->
            channel.send(ProtocolMessage.Hello("test", "loopback"))
            val manifest = (channel.receive() as ProtocolMessage.Manifest).manifest
            assertThat(manifest.items).containsExactly(meta)
            channel.send(ProtocolMessage.Select(want = listOf(1)))

            ItemStreamReceiver(tmp.newFolder()).run(
                channel = channel,
                expected = manifest.items.associateBy { it.itemId },
                apply = { _, source -> applyProvider.apply(source) },
                onEvent = { },
            )
        }
        val senderAcks = senderSide.await()

        // --- The contact arrived intact and both sides agree it was OK.
        assertThat(receiverStore.inserted).containsExactly(ada)
        assertThat(results.single().status).isEqualTo(ItemStatus.OK)
        assertThat(senderAcks.single().status).isEqualTo(ItemStatus.OK)
    }

    /** Minimal sender half mirroring TransferEngine's frame order for a single item. */
    private suspend fun runSenderScript(
        channel: SecureChannel,
        meta: ItemMeta,
        bytes: ByteArray,
    ): List<cc.grepon.portage.model.ItemResult> {
        check(channel.receive() is ProtocolMessage.Hello) { "expected HELLO" }
        channel.send(
            ProtocolMessage.Manifest(
                TransferManifest("loopback sender", listOf(meta), meta.size),
            ),
        )
        val select = channel.receive() as ProtocolMessage.Select
        check(select.want == listOf(1)) { "receiver should want exactly item 1" }

        channel.send(ProtocolMessage.ItemBegin(meta.itemId, meta.kind, meta.size, CHUNK))
        var seq = 0
        bytes.toList().chunked(CHUNK).forEach { piece ->
            channel.send(ProtocolMessage.ItemData(meta.itemId, seq++, piece.toByteArray()))
        }
        channel.send(ProtocolMessage.ItemEnd(meta.itemId, meta.sha256))
        // The receipt ack over the REAL wire must be OK — apply verdicts ride BATCH_ACK.
        val receipt = channel.receive() as ProtocolMessage.ItemAck
        check(receipt.result.status == ItemStatus.OK) { "receipt ack was ${receipt.result.status}" }

        channel.send(ProtocolMessage.BatchEnd(sent = listOf(1), summary = "sent 1"))
        return (channel.receive() as ProtocolMessage.BatchAck).results
    }

    private companion object {
        const val CHUNK = 16 // tiny on purpose: forces multi-chunk reassembly over the wire
    }
}
