/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.relay

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.Tier
import cc.grepon.portage.providers.inventory.AppRecord
import cc.grepon.portage.providers.inventory.InventorySource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Opaque app-encrypted fixture: bytes the courier never interprets. The PLAINTEXT_MARKER simulates
 * a secret a hostile parse path might leak; the scope guards assert it never appears in a log/detail
 * line or reaches any decode hook.
 */
private const val PLAINTEXT_MARKER = "TOP-SECRET-SIGNAL-PLAINTEXT-DO-NOT-LEAK"
private val OPAQUE_BLOB = ("SQLite format 3 " + PLAINTEXT_MARKER + "...ciphertext...").toByteArray()

/** Control characters used to prove sanitization strips them from advisory display fields. */
private const val NL = "\n"
private const val TAB = "\t"

/**
 * Hand-written fake of the re-link sink (mirrors the inventory/Bluetooth list-producing seams). The
 * apply path NEVER imports into the target app — it records that a re-link was OFFERED, exactly the
 * "user works the checklist" shape of AppInventoryApplyProvider. Captures the prompts so a test can
 * assert no app write happened and no opaque content leaked into the prompt.
 */
private class FakeRelaySink {
    val prompts = mutableListOf<RelayRestorePrompt>()
    fun onPrompt(prompt: RelayRestorePrompt) { prompts += prompt }
}

/** Fake inventory seam reporting a fixed installed-package set for the detection tests. */
private class FakeInventorySource(private val packages: Set<String>) : InventorySource {
    override fun installedUserApps(): List<AppRecord> = emptyList()
    override fun installedPackageNames(): Set<String> = packages
}

class AppBackupRelayProvidersTest {

    // ---- model + wire shape ----

    @Test
    fun `APP_BACKUP_RELAY kind is registered as a tier-0 wire kind`() {
        assertThat(ItemKind.APP_BACKUP_RELAY.wire).isEqualTo("app.backup.relay")
        assertThat(ItemKind.APP_BACKUP_RELAY.tier).isEqualTo(Tier.TIER0)
    }

    @Test
    fun `relay app maps to a canonical target package`() {
        assertThat(RelayApp.SIGNAL.canonicalPackage).isEqualTo("org.thoughtcrime.securesms")
        assertThat(RelayApp.AEGIS.canonicalPackage).isEqualTo("com.beemdevelopment.aegis")
        // MOLLY carries two packages (app + foss); OTHER has none (generic relay).
        assertThat(RelayApp.MOLLY.canonicalPackage).isNull()
        assertThat(RelayApp.OTHER.canonicalPackage).isNull()
    }

    @Test
    fun `package detection maps known packages to a RelayApp and unknown to OTHER`() {
        assertThat(RelayApp.forPackage("org.thoughtcrime.securesms")).isEqualTo(RelayApp.SIGNAL)
        assertThat(RelayApp.forPackage("im.molly.app")).isEqualTo(RelayApp.MOLLY)
        assertThat(RelayApp.forPackage("im.molly.foss")).isEqualTo(RelayApp.MOLLY)
        assertThat(RelayApp.forPackage("com.beemdevelopment.aegis")).isEqualTo(RelayApp.AEGIS)
        assertThat(RelayApp.forPackage("com.unknown.app")).isEqualTo(RelayApp.OTHER)
    }

    @Test
    fun `detection suggests installed relay apps via the inventory seam`() {
        val source = FakeInventorySource(
            setOf("org.thoughtcrime.securesms", "com.beemdevelopment.aegis", "com.unrelated.app"),
        )
        val candidates = RelayAppDetector.detect(source)
        assertThat(candidates.map { it.app }).containsExactly(RelayApp.SIGNAL, RelayApp.AEGIS)
        assertThat(candidates.map { it.targetPackage })
            .containsExactly("org.thoughtcrime.securesms", "com.beemdevelopment.aegis")
    }

