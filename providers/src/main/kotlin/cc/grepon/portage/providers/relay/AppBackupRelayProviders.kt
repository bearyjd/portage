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
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProvider
import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.providers.inventory.InventorySource
import cc.grepon.portage.providers.wire.JsonLines
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * COURIER, NOT BACKUP (PRP-06 §2). This provider relays a file the USER exported from an app that
 * keeps its OWN encrypted backup (Signal/Molly message history, Aegis 2FA vault). portage NEVER
 * decrypts, parses, or interprets the payload — it carries OPAQUE bytes the user picked via SAF.
 * The passphrase is never handled. This is categorically NOT a `seedvault.blob`: portage does not
 * read app-internal data and never PRODUCES the backup — only a user-picked file ever enters.
 */

/**
 * Which app a relay item targets. The typed enum is the dispatch key the receiver uses to decide the
 * target package ([canonicalPackage]) — the advisory [RelayHeader.targetPackage] free string is
 * re-validated AGAINST this enum, never trusted as the destination. This is the same "derive, never
 * trust" discipline the wallpaper/settings providers apply to surfaces/namespaces. `OTHER` is the
 * generic relay (Phase 1): no canonical package, so its advisory package is accepted only as a
 * plausible package name (it cannot redirect a KNOWN app).
 *
 * [SerialName] is explicit on each constant so a future Kotlin rename cannot silently break the wire
 * format — the same decoupling [cc.grepon.portage.model.ItemKind.wire] gives for kind dispatch.
 */
@Serializable
enum class RelayApp(val canonicalPackages: Set<String>) {
    @SerialName("signal")
    SIGNAL(setOf("org.thoughtcrime.securesms")),
    @SerialName("molly")
    MOLLY(setOf("im.molly.app", "im.molly.foss")),
    @SerialName("aegis")
    AEGIS(setOf("com.beemdevelopment.aegis")),
    @SerialName("other")
    OTHER(emptySet()),
    ;

    /** The single canonical package for display/derivation, or null for MOLLY (two) and OTHER. */
    val canonicalPackage: String? get() = canonicalPackages.singleOrNull()

    companion object {
        /** Map an installed package name to its [RelayApp], or [OTHER] when not a known relay app. */
        fun forPackage(packageName: String): RelayApp =
            entries.firstOrNull { packageName in it.canonicalPackages } ?: OTHER
    }
}

/**
 * One installed relay-capable app the sender can offer to ferry a backup for (PRP-06 §3). Display +
 * the typed [app] + its canonical [targetPackage] — the sender shows these as "you can relay your
 * <app> backup" suggestions, then the USER picks the exported file via SAF. Never carries data.
 */
data class RelayCandidate(val app: RelayApp, val targetPackage: String)

/**
 * Sender-side detection: which known relay apps are installed, via the existing inventory seam
 * ([InventorySource.installedPackageNames]; needs no extra permission on GOS). Pure + testable with a
 * fake source — this is the "suggest which apps have relays" step (PRP-06 §3/§6). It only SUGGESTS;
 * the user still triggers the app's native export and picks the resulting file. MOLLY is reported per
 * installed package (app and/or foss) so the suggestion points at the right one.
 */
object RelayAppDetector {
    fun detect(source: InventorySource): List<RelayCandidate> {
        val installed = runCatching { source.installedPackageNames() }.getOrDefault(emptySet())
        return RelayApp.entries
            .filter { it != RelayApp.OTHER }
            .flatMap { app -> app.canonicalPackages.map { app to it } }
            .filter { (_, pkg) -> pkg in installed }
            .map { (app, pkg) -> RelayCandidate(app, pkg) }
    }
}

/**
 * One-line structured header that precedes the opaque app-backup bytes in a relay item (PRP-06 §5).
 * Mirrors [cc.grepon.portage.providers.wallpaper.WallpaperHeader]'s header+blob shape, with the same
 * "advisory fields are re-validated, never trusted" rule:
 *  - [app] is the typed dispatch key; the receiver derives the real target package from it.
 *  - [targetPackage] is ADVISORY: re-validated against [app]'s canonical packages (for a known app)
 *    or against a plausible-package-name regex (for [RelayApp.OTHER]) before any intent — a hostile
 *    sender cannot redirect the re-link to an arbitrary package.
 *  - [originalName] is DISPLAY-ONLY and NEVER used as a filesystem path (the receiver stages under a
 *    generated name, like every item).
 *  - [restoreNote] is a human reminder shown on the receiver; length-bounded and control-stripped.
 *  - [byteLength] is the opaque-blob length following the header; cross-checked vs streamed bytes.
 *
 * The payload that follows is OPAQUE: it is NEVER decoded, sniffed, or format-validated — that is the
 * deliberate inversion of the wallpaper image-decode gate (portage owns the wallpaper surface; it
 * owns NOTHING here and must not pretend to understand the ciphertext).
 */
