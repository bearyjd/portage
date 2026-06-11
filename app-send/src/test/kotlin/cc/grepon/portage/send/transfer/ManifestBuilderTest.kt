/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send.transfer

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.providers.ExportProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.OutputStream

private class FakeExport(
    override val kind: ItemKind,
    override val displayName: String,
    override val group: String,
    private val payload: ByteArray?,           // null = unavailable
    private val throwOnAvailable: Boolean = false,
    private val throwOnExport: Boolean = false,
) : ExportProvider {
    override suspend fun available(): Boolean {
        if (throwOnAvailable) throw IllegalStateException("boom")
        return payload != null
    }

    override suspend fun exportTo(sink: OutputStream) {
        if (throwOnExport) throw IllegalStateException("mid-export boom")
        sink.write(payload ?: ByteArray(0))
    }
}

class ManifestBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `stages available providers with real sizes and hashes`() = runTest {
        val contacts = FakeExport(ItemKind.CONTACTS_VCF, "Contacts", "People", "vcard-bytes".toByteArray())
        val calls = FakeExport(ItemKind.CALL_LOG, "Call history", "History", "call-bytes!".toByteArray())

        val staged = ManifestBuilder(listOf(contacts, calls), tmp.root, "old phone").build()

        assertThat(staged.manifest.senderName).isEqualTo("old phone")
        assertThat(staged.manifest.items).hasSize(2)
        assertThat(staged.items).hasSize(2)

        val first = staged.items[0]
        assertThat(first.meta.kind).isEqualTo(ItemKind.CONTACTS_VCF)
        assertThat(first.meta.size).isEqualTo("vcard-bytes".length.toLong())
        assertThat(first.file.readBytes()).isEqualTo("vcard-bytes".toByteArray())
        assertThat(first.meta.sha256)
            .isEqualTo(sha256Hex(ByteArrayInputStream("vcard-bytes".toByteArray())))

        // Item ids are unique and the manifest mirrors the staged list.
        assertThat(staged.manifest.items.map { it.itemId }.toSet()).hasSize(2)
        assertThat(staged.manifest.totalBytes)
            .isEqualTo(staged.manifest.items.sumOf { it.size })
    }

    @Test
    fun `unavailable providers are excluded`() = runTest {
        val present = FakeExport(ItemKind.CONTACTS_VCF, "Contacts", "People", "x".toByteArray())
        val absent = FakeExport(ItemKind.SMS, "Texts", "History", payload = null)

        val staged = ManifestBuilder(listOf(present, absent), tmp.root, "s").build()

        assertThat(staged.manifest.items.map { it.kind }).containsExactly(ItemKind.CONTACTS_VCF)
    }

    @Test
    fun `a provider whose available() throws is excluded, not fatal`() = runTest {
        val bad = FakeExport(ItemKind.CALENDAR_ICS, "Calendar", "Schedule", "y".toByteArray(), throwOnAvailable = true)
        val good = FakeExport(ItemKind.CONTACTS_VCF, "Contacts", "People", "x".toByteArray())

        val staged = ManifestBuilder(listOf(bad, good), tmp.root, "s").build()

        assertThat(staged.manifest.items.map { it.kind }).containsExactly(ItemKind.CONTACTS_VCF)
    }

    @Test
    fun `a provider that throws mid-export is excluded and its staging file removed`() = runTest {
        val bad = FakeExport(ItemKind.CALENDAR_ICS, "Calendar", "Schedule", "y".toByteArray(), throwOnExport = true)

        val staged = ManifestBuilder(listOf(bad), tmp.root, "s").build()

        assertThat(staged.manifest.items).isEmpty()
        assertThat(tmp.root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `an empty export is excluded — nothing to carry`() = runTest {
        val empty = FakeExport(ItemKind.CALL_LOG, "Call history", "History", ByteArray(0))

        val staged = ManifestBuilder(listOf(empty), tmp.root, "s").build()

        assertThat(staged.manifest.items).isEmpty()
    }

    @Test
    fun `cleanup removes every staged file`() = runTest {
        val provider = FakeExport(ItemKind.CONTACTS_VCF, "Contacts", "People", "x".toByteArray())
        val staged = ManifestBuilder(listOf(provider), tmp.root, "s").build()
        assertThat(staged.items[0].file.exists()).isTrue()

        staged.cleanup()

        assertThat(staged.items[0].file.exists()).isFalse()
    }
}