    @Test
    fun `detection reports the installed Molly variant by package`() {
        val source = FakeInventorySource(setOf("im.molly.foss"))
        val candidates = RelayAppDetector.detect(source)
        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().app).isEqualTo(RelayApp.MOLLY)
        assertThat(candidates.single().targetPackage).isEqualTo("im.molly.foss")
    }

    @Test
    fun `detection is empty when no relay-capable app is installed`() {
        assertThat(RelayAppDetector.detect(FakeInventorySource(setOf("com.unrelated.app")))).isEmpty()
    }

    @Test
    fun `header round-trips through the codec splitting header from opaque bytes`() {
        val header = RelayHeader(
            app = RelayApp.SIGNAL,
            targetPackage = "org.thoughtcrime.securesms",
            originalName = "signal-2026-06-13.backup",
            restoreNote = "Open Signal and restore from backup; bring your 30-digit passphrase.",
            byteLength = OPAQUE_BLOB.size.toLong(),
        )
        val framed = ByteArrayOutputStream().use { out ->
            RelayCodec.writeTo(out, header, OPAQUE_BLOB)
            out.toByteArray()
        }
        val decoded = RelayCodec.readFrom(ByteArrayInputStream(framed))

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.header).isEqualTo(header)
        assertThat(decoded.opaqueBytes).isEqualTo(OPAQUE_BLOB)
    }

    @Test
    fun `codec returns null on a payload with no header line`() {
        assertThat(RelayCodec.readFrom(ByteArrayInputStream(OPAQUE_BLOB))).isNull()
    }

    @Test
    fun `codec returns null when declared byteLength does not match the opaque bytes`() {
        val header = RelayHeader(
            app = RelayApp.OTHER,
            targetPackage = "com.example.vault",
            originalName = "export.bin",
            restoreNote = "Import this in the app.",
            byteLength = OPAQUE_BLOB.size.toLong() + 999L, // lies about the length
        )
        val out = ByteArrayOutputStream()
        RelayCodec.writeTo(out, header, OPAQUE_BLOB)
        assertThat(RelayCodec.readFrom(ByteArrayInputStream(out.toByteArray()))).isNull()
    }

    @Test
    fun `a generic OTHER relay item builds and round-trips`() {
        val header = RelayHeader(
            app = RelayApp.OTHER,
            targetPackage = "com.example.vault",
            originalName = "vault.bin",
            restoreNote = "Import this encrypted file in the app.",
            byteLength = OPAQUE_BLOB.size.toLong(),
        )
        val out = ByteArrayOutputStream()
        RelayCodec.writeTo(out, header, OPAQUE_BLOB)
        val decoded = RelayCodec.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(decoded!!.header.app).isEqualTo(RelayApp.OTHER)
    }

    // ---- header validation (derive-never-trust the advisory fields) ----

    @Test
    fun `a well-formed header validates`() {
        val header = RelayHeader(
            RelayApp.SIGNAL, "org.thoughtcrime.securesms", "signal.backup", "Restore in Signal.",
            OPAQUE_BLOB.size.toLong(),
        )
        assertThat(header.sanitizedOrNull()).isNotNull()
    }

    @Test
    fun `a header whose targetPackage is not a plausible package name is rejected`() {
        val header = RelayHeader(
            RelayApp.OTHER, "not a package", "x.bin", "note", OPAQUE_BLOB.size.toLong(),
        )
        assertThat(header.sanitizedOrNull()).isNull()
    }

    @Test
    fun `a known RelayApp whose targetPackage disagrees with its canonical packages is rejected`() {
        // A hostile sender claims SIGNAL but points the package at an arbitrary app — the enum gate
        // (not the free string) decides the target, so a mismatch is dropped.
        val header = RelayHeader(
            RelayApp.SIGNAL, "com.evil.redirect", "x.backup", "note", OPAQUE_BLOB.size.toLong(),
        )
        assertThat(header.sanitizedOrNull()).isNull()
    }

    @Test
    fun `an over-long restore note is rejected`() {
        val header = RelayHeader(
            RelayApp.OTHER, "com.example.vault", "x.bin",
            restoreNote = "n".repeat(RelayHeader.MAX_NOTE_LENGTH + 1),
            byteLength = OPAQUE_BLOB.size.toLong(),
        )
        assertThat(header.sanitizedOrNull()).isNull()
    }

    @Test
    fun `a negative byteLength is rejected`() {
        val header = RelayHeader(RelayApp.OTHER, "com.example.vault", "x.bin", "note", byteLength = -1L)
        assertThat(header.sanitizedOrNull()).isNull()
    }

    @Test
    fun `sanitization strips control characters from the note and original name`() {
        val header = RelayHeader(
            RelayApp.OTHER, "com.example.vault",
            originalName = "evil" + NL + ".bin",
            restoreNote = "line1" + NL + "line2" + TAB + "tab",
            byteLength = OPAQUE_BLOB.size.toLong(),
        )
        val sanitized = header.sanitizedOrNull()
        assertThat(sanitized).isNotNull()
        // Control chars (newlines/tabs) are stripped so they can't smuggle into a UI label; ordinary
        // characters (incl. spaces in a real filename) are preserved.
        assertThat(sanitized!!.originalName).doesNotContain(NL)
        assertThat(sanitized.restoreNote).doesNotContain(NL)
        assertThat(sanitized.restoreNote).doesNotContain(TAB)
    }

    // ---- export (sender stages a user-picked opaque file) ----

    @Test
    fun `the export provider stages a user-picked opaque file behind a header`() = runTest {
        val provider = AppBackupRelayExportProvider(
            app = RelayApp.SIGNAL,
            targetPackage = "org.thoughtcrime.securesms",
            originalName = "signal-2026.backup",
            restoreNote = "Restore in Signal; bring your passphrase.",
            openPickedFile = { ByteArrayInputStream(OPAQUE_BLOB) },
            pickedFileLength = OPAQUE_BLOB.size.toLong(),
        )
        assertThat(provider.kind).isEqualTo(ItemKind.APP_BACKUP_RELAY)
        assertThat(provider.available()).isTrue()

        val out = ByteArrayOutputStream()
        provider.exportTo(out)
        val decoded = RelayCodec.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.header.app).isEqualTo(RelayApp.SIGNAL)
        assertThat(decoded.opaqueBytes).isEqualTo(OPAQUE_BLOB) // byte-exact, never interpreted
    }

    @Test
    fun `the export provider is unavailable when the picked file is empty`() = runTest {
        val provider = AppBackupRelayExportProvider(
            app = RelayApp.OTHER,
            targetPackage = "com.example.vault",
            originalName = "x.bin",
            restoreNote = "note",
            openPickedFile = { ByteArrayInputStream(ByteArray(0)) },
            pickedFileLength = 0L,
        )
        assertThat(provider.available()).isFalse()
    }

    // ---- apply (receiver surfaces a re-link prompt; NEVER imports, NEVER interprets) ----

    private fun frameOf(
        app: RelayApp = RelayApp.SIGNAL,
        targetPackage: String = "org.thoughtcrime.securesms",
        originalName: String = "signal.backup",
        restoreNote: String = "Restore in Signal.",
        bytes: ByteArray = OPAQUE_BLOB,
        byteLength: Long = bytes.size.toLong(),
    ): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        RelayCodec.writeTo(out, RelayHeader(app, targetPackage, originalName, restoreNote, byteLength), bytes)
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `apply surfaces a re-link prompt and stages the opaque file without importing it`() = runTest {
        val sink = FakeRelaySink()
        val staged = mutableListOf<ByteArray>()
        val outcome = AppBackupRelayApplyProvider(
            onPrompt = sink::onPrompt,
            handoff = { _, bytes -> staged += bytes; true },
        ).apply(frameOf())

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(sink.prompts).hasSize(1)
        assertThat(sink.prompts.single().app).isEqualTo(RelayApp.SIGNAL)
        // The staged bytes are byte-exact opaque bytes — the courier hands them off, never imports.
        assertThat(staged.single()).isEqualTo(OPAQUE_BLOB)
    }

    @Test
    fun `apply derives the target package from the typed enum, ignoring a hostile advisory package`() = runTest {
        val sink = FakeRelaySink()
        // The header claims SIGNAL but tries to redirect targetPackage to an arbitrary app. The
        // sanitizer rejects the mismatched advisory package, so the item is refused (no prompt).
        val outcome = AppBackupRelayApplyProvider(
            onPrompt = sink::onPrompt,
            handoff = { _, _ -> true },
        ).apply(frameOf(app = RelayApp.SIGNAL, targetPackage = "com.evil.redirect"))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(sink.prompts).isEmpty()
    }

    @Test
    fun `apply rejects an over-long restore note without surfacing a prompt`() = runTest {
        val sink = FakeRelaySink()
        val outcome = AppBackupRelayApplyProvider(
            onPrompt = sink::onPrompt,
            handoff = { _, _ -> true },
        ).apply(
            frameOf(
                app = RelayApp.OTHER,
                targetPackage = "com.example.vault",
                restoreNote = "n".repeat(RelayHeader.MAX_NOTE_LENGTH + 1),
            ),
        )

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(sink.prompts).isEmpty()
    }

    @Test
    fun `apply reports WRITE_ERROR on an unreadable header and never stages`() = runTest {
        val sink = FakeRelaySink()
        var handoffCalled = false
        val outcome = AppBackupRelayApplyProvider(
            onPrompt = sink::onPrompt,
            handoff = { _, _ -> handoffCalled = true; true },
        ).apply(ByteArrayInputStream(OPAQUE_BLOB)) // no header line

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(handoffCalled).isFalse()
        assertThat(sink.prompts).isEmpty()
    }

    @Test
    fun `apply reports WRITE_ERROR when the handoff fails, with no opaque content in the detail`() = runTest {
        val sink = FakeRelaySink()
        val outcome = AppBackupRelayApplyProvider(
            onPrompt = sink::onPrompt,
            handoff = { _, _ -> false }, // staging/handoff refused
        ).apply(frameOf())

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(outcome.detail ?: "").doesNotContain(PLAINTEXT_MARKER)
    }

    // ---- scope guard: the opaque blob is NEVER parsed, decoded, or logged ----

    @Test
    fun `the apply path never interprets the blob — no detail or prompt carries the plaintext marker`() = runTest {
        val sink = FakeRelaySink()
        val outcome = AppBackupRelayApplyProvider(
            onPrompt = sink::onPrompt,
            handoff = { _, _ -> true },
        ).apply(frameOf())

        // The outcome detail (which rides BATCH_ACK / the done screen) must never carry the secret.
        assertThat(outcome.detail ?: "").doesNotContain(PLAINTEXT_MARKER)
        // The user-facing prompt carries only the app id + note + display name — never the bytes.
        val prompt = sink.prompts.single()
        assertThat(prompt.restoreNote).doesNotContain(PLAINTEXT_MARKER)
        assertThat(prompt.originalName).doesNotContain(PLAINTEXT_MARKER)
        assertThat(prompt.toString()).doesNotContain(PLAINTEXT_MARKER)
    }
}