@Serializable
data class RelayHeader(
    val app: RelayApp,
    val targetPackage: String,
    val originalName: String,
    val restoreNote: String,
    val byteLength: Long,
) {
    /**
     * Return a sanitized, validated copy of this header, or null to REJECT it. Validation derives
     * the target from the typed [app] enum, bounds the note/name, and refuses a negative length —
     * everything a hostile sender could smuggle through the advisory free fields is dropped here.
     */
    fun sanitizedOrNull(): RelayHeader? {
        if (byteLength < 0) return null
        val pkg = targetPackage.trim()
        if (!PACKAGE_NAME.matches(pkg)) return null
        // For a KNOWN app the advisory package MUST match one of its canonical packages — a mismatch
        // is a redirect attempt and is dropped. For OTHER, any plausible package name is accepted.
        if (app != RelayApp.OTHER && pkg !in app.canonicalPackages) return null

        val name = sanitizeText(originalName, MAX_NAME_LENGTH)
        val note = sanitizeText(restoreNote, MAX_NOTE_LENGTH) ?: return null
        if (note.isBlank()) return null
        return copy(targetPackage = pkg, originalName = name ?: "", restoreNote = note)
    }

    companion object {
        /** Restore-note display cap: enough for a clear reminder, short enough to bound a UI row. */
        const val MAX_NOTE_LENGTH = 240

        /** Original-name display cap (display-only; never a path). */
        const val MAX_NAME_LENGTH = 120

        /**
         * The Android package grammar: dot-separated `[A-Za-z0-9_]` segments, two or more. Same
         * regex the inventory deep link uses (InventoryProviders.kt) — everything it accepts is
         * URL/intent-safe by construction, so no scheme/query can hide in a validated package.
         */
        private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")

        /** Strip control characters (incl. newlines/tabs), trim, and length-cap. Null over [max]. */
        private fun sanitizeText(raw: String, max: Int): String? {
            val cleaned = raw.filter { !it.isISOControl() }.trim()
            if (cleaned.length > max) return null
            return cleaned
        }
    }
}

/**
 * Wire codec for a relay item: `JSON(header) + "\n" + <opaque app-encrypted bytes>`. The opaque
 * bytes are handled by LENGTH only — never parsed, decoded, or sniffed. As a defensive cross-check,
 * the actual byte count streamed is compared against [RelayHeader.byteLength]; a mismatch indicates a
 * truncated/corrupt frame and is rejected. Streams are NOT closed here — the staging layer owns them.
 *
 * The production apply path uses [readHeaderFrom] + [streamBlob], which NEVER materializes the full
 * blob in a ByteArray (an item can be up to 2 GiB for the relay kind). The full-materializing
 * [readFrom] is a test helper ONLY for small-blob round-trip assertions.
 */
object RelayCodec {

    private const val NEWLINE = '\n'.code.toByte()
    private const val CHUNK = 8 * 1024

    /** Safety cap on header scanning: a header larger than this is rejected as malformed. */
    private const val MAX_HEADER_BYTES = 4 * 1024

    fun writeTo(sink: OutputStream, header: RelayHeader, opaqueBytes: ByteArray) {
        sink.write(JsonLines.format.encodeToString(RelayHeader.serializer(), header).toByteArray(Charsets.UTF_8))
        sink.write(NEWLINE.toInt())
        sink.write(opaqueBytes)
        sink.flush()
    }

