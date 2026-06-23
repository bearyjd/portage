/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.transfer

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.apk.ApkContainerValidation
import com.ventouxlabs.portage.transport.SecureChannel
import com.ventouxlabs.portage.transport.TransportException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private class ScriptedChannel(vararg incoming: ProtocolMessage?) : SecureChannel {
    private val queue = ArrayDeque(incoming.toList())
    val sent = mutableListOf<ProtocolMessage>()
    override suspend fun send(message: ProtocolMessage) { sent += message }
    override suspend fun receive(): ProtocolMessage? =
        if (queue.isEmpty()) null else queue.removeFirst()
    override fun close() = Unit
}

class ItemStreamReceiverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = "BEGIN:VCARD...END:VCARD".toByteArray()
    private val meta = ItemMeta(1, ItemKind.CONTACTS_VCF, payload.size.toLong(), sha256(payload), "Contacts", "People")

    private fun itemFrames(meta: ItemMeta, bytes: ByteArray, chunk: Int = 8): List<ProtocolMessage> {
        val frames = mutableListOf<ProtocolMessage>(
            ProtocolMessage.ItemBegin(meta.itemId, meta.kind, meta.size, chunk),
        )
        var seq = 0
        bytes.toList().chunked(chunk).forEach { piece ->
            frames += ProtocolMessage.ItemData(meta.itemId, seq++, piece.toByteArray())
        }
        frames += ProtocolMessage.ItemEnd(meta.itemId, sha256(bytes))
        return frames
    }

    private fun receiver() = ItemStreamReceiver(tmp.newFolder())

    @Test
    fun `happy path stages, verifies, acks, applies, and batch-acks`() = runTest {
        val frames = itemFrames(meta, payload) + ProtocolMessage.BatchEnd(listOf(1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())
        val applied = mutableListOf<ByteArray>()

        val results = receiver().run(
            channel = channel,
            expected = mapOf(1 to meta),
            apply = { _, source: InputStream ->
                applied += source.readBytes()
                ApplyOutcome(ItemStatus.OK, "applied 1, skipped 0")
            },
            onEvent = { },
        )

        assertThat(applied.single()).isEqualTo(payload) // byte-exact through staging
        val ack = channel.sent.filterIsInstance<ProtocolMessage.ItemAck>().single()
        assertThat(ack.result.status).isEqualTo(ItemStatus.OK)
        val batchAck = channel.sent.filterIsInstance<ProtocolMessage.BatchAck>().single()
        assertThat(batchAck.results).isEqualTo(results)
        assertThat(results.single().status).isEqualTo(ItemStatus.OK)
        assertThat(results.single().detail).isEqualTo("applied 1, skipped 0")
    }

    @Test
    fun `a corrupted payload is HASH_MISMATCH and never reaches apply`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, "x".repeat(payload.size).toByteArray()),
            ProtocolMessage.ItemEnd(1, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ ->
            applyCalled = true
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.HASH_MISMATCH)
    }

    @Test
    fun `a failed item never aborts the batch — the next item still applies`() = runTest {
        val meta2 = ItemMeta(2, ItemKind.CALL_LOG, 5L, sha256("calls".toByteArray()), "Calls", "History")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, "garbage!".toByteArray()),
            ProtocolMessage.ItemEnd(1, "0".repeat(64)),
        ) + itemFrames(meta2, "calls".toByteArray()) + ProtocolMessage.BatchEnd(listOf(1, 2), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta, 2 to meta2), { m, _ ->
            ApplyOutcome(ItemStatus.OK, "ok ${m.itemId}")
        }) { }

        assertThat(results.map { it.status })
            .containsExactly(ItemStatus.HASH_MISMATCH, ItemStatus.OK).inOrder()
    }

    @Test
    fun `the receiver's own byte cap refuses an item even when manifest and wire agree`() = runTest {
        // PROTOCOL.md §5: receiver-enforced max item size REGARDLESS of manifest claims.
        val bigMeta = ItemMeta(1, ItemKind.CONTACTS_VCF, 100, "a".repeat(64), "Contacts", "People")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, bigMeta.kind, bigMeta.size, 8), // wire agrees: 100 bytes
            ProtocolMessage.ItemData(1, 0, ByteArray(100)),
            ProtocolMessage.ItemEnd(1, bigMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = ItemStreamReceiver(tmp.newFolder(), maxItemBytes = 4)
            .run(channel, mapOf(1 to bigMeta), { _, _ ->
                applyCalled = true
                ApplyOutcome(ItemStatus.OK)
            }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `a per-kind cap raises the ceiling for the relay kind only — a Tier-0 item of the same size is still refused`() = runTest {
        // The relay path raises the per-item cap FOR THE RELAY KIND ONLY. A relay item just above the
        // 64 MiB Tier-0 default is accepted, while a Tier-0 PII item of the EXACT SAME size on the
        // SAME receiver is still OVERSIZE — the proof the raised cap does not leak (PRP-06 §5).
        val tierZeroDefault = 64L * 1024 * 1024
        val big = tierZeroDefault + 1
        val relayMeta = ItemMeta(1, ItemKind.APP_BACKUP_RELAY, big, "a".repeat(64), "Signal backup", "App backups")
        val piiMeta = ItemMeta(2, ItemKind.CONTACTS_VCF, big, "b".repeat(64), "Contacts", "People")
        val frames = listOf(
            // Relay item: wire + manifest agree at `big`; relay cap allows it. We don't deliver the
            // bytes (no need to materialize 64 MiB) — the up-front begin.size cap is what we assert,
            // so it's drained as a mismatch later; what matters is it is NOT rejected up front.
            ProtocolMessage.ItemBegin(1, ItemKind.APP_BACKUP_RELAY, big, 8),
            ProtocolMessage.ItemEnd(1, relayMeta.sha256),
            // Tier-0 PII item of the SAME oversize: must be refused before staging a byte.
            ProtocolMessage.ItemBegin(2, ItemKind.CONTACTS_VCF, big, 8),
            ProtocolMessage.ItemEnd(2, piiMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1, 2), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = ItemStreamReceiver(
            tmp.newFolder(),
            maxBytesByKind = mapOf(ItemKind.APP_BACKUP_RELAY to (2L * 1024 * 1024 * 1024)),
        ).run(channel, mapOf(1 to relayMeta, 2 to piiMeta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }

        // The relay item passed the up-front cap (it fails later only on the empty-vs-advertised
        // hash, NOT on OVERSIZE); the PII item is rejected by the unchanged 64 MiB Tier-0 cap.
        assertThat(results.first { it.itemId == 1 }.status).isNotEqualTo(ItemStatus.OVERSIZE)
        assertThat(results.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `a relay item over its own raised ceiling is still refused as OVERSIZE`() = runTest {
        // The relay cap is finite: an item above the raised ceiling is still rejected (no unbounded
        // writes). Use a tiny explicit relay cap so the test stays cheap.
        val relayCap = 16L
        val overMeta = ItemMeta(1, ItemKind.APP_BACKUP_RELAY, 100L, "a".repeat(64), "Backup", "App backups")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.APP_BACKUP_RELAY, 100L, 8), // wire agrees
            ProtocolMessage.ItemData(1, 0, ByteArray(100)),
            ProtocolMessage.ItemEnd(1, overMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = ItemStreamReceiver(
            tmp.newFolder(),
            maxBytesByKind = mapOf(ItemKind.APP_BACKUP_RELAY to relayCap),
        ).run(channel, mapOf(1 to overMeta), { _, _ -> applyCalled = true; ApplyOutcome(ItemStatus.OK) }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `a relay item under its raised ceiling stages, verifies, and applies`() = runTest {
        // A 65 MiB-shaped relay item (modeled small to keep the test fast, but above the Tier-0
        // default would be rejected without the per-kind raise) flows end to end under the raise.
        val blob = "OPAQUE-CIPHERTEXT".toByteArray()
        val relayMeta = ItemMeta(1, ItemKind.APP_BACKUP_RELAY, blob.size.toLong(), sha256(blob), "Signal backup", "App backups")
        val frames = itemFrames(relayMeta, blob) + ProtocolMessage.BatchEnd(listOf(1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())
        val applied = mutableListOf<ByteArray>()

        val results = ItemStreamReceiver(
            tmp.newFolder(),
            // Default Tier-0 cap kept tiny to PROVE the relay raise is what admits this item.
            maxItemBytes = 4,
            maxBytesByKind = mapOf(ItemKind.APP_BACKUP_RELAY to (2L * 1024 * 1024 * 1024)),
        ).run(channel, mapOf(1 to relayMeta), { _, source -> applied += source.readBytes(); ApplyOutcome(ItemStatus.OK) }) { }

        assertThat(results.single().status).isEqualTo(ItemStatus.OK)
        assertThat(applied.single()).isEqualTo(blob) // byte-exact opaque bytes through staging
    }

    @Test
    fun `an item whose size disagrees with the manifest is refused as OVERSIZE`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size + 999, 8), // liar
            ProtocolMessage.ItemData(1, 0, payload),
            ProtocolMessage.ItemEnd(1, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ ->
            applyCalled = true
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `more bytes than advertised flips the item to OVERSIZE mid-stream`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, payload),
            ProtocolMessage.ItemData(1, 1, payload), // double delivery
            ProtocolMessage.ItemEnd(1, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }

        assertThat(results.single().status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `an unrequested item id is drained and SKIPPED without touching apply`() = runTest {
        val rogue = ItemMeta(7, ItemKind.SETTINGS, 4L, sha256("evil".toByteArray()), "X", "Y")
        val frames = itemFrames(rogue, "evil".toByteArray()) +
            itemFrames(meta, payload) +
            ProtocolMessage.BatchEnd(listOf(7, 1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())
        val appliedKinds = mutableListOf<ItemKind>()

        val results = receiver().run(channel, mapOf(1 to meta), { m, _ ->
            appliedKinds += m.kind
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(appliedKinds).containsExactly(ItemKind.CONTACTS_VCF)
        assertThat(results.first { it.itemId == 7 }.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(results.first { it.itemId == 1 }.status).isEqualTo(ItemStatus.OK)
    }

    @Test
    fun `a kind that disagrees with the manifest is refused — the wire can't relabel items`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.SETTINGS, meta.size, 8), // manifest says contacts
            ProtocolMessage.ItemData(1, 0, payload),
            ProtocolMessage.ItemEnd(1, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ ->
            applyCalled = true
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.UNKNOWN_KIND)
    }

    @Test
    fun `selected items the sender never delivered are reported SKIPPED`() = runTest {
        val meta2 = ItemMeta(2, ItemKind.CALL_LOG, 5L, "f".repeat(64), "Calls", "History")
        val frames = itemFrames(meta, payload) + ProtocolMessage.BatchEnd(listOf(1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta, 2 to meta2), { _, _ ->
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(results.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.SKIPPED)
    }

    @Test
    fun `a dropped connection mid-item is a TransportException, not a hang or partial apply`() = runTest {
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, payload.copyOf(8)),
            null, // connection lost
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false
        val staging = tmp.newFolder()

        val thrown = runCatching {
            ItemStreamReceiver(staging).run(channel, mapOf(1 to meta), { _, _ ->
                applyCalled = true
                ApplyOutcome(ItemStatus.OK)
            }) { }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(TransportException::class.java)
        assertThat(applyCalled).isFalse()
        assertThat(staging.listFiles().orEmpty()).isEmpty() // partials never survive
    }

    @Test
    fun `staging files are deleted after a successful run too`() = runTest {
        val staging = tmp.newFolder()
        val frames = itemFrames(meta, payload) + ProtocolMessage.BatchEnd(listOf(1), "done")

        ItemStreamReceiver(staging).run(
            ScriptedChannel(*frames.toTypedArray()),
            mapOf(1 to meta),
            { _, _ -> ApplyOutcome(ItemStatus.OK) },
        ) { }

        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a sender spraying endless unrequested items hits the item-count cap`() = runTest {
        // PROTOCOL.md §5: the receiver enforces a max item count regardless of manifest
        // claims. Stream far more ITEM_BEGINs than were selected and never send BATCH_END.
        val frames = mutableListOf<ProtocolMessage>()
        repeat(50) { n ->
            frames += ProtocolMessage.ItemBegin(1000 + n, ItemKind.SETTINGS, 1, 8)
            frames += ProtocolMessage.ItemEnd(1000 + n, "0".repeat(64))
        }
        val channel = ScriptedChannel(*frames.toTypedArray())

        val thrown = runCatching {
            receiver().run(channel, mapOf(1 to meta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(TransportException::class.java)
        assertThat(thrown?.message).contains("item-count")
    }

    @Test
    fun `apply throwing is contained as a WRITE_ERROR result`() = runTest {
        val frames = itemFrames(meta, payload) + ProtocolMessage.BatchEnd(listOf(1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ ->
            throw IllegalStateException("provider blew up")
        }) { }

        assertThat(results.single().status).isEqualTo(ItemStatus.WRITE_ERROR)
        // The receipt ack already went out OK; the apply failure rides BATCH_ACK.
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.BatchAck>().single().results.single().status)
            .isEqualTo(ItemStatus.WRITE_ERROR)
    }

    // --- APK keystone receiver gate (ADR-006 Phase 2): per-item cap, aggregate budget, free space ---

    /**
     * A relay-style mapping wires the APK per-item cap exactly as production does. Ample free space is
     * the default for these tests; the free-space gate gets its own test below.
     */
    private fun apkReceiver(
        maxBytesByKind: Map<ItemKind, Long> = mapOf(ItemKind.APK to ApkContainerValidation.MAX_APK_ITEM_BYTES),
        freeSpace: (java.io.File) -> Long = { Long.MAX_VALUE },
    ) = ItemStreamReceiver(
        tmp.newFolder(),
        maxBytesByKind = maxBytesByKind,
        freeSpace = freeSpace,
    )

    @Test
    fun `AC-4 a 65 MiB APK item is accepted through the raised cap while a same-size Tier-0 item is OVERSIZE`() = runTest {
        // The APK per-item cap (1 GiB) admits a 65 MiB APK item that the 64 MiB Tier-0 default would
        // refuse; a same-size SETTINGS item on the SAME receiver is still OVERSIZE — APK-scoped raise.
        val big = 64L * 1024 * 1024 + 1
        val apkMeta = ItemMeta(1, ItemKind.APK, big, "a".repeat(64), "Some app", "Apps")
        val piiMeta = ItemMeta(2, ItemKind.SETTINGS, big, "b".repeat(64), "Settings", "System")
        val frames = listOf(
            // Sizes are declared, not materialized (the up-front begin.size cap is what we assert).
            ProtocolMessage.ItemBegin(1, ItemKind.APK, big, 8),
            ProtocolMessage.ItemEnd(1, apkMeta.sha256),
            ProtocolMessage.ItemBegin(2, ItemKind.SETTINGS, big, 8),
            ProtocolMessage.ItemEnd(2, piiMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1, 2), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = apkReceiver()
            .run(channel, mapOf(1 to apkMeta, 2 to piiMeta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }

        // The APK item passed the up-front cap (it fails later only on the empty-vs-advertised hash,
        // NOT on OVERSIZE); the Tier-0 item is refused by the unchanged 64 MiB default.
        assertThat(results.first { it.itemId == 1 }.status).isNotEqualTo(ItemStatus.OVERSIZE)
        assertThat(results.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `AC-5 an APK item whose bytes exceed the manifest size is OVERSIZE and its staged file is wiped`() = runTest {
        val staging = tmp.newFolder()
        val blob = "APK-CONTAINER".toByteArray()
        val apkMeta = ItemMeta(1, ItemKind.APK, blob.size.toLong(), sha256(blob), "Some app", "Apps")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.APK, apkMeta.size, 8),
            ProtocolMessage.ItemData(1, 0, blob),
            ProtocolMessage.ItemData(1, 1, blob), // more bytes than advertised
            ProtocolMessage.ItemEnd(1, apkMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = ItemStreamReceiver(
            staging,
            maxBytesByKind = mapOf(ItemKind.APK to ApkContainerValidation.MAX_APK_ITEM_BYTES),
            freeSpace = { Long.MAX_VALUE },
        ).run(channel, mapOf(1 to apkMeta), { _, _ -> applyCalled = true; ApplyOutcome(ItemStatus.OK) }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.OVERSIZE)
        assertThat(staging.listFiles().orEmpty()).isEmpty() // staged file wiped on reject
    }

    @Test
    fun `AC-5 an APK item whose sha256 disagrees is HASH_MISMATCH and its staged file is wiped`() = runTest {
        val staging = tmp.newFolder()
        val blob = "APK-CONTAINER".toByteArray()
        // Manifest hash is wrong on purpose: streamed bytes will not match it.
        val apkMeta = ItemMeta(1, ItemKind.APK, blob.size.toLong(), "0".repeat(64), "Some app", "Apps")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.APK, apkMeta.size, 8),
            ProtocolMessage.ItemData(1, 0, blob),
            ProtocolMessage.ItemEnd(1, sha256(blob)), // wire hash of the real bytes ≠ manifest hash
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = ItemStreamReceiver(
            staging,
            maxBytesByKind = mapOf(ItemKind.APK to ApkContainerValidation.MAX_APK_ITEM_BYTES),
            freeSpace = { Long.MAX_VALUE },
        ).run(channel, mapOf(1 to apkMeta), { _, _ -> applyCalled = true; ApplyOutcome(ItemStatus.OK) }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.HASH_MISMATCH)
        assertThat(staging.listFiles().orEmpty()).isEmpty() // staged file wiped on reject
    }

    @Test
    fun `AC-16 an APK item fails closed when free space is under twice its size, then proceeds when ample`() = runTest {
        val blob = "APK-CONTAINER-BYTES".toByteArray()
        val size = blob.size.toLong()
        val apkMeta = ItemMeta(1, ItemKind.APK, size, sha256(blob), "Some app", "Apps")

        // Under the double-stage requirement (2*size - 1): fail closed, nothing staged.
        val tightStaging = tmp.newFolder()
        val tightFrames = itemFrames(apkMeta, blob) + ProtocolMessage.BatchEnd(listOf(1), "done")
        var tightApplyCalled = false
        val tight = ItemStreamReceiver(
            tightStaging,
            maxBytesByKind = mapOf(ItemKind.APK to ApkContainerValidation.MAX_APK_ITEM_BYTES),
            freeSpace = { 2 * size - 1 },
        ).run(ScriptedChannel(*tightFrames.toTypedArray()), mapOf(1 to apkMeta), { _, _ ->
            tightApplyCalled = true
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(tightApplyCalled).isFalse()
        assertThat(tight.single().status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(tightStaging.listFiles().orEmpty()).isEmpty() // partial staging wiped / never created

        // Exactly at the double-stage requirement (2*size): proceeds end to end.
        val roomyStaging = tmp.newFolder()
        val roomyFrames = itemFrames(apkMeta, blob) + ProtocolMessage.BatchEnd(listOf(1), "done")
        val applied = mutableListOf<ByteArray>()
        val roomy = ItemStreamReceiver(
            roomyStaging,
            maxBytesByKind = mapOf(ItemKind.APK to ApkContainerValidation.MAX_APK_ITEM_BYTES),
            freeSpace = { 2 * size },
        ).run(ScriptedChannel(*roomyFrames.toTypedArray()), mapOf(1 to apkMeta), { _, source ->
            applied += source.readBytes()
            ApplyOutcome(ItemStatus.OK)
        }) { }

        // The gate let it through to stage->hash->apply-dispatch unchanged; no ApkApplyProvider yet, so
        // the receiver's own apply lambda stands in — assert the GATE passed (byte-exact through staging).
        assertThat(roomy.single().status).isEqualTo(ItemStatus.OK)
        assertThat(applied.single()).isEqualTo(blob)
    }

    @Test
    fun `AC-17 APK items each under the per-item cap are aggregate-bounded — the one that breaches is OVERSIZE`() = runTest {
        // Three APK items, each 3 GiB — individually under the per-item cap (raised to 4 GiB here so
        // the per-item gate is NOT what fires), but whose running total crosses MAX_APK_TOTAL_BYTES
        // (8 GiB): 3 + 3 GiB are accepted (6 GiB), the third (would-be 9 GiB) breaches and is refused
        // OVERSIZE. A NON-APK item is never aggregate-bounded. The 4 GiB per-item cap isolates AC-17
        // from the per-item cap (AC-4's concern); MAX_APK_TOTAL_BYTES itself stays the real 8 GiB.
        val perItemCap = 4L * 1024 * 1024 * 1024
        val threeGiB = 3L * 1024 * 1024 * 1024
        val a = ItemMeta(1, ItemKind.APK, threeGiB, "a".repeat(64), "App A", "Apps")
        val b = ItemMeta(2, ItemKind.APK, threeGiB, "b".repeat(64), "App B", "Apps")
        val c = ItemMeta(3, ItemKind.APK, threeGiB, "c".repeat(64), "App C", "Apps")
        // A non-APK item of the same declared 3 GiB would blow the 64 MiB Tier-0 cap, so model it small
        // to prove only that the APK aggregate never counts it; keep it under its own default cap.
        val nonApk = ItemMeta(4, ItemKind.SETTINGS, 4L, sha256("set!".toByteArray()), "Settings", "System")
        // A 4th APK item sized to fit the CORRECT remaining budget after items 1+2 (8 GiB - 6 GiB = 2 GiB).
        // With item 3 rejected its size never enters acceptedBytes, so item 4 sees the real 2 GiB headroom.
        val twoGiB = 2L * 1024 * 1024 * 1024
        val d = ItemMeta(5, ItemKind.APK, twoGiB, "d".repeat(64), "App D", "Apps")
        val frames = listOf(
            // Sizes declared, bytes not materialized — the up-front aggregate gate is what we assert.
            ProtocolMessage.ItemBegin(1, ItemKind.APK, threeGiB, 8),
            ProtocolMessage.ItemEnd(1, a.sha256),
            ProtocolMessage.ItemBegin(2, ItemKind.APK, threeGiB, 8),
            ProtocolMessage.ItemEnd(2, b.sha256),
            ProtocolMessage.ItemBegin(3, ItemKind.APK, threeGiB, 8),
            ProtocolMessage.ItemEnd(3, c.sha256),
        ) + itemFrames(nonApk, "set!".toByteArray()) + listOf(
            ProtocolMessage.ItemBegin(5, ItemKind.APK, twoGiB, 8),
            ProtocolMessage.ItemEnd(5, d.sha256),
            ProtocolMessage.BatchEnd(listOf(1, 2, 3, 4, 5), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = apkReceiver(maxBytesByKind = mapOf(ItemKind.APK to perItemCap))
            .run(channel, mapOf(1 to a, 2 to b, 3 to c, 4 to nonApk, 5 to d), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }

        // First two APK items clear the aggregate (they fail later only on the empty-vs-advertised hash,
        // NOT on OVERSIZE); the third breaches 8 GiB and is refused up front as OVERSIZE.
        assertThat(results.first { it.itemId == 1 }.status).isNotEqualTo(ItemStatus.OVERSIZE)
        assertThat(results.first { it.itemId == 2 }.status).isNotEqualTo(ItemStatus.OVERSIZE)
        assertThat(results.first { it.itemId == 3 }.status).isEqualTo(ItemStatus.OVERSIZE)
        assertThat(results.first { it.itemId == 3 }.detail).contains("aggregate")
        // The non-APK item is never aggregate-bounded — it rides the normal path to OK.
        assertThat(results.first { it.itemId == 4 }.status).isEqualTo(ItemStatus.OK)
        // The rejected item 3 must NOT have poisoned the budget: item 5 (2 GiB, fits the 2 GiB
        // remaining after items 1+2) is accepted, not OVERSIZE.
        assertThat(results.first { it.itemId == 5 }.status).isNotEqualTo(ItemStatus.OVERSIZE)
    }

    // --- Security hardening: negative-size floor + budget poison-proofing (2026-06-20) ---

    @Test
    fun `negative declared size is refused OVERSIZE before staging and leaves the budget un-poisoned`() = runTest {
        // A hostile sender with size = -1 (meta matches, passes size-agreement gate without the floor).
        // The negative-floor gate must fire FIRST as OVERSIZE("negative declared size"), and a
        // following legit APK item must still be judged against the un-corrupted budget.
        val negMeta1 = ItemMeta(1, ItemKind.APK, -1L, "a".repeat(64), "Evil -1", "Apps")
        val negMeta2 = ItemMeta(2, ItemKind.APK, Long.MIN_VALUE, "b".repeat(64), "Evil MIN", "Apps")
        val legitBlob = "GOOD-APK".toByteArray()
        val legitMeta = ItemMeta(3, ItemKind.APK, legitBlob.size.toLong(), sha256(legitBlob), "Legit", "Apps")
        val staging = tmp.newFolder()
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.APK, -1L, 8),
            ProtocolMessage.ItemEnd(1, negMeta1.sha256),
            ProtocolMessage.ItemBegin(2, ItemKind.APK, Long.MIN_VALUE, 8),
            ProtocolMessage.ItemEnd(2, negMeta2.sha256),
        ) + itemFrames(legitMeta, legitBlob) + ProtocolMessage.BatchEnd(listOf(1, 2, 3), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())
        val applied = mutableListOf<ByteArray>()

        val results = ItemStreamReceiver(
            staging,
            maxBytesByKind = mapOf(ItemKind.APK to ApkContainerValidation.MAX_APK_ITEM_BYTES),
            freeSpace = { Long.MAX_VALUE },
        ).run(channel, mapOf(1 to negMeta1, 2 to negMeta2, 3 to legitMeta), { _, src ->
            applied += src.readBytes()
            ApplyOutcome(ItemStatus.OK)
        }) { }

        // Both negative-size items are refused OVERSIZE with the "negative declared size" detail.
        val r1 = results.first { it.itemId == 1 }
        val r2 = results.first { it.itemId == 2 }
        assertThat(r1.status).isEqualTo(ItemStatus.OVERSIZE)
        assertThat(r1.detail).contains("negative")
        assertThat(r2.status).isEqualTo(ItemStatus.OVERSIZE)
        assertThat(r2.detail).contains("negative")
        // Nothing was staged for the negative items.
        // (The staging sweep runs in finally; assert apply was NOT called for them.)
        assertThat(applied.size).isEqualTo(1)
        assertThat(applied.single()).isEqualTo(legitBlob)
        // The legit item was not harmed by the negative items — it was accepted and applied OK.
        assertThat(results.first { it.itemId == 3 }.status).isEqualTo(ItemStatus.OK)
    }

    @Test
    fun `rejected-item-does-not-poison-budget — over-cap item size is never added to acceptedBytes`() = runTest {
        // Scenario: per-item cap = 1 GiB (production APK cap). Item 1 declares 2 GiB (per-item bust).
        // Item 2 declares 7 GiB — fits the full 8 GiB aggregate budget but needs a raised per-item cap.
        // We use two sequential receivers sharing the same staging folder so each has its own budget:
        // Instead, use ONE receiver with per-item cap raised to 8 GiB so both items pass the per-item
        // gate, BUT item 1 has a size that ALSO exceeds the aggregate (> 8 GiB) — rejected by aggregate.
        // Then item 2 (1 GiB) must still be accepted (budget = 0, not poisoned).
        // Per-item cap must EXCEED overAggregate so the per-item gate does NOT fire first — isolating
        // the aggregate gate as the sole rejection reason. 9 GiB > 8 GiB+1, so only aggregate fires.
        val perItemCap = 9L * 1024 * 1024 * 1024
        val overAggregate = ApkContainerValidation.MAX_APK_TOTAL_BYTES + 1L  // 8 GiB + 1 → aggregate bust
        val item1Meta = ItemMeta(1, ItemKind.APK, overAggregate, "a".repeat(64), "Over agg", "Apps")
        val item2Size = 1L * 1024 * 1024 * 1024  // 1 GiB — fits comfortably
        val item2Meta = ItemMeta(2, ItemKind.APK, item2Size, "b".repeat(64), "Fits", "Apps")

        val frames = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.APK, overAggregate, 8),
            ProtocolMessage.ItemEnd(1, item1Meta.sha256),
            ProtocolMessage.ItemBegin(2, ItemKind.APK, item2Size, 8),
            ProtocolMessage.ItemEnd(2, item2Meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1, 2), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = apkReceiver(maxBytesByKind = mapOf(ItemKind.APK to perItemCap))
            .run(channel, mapOf(1 to item1Meta, 2 to item2Meta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }

        // Item 1: busts aggregate (8 GiB + 1 > 8 GiB ceiling) → OVERSIZE.
        assertThat(results.first { it.itemId == 1 }.status).isEqualTo(ItemStatus.OVERSIZE)
        assertThat(results.first { it.itemId == 1 }.detail).contains("aggregate")
        // Item 2: the rejected item 1 must NOT have entered acceptedBytes (budget still 0), so item 2
        // (1 GiB) is well under the ceiling and clears the aggregate gate. With no ItemData frames it
        // reaches verifyStaged with an empty digest → HASH_MISMATCH, the EXACT status a gate-passing,
        // no-bytes-sent item reaches; asserting it (not isNotEqualTo(OVERSIZE)) proves the gate passed.
        assertThat(results.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.HASH_MISMATCH)
    }

    @Test
    fun `aggregate ceiling fence-post — exactly-filling item accepted, one-byte-over item refused`() = runTest {
        // Two-item batch. Item 1 consumes (MAX_APK_TOTAL_BYTES - 1) of the budget.
        // Item 2a (sibling run): size = 1 → total = MAX_APK_TOTAL_BYTES exactly → accepted.
        // Item 2b (sibling run): size = 2 → total = MAX_APK_TOTAL_BYTES + 1 → OVERSIZE.
        val perItemCap = ApkContainerValidation.MAX_APK_TOTAL_BYTES  // cap high enough not to interfere
        val firstSize = ApkContainerValidation.MAX_APK_TOTAL_BYTES - 1L
        val item1Meta = ItemMeta(1, ItemKind.APK, firstSize, "a".repeat(64), "App A", "Apps")

        // Case A: second item exactly closes the budget (size = 1) → accepted.
        val exactMeta = ItemMeta(2, ItemKind.APK, 1L, "b".repeat(64), "Exact", "Apps")
        val framesA = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.APK, firstSize, 8),
            ProtocolMessage.ItemEnd(1, item1Meta.sha256),
            ProtocolMessage.ItemBegin(2, ItemKind.APK, 1L, 8),
            ProtocolMessage.ItemEnd(2, exactMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1, 2), "done"),
        )
        val resultsA = apkReceiver(maxBytesByKind = mapOf(ItemKind.APK to perItemCap))
            .run(ScriptedChannel(*framesA.toTypedArray()), mapOf(1 to item1Meta, 2 to exactMeta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }
        // Item 2 exactly closed the budget → it cleared the aggregate gate. With no ItemData frames it
        // reaches verifyStaged with an empty digest, so its EXACT terminal status is HASH_MISMATCH —
        // asserting that (not the looser isNotEqualTo(OVERSIZE)) proves it traversed the aggregate gate.
        assertThat(resultsA.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.HASH_MISMATCH)

        // Case B: second item is one byte over (size = 2) → OVERSIZE.
        val overMeta = ItemMeta(2, ItemKind.APK, 2L, "c".repeat(64), "Over", "Apps")
        val framesB = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.APK, firstSize, 8),
            ProtocolMessage.ItemEnd(1, item1Meta.sha256),
            ProtocolMessage.ItemBegin(2, ItemKind.APK, 2L, 8),
            ProtocolMessage.ItemEnd(2, overMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1, 2), "done"),
        )
        val resultsB = apkReceiver(maxBytesByKind = mapOf(ItemKind.APK to perItemCap))
            .run(ScriptedChannel(*framesB.toTypedArray()), mapOf(1 to item1Meta, 2 to overMeta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }
        assertThat(resultsB.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.OVERSIZE)
        assertThat(resultsB.first { it.itemId == 2 }.detail).contains("aggregate")
    }

    @Test
    fun `per-item APK cap exact boundary — MAX_APK_ITEM_BYTES accepted, MAX plus one refused`() = runTest {
        val cap = ApkContainerValidation.MAX_APK_ITEM_BYTES

        // Exactly at cap: cleared the per-item gate. With no ItemData frames it reaches verifyStaged
        // with an empty digest, so it terminates at HASH_MISMATCH — the EXACT status a gate-passing,
        // no-bytes-sent item reaches. Asserting that exact status proves it traversed the cap gate
        // (vs. a looser isNotEqualTo(OVERSIZE) that would also green for SKIPPED/UNKNOWN_KIND).
        val atCapMeta = ItemMeta(1, ItemKind.APK, cap, "a".repeat(64), "At cap", "Apps")
        val framesAt = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.APK, cap, 8),
            ProtocolMessage.ItemEnd(1, atCapMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val resultsAt = apkReceiver()
            .run(ScriptedChannel(*framesAt.toTypedArray()), mapOf(1 to atCapMeta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }
        assertThat(resultsAt.single().status).isEqualTo(ItemStatus.HASH_MISMATCH)

        // One byte over cap: OVERSIZE before staging.
        val overCapMeta = ItemMeta(1, ItemKind.APK, cap + 1L, "b".repeat(64), "Over cap", "Apps")
        val framesOver = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.APK, cap + 1L, 8),
            ProtocolMessage.ItemEnd(1, overCapMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val resultsOver = apkReceiver()
            .run(ScriptedChannel(*framesOver.toTypedArray()), mapOf(1 to overCapMeta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }
        assertThat(resultsOver.single().status).isEqualTo(ItemStatus.OVERSIZE)
    }

    @Test
    fun `large non-APK item interleaved between APK items never enters the APK aggregate`() = runTest {
        // A SETTINGS item with a declared size that WOULD breach the APK aggregate if wrongly counted
        // is interleaved between two accepted APK items. The APK item after the non-APK item must
        // still clear the aggregate — proving non-APK sizes never enter acceptedBytes.
        // Non-APK item is small enough for the 64 MiB Tier-0 default; we fake a huge declared size
        // by making the non-APK item small in practice but verifying the APK budget is unaffected.
        // Use a per-item APK cap large enough for 3 GiB items; aggregate ceiling is the real 8 GiB.
        // Per-item cap 6 GiB accommodates apk1 (3 GiB) and apk3 (5 GiB) without firing the per-item
        // gate, isolating the aggregate gate as the boundary under test. Total APK declared = 3+5 = 8 GiB
        // = MAX_APK_TOTAL_BYTES exactly, so the aggregate gate just passes — proving non-APK sizes are
        // never counted (if they were, the 8 GiB budget would appear exhausted and apk3 would be refused).
        val perItemCap = 6L * 1024 * 1024 * 1024
        val apk1Size = 3L * 1024 * 1024 * 1024       // 3 GiB — accepted; APK budget = 3 GiB
        // Non-APK item is small (under 64 MiB Tier-0 default). If its size were wrongly counted
        // toward the APK budget, the follow-on apk3 (which exactly fills the remaining 5 GiB) would
        // be wrongly refused — that's what this test rules out.
        val nonApkBytes = "settings-data".toByteArray()
        val nonApkMeta = ItemMeta(2, ItemKind.SETTINGS, nonApkBytes.size.toLong(), sha256(nonApkBytes), "Settings", "System")
        val apk3Size = 5L * 1024 * 1024 * 1024       // 5 GiB — fits the remaining 5 GiB APK budget

        val apk1Meta = ItemMeta(1, ItemKind.APK, apk1Size, "a".repeat(64), "App A", "Apps")
        val apk3Meta = ItemMeta(3, ItemKind.APK, apk3Size, "c".repeat(64), "App C", "Apps")

        val frames = listOf(
            // apk1: size declared, no bytes sent — aggregate gate is what we assert.
            ProtocolMessage.ItemBegin(1, ItemKind.APK, apk1Size, 8),
            ProtocolMessage.ItemEnd(1, apk1Meta.sha256),
        ) + itemFrames(nonApkMeta, nonApkBytes) + listOf(
            // apk3: size declared, no bytes sent — passes aggregate (3+5=8 GiB = ceiling exactly).
            ProtocolMessage.ItemBegin(3, ItemKind.APK, apk3Size, 8),
            ProtocolMessage.ItemEnd(3, apk3Meta.sha256),
            ProtocolMessage.BatchEnd(listOf(1, 2, 3), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = apkReceiver(maxBytesByKind = mapOf(ItemKind.APK to perItemCap))
            .run(channel, mapOf(1 to apk1Meta, 2 to nonApkMeta, 3 to apk3Meta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }

        // Non-APK item applied OK (real bytes + hash were materialized).
        assertThat(results.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.OK)
        // APK items are not OVERSIZE — the non-APK item was never counted in the APK budget,
        // so both apk1 (3 GiB) and apk3 (5 GiB) clear the 8 GiB aggregate ceiling.
        // Each fails later on hash (no APK bytes were sent), NOT on OVERSIZE.
        assertThat(results.first { it.itemId == 1 }.status).isNotEqualTo(ItemStatus.OVERSIZE)
        assertThat(results.first { it.itemId == 3 }.status).isNotEqualTo(ItemStatus.OVERSIZE)
    }

    // --- Mid-stream disk fault (ENOSPC) + default-seam + negative-size arm coverage (2026-06-20) ---

    @Test
    fun `a mid-stream staging-write fault is a per-item WRITE_ERROR and does not abort the batch`() = runTest {
        // The one disk-pressure path AC-16 can't pre-check: free space looked sufficient, but the
        // staging write fails mid-stream (e.g. ENOSPC). It must become a per-item WRITE_ERROR + drain,
        // NOT a batch abort — the FOLLOWING item must still stage, verify, and apply. The openSink seam
        // throws ONLY for the first staged file; the second item gets a real sink.
        val blob1 = "FIRST-ITEM-BYTES".toByteArray()
        val item1 = ItemMeta(1, ItemKind.CONTACTS_VCF, blob1.size.toLong(), sha256(blob1), "First", "People")
        val blob2 = "SECOND-ITEM".toByteArray()
        val item2 = ItemMeta(2, ItemKind.CONTACTS_VCF, blob2.size.toLong(), sha256(blob2), "Second", "People")
        val frames = itemFrames(item1, blob1) + itemFrames(item2, blob2) +
            ProtocolMessage.BatchEnd(listOf(1, 2), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())
        val applied = mutableListOf<ByteArray>()

        val results = ItemStreamReceiver(
            tmp.newFolder(),
            // Fail the write for item 1's staged file only; item 2 gets a real, working sink.
            openSink = { file ->
                if (file.name == "stage-1.bin") FailingSink else file.outputStream()
            },
        ).run(channel, mapOf(1 to item1, 2 to item2), { _, src ->
            applied += src.readBytes()
            ApplyOutcome(ItemStatus.OK)
        }) { }

        // Item 1: the staging write threw → WRITE_ERROR, drained, never applied.
        assertThat(results.first { it.itemId == 1 }.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(results.first { it.itemId == 1 }.detail).contains("staging write failed")
        // Item 2: the batch was NOT aborted — it stages, verifies, and applies byte-exact.
        assertThat(results.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.OK)
        assertThat(applied.single()).isEqualTo(blob2)
    }

    @Test
    fun `the production default freeSpace seam admits an item against a real staging dir with ample space`() = runTest {
        // Every other APK test injects a fake freeSpace; this one constructs the receiver WITHOUT the
        // override so the production default arg (it.usableSpace on the real temp staging volume) is
        // exercised. The temp dir has ample space, so the APK free-space gate must admit the item.
        val blob = "APK-ON-REAL-DISK".toByteArray()
        val apkMeta = ItemMeta(1, ItemKind.APK, blob.size.toLong(), sha256(blob), "Some app", "Apps")
        val frames = itemFrames(apkMeta, blob) + ProtocolMessage.BatchEnd(listOf(1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())
        val applied = mutableListOf<ByteArray>()

        val results = ItemStreamReceiver(
            tmp.newFolder(),
            maxBytesByKind = mapOf(ItemKind.APK to ApkContainerValidation.MAX_APK_ITEM_BYTES),
            // No freeSpace override — the real usableSpace default decides, and it is ample here.
        ).run(channel, mapOf(1 to apkMeta), { _, src ->
            applied += src.readBytes()
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(results.single().status).isEqualTo(ItemStatus.OK)
        assertThat(applied.single()).isEqualTo(blob) // byte-exact through the real staging file
    }

    @Test
    fun `a negative declared size on a NON-APK kind is refused OVERSIZE before the meta and kind arms`() = runTest {
        // The negative-size floor runs for ALL kinds and precedes the meta-null / kind / size arms.
        // A requested SETTINGS item with size = -1 must be refused OVERSIZE("negative declared size"),
        // proving the floor is not APK-scoped — it guards every numeric gate downstream.
        val negMeta = ItemMeta(1, ItemKind.SETTINGS, -1L, "a".repeat(64), "Evil settings", "System")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, ItemKind.SETTINGS, -1L, 8),
            ProtocolMessage.ItemEnd(1, negMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(1), "done"),
        )
        val channel = ScriptedChannel(*frames.toTypedArray())
        var applyCalled = false

        val results = receiver().run(channel, mapOf(1 to negMeta), { _, _ ->
            applyCalled = true
            ApplyOutcome(ItemStatus.OK)
        }) { }

        assertThat(applyCalled).isFalse()
        assertThat(results.single().status).isEqualTo(ItemStatus.OVERSIZE)
        assertThat(results.single().detail).contains("negative declared size")
    }

    @Test
    fun `a negative declared size on an UNREQUESTED item is OVERSIZE, not SKIPPED — the floor precedes the meta-null arm`() = runTest {
        // An unrequested item (meta == null) with size = -1: the negative-size floor must fire FIRST as
        // OVERSIZE, NOT the meta-null SKIPPED arm — locking the load-bearing arm ordering (a negative
        // size must never reach a downstream numeric gate, even for an unknown item id).
        val frames = listOf(
            ProtocolMessage.ItemBegin(7, ItemKind.SETTINGS, -1L, 8), // id 7 not in expected
            ProtocolMessage.ItemEnd(7, "0".repeat(64)),
        ) + itemFrames(meta, payload) + ProtocolMessage.BatchEnd(listOf(7, 1), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta), { _, _ -> ApplyOutcome(ItemStatus.OK) }) { }

        val rogue = results.first { it.itemId == 7 }
        assertThat(rogue.status).isEqualTo(ItemStatus.OVERSIZE)
        assertThat(rogue.detail).contains("negative declared size")
        // The legit requested item is unharmed.
        assertThat(results.first { it.itemId == 1 }.status).isEqualTo(ItemStatus.OK)
    }

    @Test
    fun `a DATA chunk with a non-monotonic seq is WRITE_ERROR (stream out of order) and the batch continues`() = runTest {
        // A buggy/hostile sender skips a sequence number mid-item. The receiver must reject it SPECIFICALLY
        // as a frame-desync (WRITE_ERROR "stream out of order"), not let a wrong byte set slide through to a
        // downstream HASH_MISMATCH — and must DRAIN to stay frame-synchronized so the next item still
        // applies. (Pins the seq guard the bug-hunt flagged as the one untested receiver safety arm.)
        val meta2 = ItemMeta(2, ItemKind.CALL_LOG, 5L, sha256("calls".toByteArray()), "Calls", "History")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, "AAAAAAAA".toByteArray()),
            ProtocolMessage.ItemData(1, 2, "BBBBBBBB".toByteArray()), // seq jumps 0 -> 2 (skips 1)
            ProtocolMessage.ItemEnd(1, meta.sha256),
        ) + itemFrames(meta2, "calls".toByteArray()) + ProtocolMessage.BatchEnd(listOf(1, 2), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())
        var appliedItem1 = false

        val results = receiver().run(channel, mapOf(1 to meta, 2 to meta2), { m, _ ->
            if (m.itemId == 1) appliedItem1 = true
            ApplyOutcome(ItemStatus.OK, "ok ${m.itemId}")
        }) { }

        val item1 = results.first { it.itemId == 1 }
        assertThat(item1.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(item1.detail).contains("out of order")
        assertThat(appliedItem1).isFalse() // the desynced item never reached apply
        // Drained, not aborted: the next valid item still applies (frame sync preserved).
        assertThat(results.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.OK)
    }

    @Test
    fun `a DATA chunk whose itemId disagrees mid-item is WRITE_ERROR (stream out of order) and the batch continues`() = runTest {
        // A DATA frame tagged with a different itemId than the open ItemBegin — the receiver must never
        // misattribute bytes across items; it rejects the desync as WRITE_ERROR and DRAINS so the next
        // item still applies. The chunk uses seq=1 (== nextSeq), isolating the itemId arm of the guard.
        val meta2 = ItemMeta(2, ItemKind.CALL_LOG, 5L, sha256("calls".toByteArray()), "Calls", "History")
        val frames = listOf(
            ProtocolMessage.ItemBegin(1, meta.kind, meta.size, 8),
            ProtocolMessage.ItemData(1, 0, "AAAAAAAA".toByteArray()),
            ProtocolMessage.ItemData(2, 1, "BBBBBBBB".toByteArray()), // itemId 2 inside item 1's stream
            ProtocolMessage.ItemEnd(1, meta.sha256),
        ) + itemFrames(meta2, "calls".toByteArray()) + ProtocolMessage.BatchEnd(listOf(1, 2), "done")
        val channel = ScriptedChannel(*frames.toTypedArray())

        val results = receiver().run(channel, mapOf(1 to meta, 2 to meta2), { m, _ ->
            ApplyOutcome(ItemStatus.OK, "ok ${m.itemId}")
        }) { }

        val item1 = results.first { it.itemId == 1 }
        assertThat(item1.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(item1.detail).contains("out of order")
        // Drained, not aborted: the next valid item still applies (frame sync preserved).
        assertThat(results.first { it.itemId == 2 }.status).isEqualTo(ItemStatus.OK)
    }
}

/** A sink whose every write throws [IOException], simulating a mid-stream disk fault (ENOSPC). */
private object FailingSink : OutputStream() {
    override fun write(b: Int) = throw IOException("simulated ENOSPC")
    override fun write(b: ByteArray) = throw IOException("simulated ENOSPC")
    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("simulated ENOSPC")
}
