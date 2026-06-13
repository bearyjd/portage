/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send.relay

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.providers.relay.RelayApp
import cc.grepon.portage.providers.relay.RelayCodec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Opaque app-encrypted fixture — the courier never interprets these bytes. */
private val OPAQUE_BLOB = ("SQLite format 3 ...signal-ciphertext...").toByteArray()

private fun signalPick(
    pickId: Long = 1L,
    bytes: ByteArray = OPAQUE_BLOB,
    note: String = RelayRestoreNotes.defaultFor(RelayApp.SIGNAL),
) = RelayFile(
    pickId = pickId,
    app = RelayApp.SIGNAL,
    targetPackage = "org.thoughtcrime.securesms",
    originalName = "signal-2026-06-13.backup",
    restoreNote = note,
    byteLength = bytes.size.toLong(),
    openStream = { ByteArrayInputStream(bytes) },
)

class RelayPicksTest {

    @Test
    fun `a picked file builds an APP_BACKUP_RELAY export provider with the right header`() = runTest {
        val provider = relayExportProviders(listOf(signalPick())).single()

        assertThat(provider.kind).isEqualTo(ItemKind.APP_BACKUP_RELAY)
        assertThat(provider.available()).isTrue()

        val out = ByteArrayOutputStream()
        provider.exportTo(out)
        val decoded = RelayCodec.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.header.app).isEqualTo(RelayApp.SIGNAL)
        assertThat(decoded.header.targetPackage).isEqualTo("org.thoughtcrime.securesms")
        assertThat(decoded.header.byteLength).isEqualTo(OPAQUE_BLOB.size.toLong())
        // The default restore note is carried — the receiver's blank-note gate is satisfied.
        assertThat(decoded.header.restoreNote).contains("passphrase")
        // The opaque bytes ride byte-exact — never interpreted.
        assertThat(decoded.opaqueBytes).isEqualTo(OPAQUE_BLOB)
    }

    @Test
    fun `the per-app default note names the target app and the passphrase`() {
        assertThat(RelayRestoreNotes.defaultFor(RelayApp.SIGNAL)).contains("Signal")
        assertThat(RelayRestoreNotes.defaultFor(RelayApp.SIGNAL)).contains("passphrase")
        assertThat(RelayRestoreNotes.defaultFor(RelayApp.AEGIS)).contains("Aegis")
        assertThat(RelayRestoreNotes.defaultFor(RelayApp.MOLLY)).contains("Molly")
        // Even the generic case carries a usable reminder so the note is never blank.
        assertThat(RelayRestoreNotes.defaultFor(RelayApp.OTHER)).isNotEmpty()
    }

    @Test
    fun `multiple picked relays produce distinct provider items`() = runTest {
        val signal = signalPick(pickId = 1L, bytes = "first-backup".toByteArray())
        val aegis = RelayFile(
            pickId = 2L,
            app = RelayApp.AEGIS,
            targetPackage = "com.beemdevelopment.aegis",
            originalName = "aegis-export.json",
            restoreNote = RelayRestoreNotes.defaultFor(RelayApp.AEGIS),
            byteLength = "second-vault".toByteArray().size.toLong(),
            openStream = { ByteArrayInputStream("second-vault".toByteArray()) },
        )

        val providers = relayExportProviders(listOf(signal, aegis))
        assertThat(providers).hasSize(2)

        // Each provider stages its OWN distinct payload behind its OWN typed header.
        val firstOut = ByteArrayOutputStream().also { providers[0].exportTo(it) }
        val secondOut = ByteArrayOutputStream().also { providers[1].exportTo(it) }
        val first = RelayCodec.readFrom(ByteArrayInputStream(firstOut.toByteArray()))!!
        val second = RelayCodec.readFrom(ByteArrayInputStream(secondOut.toByteArray()))!!

        assertThat(first.header.app).isEqualTo(RelayApp.SIGNAL)
        assertThat(second.header.app).isEqualTo(RelayApp.AEGIS)
        assertThat(first.opaqueBytes).isEqualTo("first-backup".toByteArray())
        assertThat(second.opaqueBytes).isEqualTo("second-vault".toByteArray())
    }

    @Test
    fun `a pick of an empty file self-omits from the manifest`() = runTest {
        val empty = signalPick(bytes = ByteArray(0))
        val provider = relayExportProviders(listOf(empty)).single()
        // available() false ⇒ ManifestBuilder skips it, so a half-finished pick never ships.
        assertThat(provider.available()).isFalse()
    }
}