    /**
     * Scan [source] byte-by-byte to the first '\n', decode that line as a [RelayHeader], and return
     * it. On return the stream is positioned immediately after the '\n' — the next read yields the
     * first byte of the opaque blob. Returns null if no '\n' is found before EOF or before the
     * [MAX_HEADER_BYTES] safety cap, or if the header JSON is unparseable.
     * The blob bytes are NOT read or buffered here.
     */
    fun readHeaderFrom(source: InputStream): RelayHeader? {
        val headerBuf = mutableListOf<Byte>()
        while (true) {
            val b = source.read()
            if (b == -1) return null            // EOF before newline
            if (b.toByte() == NEWLINE) break    // newline found; stream now at blob start
            headerBuf.add(b.toByte())
            if (headerBuf.size > MAX_HEADER_BYTES) return null  // runaway header guard
        }
        if (headerBuf.isEmpty()) return null
        return runCatching {
            JsonLines.format.decodeFromString(
                RelayHeader.serializer(),
                String(headerBuf.toByteArray(), Charsets.UTF_8),
            )
        }.getOrNull()
    }

    /**
     * Stream [expectedBytes] bytes from [source] to [sink] in bounded [CHUNK]-sized reads, returning
     * the total bytes written. The caller MUST verify this equals [RelayHeader.byteLength] — a
     * mismatch means the frame was truncated or corrupt. The bytes are copied VERBATIM — never
     * decoded, sniffed, or format-validated.
     */
    fun streamBlob(source: InputStream, sink: OutputStream, expectedBytes: Long): Long {
        val buf = ByteArray(CHUNK)
        var remaining = expectedBytes
        var written = 0L
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = source.read(buf, 0, toRead)
            if (n == -1) break
            sink.write(buf, 0, n)
            written += n
            remaining -= n
        }
        return written
    }

    /**
     * Test helper: fully materializes header + opaque bytes into a [RelayFrame]. NOT used on the
     * production apply path (full materialization is unsafe at the 2 GiB relay cap). Safe for small
     * test blobs to assert byte-exact round-trip equality without duplicating the scan logic.
     */
    fun readFrom(source: InputStream): RelayFrame? {
        val header = readHeaderFrom(source) ?: return null
        val blob = source.readBytes()
        if (blob.size.toLong() != header.byteLength) return null
        return RelayFrame(header, blob)
    }
}

/** A decoded relay frame (header + OPAQUE bytes). Used in tests for byte-exact round-trip checks. */
class RelayFrame(val header: RelayHeader, val opaqueBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayFrame) return false
        return header == other.header && opaqueBytes.contentEquals(other.opaqueBytes)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + opaqueBytes.contentHashCode()
}

/**
 * A validated re-link prompt surfaced on the receiver's Done screen: "open this in <app> and enter
 * your passphrase". Display-only typed fields — NEVER the opaque bytes, NEVER a passphrase.
 *
 * [itemId] is the wire item id ([cc.grepon.portage.model.ItemMeta.itemId]) of the relay item that
 * produced this prompt. It is the unique Compose row key on the Done screen so that two relays for
 * the same app (e.g. two Signal backups) produce two distinct rows rather than a duplicate-key crash.
 * It also disambiguates handoff filenames so a second same-app relay never silently overwrites the
 * first file on disk.
 */
data class RelayRestorePrompt(
    val itemId: Int,
    val app: RelayApp,
    val targetPackage: String,
    val originalName: String,
    val restoreNote: String,
)

/**
 * Sender side: stage a user-picked OPAQUE app-backup file behind a [RelayHeader]. portage cannot
 * trigger the app's export (these apps deny programmatic backup by design) — the USER triggers it,
 * then points portage at the resulting file via SAF; this provider only ferries it. The file content
 * is OPAQUE: [openPickedFile] yields a stream copied verbatim into the item, never interpreted.
 *
 * NOTE: this class is built and tested, but the SAF file-pick UI that wires instances of it into the
 * sender is NOT yet implemented — APP_BACKUP_RELAY has no producer until the follow-up Phase-2 PR.
 * The green tests here prove the provider itself works, not the end-to-end flow.
 *
 * [available] is false when no/empty file was picked or [restoreNote] is blank (a blank note would
 * be rejected by [RelayHeader.sanitizedOrNull] on the receiver anyway, so we gate it here too).
 */
