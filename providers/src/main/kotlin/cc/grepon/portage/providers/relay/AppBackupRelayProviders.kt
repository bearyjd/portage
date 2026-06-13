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
 */
@Serializable
enum class RelayApp(val canonicalPackages: Set<String>) {
    SIGNAL(setOf("org.thoughtcrime.securesms")),
    MOLLY(setOf("im.molly.app", "im.molly.foss")),
    AEGIS(setOf("com.beemdevelopment.aegis")),
    OTHER(emptySet()),
    ;

    /** The single canonical package for display/derivation, or null for MOLLY (two) and OTHER. */
    val canonicalPackage: String? get() = canonicalPackages.singleOrNull()

    companion object {
        /** Map an installed package name to its [RelayApp], or [OTHER] when it is not a known relay app. */
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
 *    sender cannot redirect the re-link to an arbitrary package (the hardened InstallAction precedent).
 *  - [originalName] is DISPLAY-ONLY and NEVER used as a filesystem path (the receiver stages under a
 *    generated name, like every item).
 *  - [restoreNote] is a human reminder shown on the receiver; length-bounded and control-stripped.
 *  - [byteLength] is the opaque-blob length following the header; cross-checked vs the actual bytes.
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

/** A decoded relay item: its header plus the OPAQUE app-encrypted bytes that followed it. */
class RelayFrame(val header: RelayHeader, val opaqueBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayFrame) return false
        return header == other.header && opaqueBytes.contentEquals(other.opaqueBytes)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + opaqueBytes.contentHashCode()
}

/**
 * Wire codec for a relay item: `JSON(header) + "\n" + <opaque app-encrypted bytes>`. Keeps the
 * transport contract ("an item is a byte stream") intact while giving the receiver a typed app id +
 * restore note. The opaque bytes are split by length ONLY — never parsed, decoded, or sniffed. As a
 * defensive cross-check (mirroring WallpaperCodec), the actual byte count is compared against
 * [RelayHeader.byteLength]; a mismatch indicates a truncated/corrupt frame and is rejected. Streams
 * are NOT closed here — the staging layer owns their lifecycle.
 */
object RelayCodec {

    private const val NEWLINE = '\n'.code

    fun writeTo(sink: OutputStream, header: RelayHeader, opaqueBytes: ByteArray) {
        sink.write(JsonLines.format.encodeToString(RelayHeader.serializer(), header).toByteArray(Charsets.UTF_8))
        sink.write(NEWLINE)
        sink.write(opaqueBytes)
        sink.flush()
    }

    /**
     * Decode a single framed relay item, or null if the header line is absent/unparseable or the
     * declared [RelayHeader.byteLength] disagrees with the actual opaque-byte count. The opaque
     * bytes are taken verbatim — this method NEVER inspects their contents.
     */
    fun readFrom(source: InputStream): RelayFrame? {
        val all = source.readBytes()
        val split = all.indexOf(NEWLINE.toByte())
        if (split < 0) return null
        val header = runCatching {
            JsonLines.format.decodeFromString(
                RelayHeader.serializer(),
                String(all, 0, split, Charsets.UTF_8),
            )
        }.getOrNull() ?: return null
        val opaqueBytes = all.copyOfRange(split + 1, all.size)
        if (opaqueBytes.size.toLong() != header.byteLength) return null
        return RelayFrame(header, opaqueBytes)
    }

    private fun ByteArray.indexOf(target: Byte): Int {
        for (i in indices) if (this[i] == target) return i
        return -1
    }
}

/**
 * A validated re-link prompt surfaced on the receiver's Done screen: "open this in <app> and enter
 * your passphrase". Display-only typed fields — NEVER the opaque bytes, NEVER a passphrase. Mirrors
 * the inventory/Bluetooth checklist shape: the apply path produces this; the user acts on it.
 */
data class RelayRestorePrompt(
    val app: RelayApp,
    val targetPackage: String,
    val originalName: String,
    val restoreNote: String,
)

/**
 * Sender side: stage a user-picked OPAQUE app-backup file behind a [RelayHeader]. portage cannot
 * trigger the app's export (these apps deny programmatic backup by design) — the USER triggers it,
 * then points portage at the resulting file via SAF; this provider only ferries it. The file content
 * is OPAQUE: [openPickedFile] yields a stream of bytes copied verbatim into the item, never read or
 * interpreted by portage.
 *
 * One instance per picked file. [available] is false when no/empty file was picked, so an unpicked
 * relay never enters the manifest (the Tier-0 graceful-degrade contract, Providers.kt).
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

    override suspend fun available(): Boolean = pickedFileLength > 0L

    override suspend fun exportTo(sink: OutputStream) {
        if (pickedFileLength <= 0L) return
        val header = RelayHeader(app, targetPackage, originalName, restoreNote, pickedFileLength)
        // Write the header line, then stream the opaque bytes verbatim (never buffered/parsed whole).
        sink.write(
            JsonLines.format.encodeToString(RelayHeader.serializer(), header).toByteArray(Charsets.UTF_8),
        )
        sink.write('\n'.code)
        openPickedFile().use { it.copyTo(sink) }
        sink.flush()
    }
}

/**
 * Receiver side: validate a staged relay item, then surface a re-link prompt and hand the OPAQUE
 * file to the user / target app via [handoff]. It NEVER imports into the app and NEVER interprets the
 * bytes — exactly the "produce a checklist the user works through" shape of AppInventoryApplyProvider
 * (the apply does no app write itself).
 *
 * Validation (derive-never-trust, PRP-06 §5/§7): the header is sanitized ([RelayHeader.sanitizedOrNull])
 * — the target is derived from the typed [RelayApp] enum, the advisory package re-validated against
 * it, the note/name bounded and control-stripped. A hostile sender cannot redirect the re-link to an
 * arbitrary package or smuggle a scheme. The opaque bytes are NEVER decoded/sniffed. Nothing here
 * (detail, prompt, or log) ever carries the blob contents or a passphrase.
 *
 * [handoff] writes the opaque bytes to a user-accessible location / hands them to the target app
 * (e.g. via SAF or a scoped content:// grant); false on failure. A failed item is a per-item result,
 * never a batch abort (PROTOCOL.md §5).
 */
class AppBackupRelayApplyProvider(
    private val onPrompt: (RelayRestorePrompt) -> Unit,
    private val handoff: (RelayHeader, ByteArray) -> Boolean,
) : ApplyProvider {

    override val kind = ItemKind.APP_BACKUP_RELAY

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val frame = RelayCodec.readFrom(source)
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable app-backup relay payload")

        // Derive-never-trust: reject any header whose advisory fields fail the typed-enum + bounds
        // gate BEFORE surfacing a prompt or handing off the file.
        val header = frame.header.sanitizedOrNull()
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "invalid relay header (package/note/length)")

        // Hand off the OPAQUE bytes (write to a user location / target-app surface). portage never
        // imports them itself — the user completes the restore inside the target app with their
        // passphrase. We do NOT log/echo the bytes; detail carries only the display name + app id.
        val handedOff = runCatching { handoff(header, frame.opaqueBytes) }.getOrDefault(false)
        if (!handedOff) {
            return ApplyOutcome(ItemStatus.WRITE_ERROR, "could not stage the relayed backup file")
        }

        onPrompt(
            RelayRestorePrompt(
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