class AppBackupRelayExportProvider(
    private val app: RelayApp,
    private val targetPackage: String,
    private val originalName: String,
    private val restoreNote: String,
    private val openPickedFile: () -> InputStream,
    private val pickedFileLength: Long,
) : ExportProvider {

    override val kind = ItemKind.APP_BACKUP_RELAY
    override val displayName = if (originalName.isNotBlank()) originalName else "App backup"
    override val group = "App backups"

    override suspend fun available(): Boolean = pickedFileLength > 0L && restoreNote.isNotBlank()

    override suspend fun exportTo(sink: OutputStream) {
        if (pickedFileLength <= 0L || restoreNote.isBlank()) return
        val header = RelayHeader(app, targetPackage, originalName, restoreNote, pickedFileLength)
        // Write the JSON header line, then stream the opaque bytes verbatim — never buffered/parsed.
        sink.write(
            JsonLines.format.encodeToString(RelayHeader.serializer(), header).toByteArray(Charsets.UTF_8),
        )
        sink.write('\n'.code)
        openPickedFile().use { it.copyTo(sink) }
        sink.flush()
    }
}

/**
 * Receiver side: validate a staged relay item, then surface a re-link prompt and STREAM the OPAQUE
 * bytes to the user / target app via [handoff]. It NEVER imports into the app and NEVER interprets
 * the bytes — exactly the "produce a checklist the user works through" shape of
 * AppInventoryApplyProvider.
 *
 * Validation (derive-never-trust, PRP-06 §5/§7): the header is sanitized ([RelayHeader.sanitizedOrNull])
 * — the target is derived from the typed [RelayApp] enum, the advisory package re-validated against
 * it, the note/name bounded and control-stripped. The opaque bytes are NEVER decoded/sniffed. Nothing
 * (detail, prompt, or log) ever carries the blob contents or a passphrase.
 *
 * [handoff] receives (header, blobStream, declaredByteLength, itemId). It STREAMS bytes from
 * [blobStream] to the destination, counts them, verifies against [declaredByteLength], and deletes
 * any partial file on mismatch. Returns true on success, false on any failure. A failed item maps to
 * a per-item WRITE_ERROR — never a batch abort (PROTOCOL.md §5).
 *
 * [itemId] comes from [cc.grepon.portage.model.ItemMeta.itemId] via [nextItemId]. The caller
 * (ReceiverViewModel.applyStaged) sets this before each apply call so that two relay items for the
 * same package get distinct [RelayRestorePrompt] row keys and distinct handoff filenames — preventing
 * both the Compose duplicate-key crash and silent file overwrite.
 */
class AppBackupRelayApplyProvider(
    private val onPrompt: (RelayRestorePrompt) -> Unit,
    private val handoff: (RelayHeader, InputStream, Long, Int) -> Boolean,
    /**
     * Returns the item id for the item currently being applied. Set by the ViewModel before each
     * [apply] call via [setNextItemId]. Default 0 is safe: it is overwritten before first use in
     * production; tests that exercise the single-item path can leave it at the default.
     */
    private var nextItemId: Int = 0,
) : ApplyProvider {

    override val kind = ItemKind.APP_BACKUP_RELAY

    /** Called by ReceiverViewModel.applyStaged immediately before apply() to thread the item id. */
    fun setNextItemId(id: Int) { nextItemId = id }

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val itemId = nextItemId

        // Read only the header line; stream position is left at the first blob byte.
        val rawHeader = RelayCodec.readHeaderFrom(source)
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable app-backup relay payload")

        // Derive-never-trust: reject any header whose advisory fields fail the typed-enum + bounds
        // gate BEFORE surfacing a prompt or handing off the file.
        val header = rawHeader.sanitizedOrNull()
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "invalid relay header (package/note/length)")

        // Stream the OPAQUE bytes to the user-visible location via the handoff seam. portage never
        // imports them — the user completes the restore with their passphrase in the target app.
        // We do NOT log/echo the bytes; detail carries only the display name + app label.
        val handedOff = runCatching {
            handoff(header, source, header.byteLength, itemId)
        }.getOrDefault(false)
        if (!handedOff) {
            return ApplyOutcome(ItemStatus.WRITE_ERROR, "could not stage the relayed backup file")
        }

        onPrompt(
            RelayRestorePrompt(
                itemId = itemId,
                app = header.app,
                targetPackage = header.targetPackage,
                originalName = header.originalName,
                restoreNote = header.restoreNote,
            ),
        )
        val appLabel = header.app.name.lowercase()
        return ApplyOutcome(ItemStatus.OK, "relayed an app backup for $appLabel — open it there to restore")
    }
}
