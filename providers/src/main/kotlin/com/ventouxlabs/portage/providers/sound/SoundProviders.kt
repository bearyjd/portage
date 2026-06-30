/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.sound

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.TransferScopedApplyProvider
import com.ventouxlabs.portage.providers.wire.JsonLines
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream

/**
 * The three default-sound roles this feature carries (PRP-04 §2). The typed enum is the dispatch
 * key the receiver maps to a platform `RingtoneManager.TYPE_*` itself ([AndroidSoundStore]), so a
 * payload can never steer a write into the wrong role — the same "derive, never trust" discipline
 * the settings provider applies to namespaces (SettingsProviders.kt).
 */
@Serializable
enum class SoundRole { RINGTONE, NOTIFICATION, ALARM }

/**
 * Where a role's chosen sound comes from. BUILTIN is re-resolved by title on the receiver; USER_FILE
 * is carried by a preceding SOUND_FILE item and resolved through a transfer-scoped remap table.
 * UNSET means the role had no default set on the sender.
 */
@Serializable
enum class SoundSource { BUILTIN, USER_FILE, UNSET }

/**
 * One role → sound selection on the wire (PRP-04 §5). For BUILTIN, [builtinTitle] is the stable
 * identity the receiver matches against its OWN enumerated built-ins to resolve a LOCAL URI — the
 * carried value is a lookup key, NEVER a destination. For USER_FILE, [fileDisplayName] is advisory
 * UI text only; the actual local URI is produced by the prior SOUND_FILE item for the same role.
 */
@Serializable
data class SoundChoice(
    val role: SoundRole,
    val source: SoundSource,
    val builtinTitle: String? = null,
    val fileSha256: String? = null,
    val fileDisplayName: String? = null,
)

@Serializable
data class SoundSelection(val choices: List<SoundChoice>)

/** JSON (de)serialization for the selection snapshot — separated so tests can frame payloads. */
object SoundCodec {
    fun encode(selection: SoundSelection): String =
        JsonLines.format.encodeToString(SoundSelection.serializer(), selection)

    fun decode(source: InputStream): SoundSelection? = runCatching {
        JsonLines.format.decodeFromString(
            SoundSelection.serializer(),
            source.bufferedReader(Charsets.UTF_8).readText(),
        )
    }.getOrNull()
}

@Serializable
data class SoundFileHeader(
    val role: SoundRole,
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
) {
    fun sanitizedOrNull(): SoundFileHeader? {
        if (byteLength < 0L || byteLength > MAX_PAYLOAD_BYTES) return null
        val name = displayName
            .filter { !it.isISOControl() && it != '/' && it != '\\' }
            .trim()
            .take(MAX_NAME_LENGTH)
            .ifBlank { "${role.name.lowercase()}.ogg" }
        val mime = mimeType.trim().lowercase()
        if (!MIME.matches(mime)) return null
        return copy(displayName = name, mimeType = mime)
    }

    companion object {
        const val MAX_NAME_LENGTH = 160
        const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024
        const val MAX_HEADER_FRAME_BYTES = 4L * 1024 + 1
        const val MAX_ITEM_BYTES = MAX_PAYLOAD_BYTES + MAX_HEADER_FRAME_BYTES
        private val MIME = Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")
    }
}

data class SoundFileCandidate(
    val role: SoundRole,
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
    val openStream: () -> InputStream,
)

object SoundFileCodec {
    private const val MAX_HEADER_BYTES = 4 * 1024
    private const val CHUNK = 16 * 1024

    fun writeHeader(sink: OutputStream, header: SoundFileHeader) {
        sink.write(
            JsonLines.format.encodeToString(SoundFileHeader.serializer(), header)
                .toByteArray(Charsets.UTF_8),
        )
        sink.write('\n'.code)
    }

    fun readHeader(source: InputStream): SoundFileHeader? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < MAX_HEADER_BYTES) {
            val value = source.read()
            if (value == -1) return null
            if (value == '\n'.code) {
                if (bytes.isEmpty()) return null
                return runCatching {
                    JsonLines.format.decodeFromString(
                        SoundFileHeader.serializer(),
                        String(bytes.toByteArray(), Charsets.UTF_8),
                    )
                }.getOrNull()
            }
            bytes += value.toByte()
        }
        return null
    }

    fun stream(source: InputStream, sink: OutputStream, expectedBytes: Long): Long {
        val buffer = ByteArray(CHUNK)
        var remaining = expectedBytes
        var written = 0L
        while (remaining > 0) {
            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count <= 0) break
            sink.write(buffer, 0, count)
            remaining -= count
            written += count
        }
        return written
    }
}

/**
 * URI hygiene for the apply path (PRP-04 §7, derive-never-trust). Even though the receiver only
 * ever writes a URI IT resolved locally — never a sender-supplied string — that locally-resolved
 * URI is still validated before it reaches `setActualDefaultRingtoneUri`: it must be non-blank and
 * carry an expected media scheme. This keeps a malformed/unexpected platform result from ever
 * landing as a dangling default sound.
 */
object SoundUri {
    private val ACCEPTED_SCHEMES = setOf("content", "file")

    fun isAcceptable(uri: String): Boolean {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return false
        val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme in ACCEPTED_SCHEMES
    }
}

/**
 * The `RingtoneManager` / `Settings.System` boundary, mirroring SystemSettingsStore. Reading the
 * current default URI and its built-in title needs no permission (Tier 0). WRITING needs the
 * user-granted "Modify system settings" special access ([canWrite], `Settings.System.canWrite`).
 *
 * [resolveBuiltin] is the heart of the cross-device safety property: given a role and a built-in
 * TITLE carried from the old phone, it returns THIS device's local URI for the equivalent built-in,
 * or null when the target has no match (a different build/OEM set) — in which case the role is left
 * at its existing default rather than getting a dangling write (PRP-04 §3 fallback).
 */
interface SoundStore {
    /** The current default URI string for [role], or null when no default is set. */
    fun read(role: SoundRole): String?

    /** The human title of [uri] if it resolves to a built-in sound, else null (e.g. a user file). */
    fun titleOf(uri: String): String?

    /** The active default sound as a readable custom file, or null when [uri] is built-in/unreadable. */
    fun customFile(role: SoundRole, uri: String): SoundFileCandidate? = null

    /** This device's local URI for the built-in named [title] under [role], or null if absent. */
    fun resolveBuiltin(role: SoundRole, title: String): String?

    /** Whether default-sound writes are permitted now (the "modify system settings" access). */
    fun canWrite(): Boolean

    /** Set [role]'s default to [uri] via `setActualDefaultRingtoneUri`. False on write failure. */
    fun setDefault(role: SoundRole, uri: String): Boolean

    /** Store a custom default-sound file and return this device's local MediaStore URI. */
    fun registerSoundFile(header: SoundFileHeader, source: InputStream): String? = null
}

/**
 * Sender side: snapshot the three role selections (PRP-04 §4). Built-ins are carried by title;
 * custom sounds are carried by a preceding SOUND_FILE item and referenced here by role.
 * Mirrors SettingsExportProvider's snapshot shape; reads degrade to "nothing to send" on any
 * failure (no permission is required to read built-in selections).
 */
class SoundSelectionExportProvider(
    private val store: SoundStore,
) : ExportProvider {

    override val kind = ItemKind.SOUND_SELECTION
    override val displayName = "Sounds"
    override val group = "Sounds"

    private fun snapshot(): SoundSelection = SoundSelection(
        SoundRole.entries.mapNotNull { role ->
            val uri = runCatching { store.read(role) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val custom = runCatching { store.customFile(role, uri) }.getOrNull()
            if (custom != null) {
                return@mapNotNull SoundChoice(
                    role = role,
                    source = SoundSource.USER_FILE,
                    fileDisplayName = custom.displayName,
                )
            }
            // Built-in selections carry only a resolvable title. The receiver never writes [uri].
            val title = runCatching { store.titleOf(uri) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SoundChoice(role, SoundSource.BUILTIN, builtinTitle = title)
        },
    )

    override suspend fun available(): Boolean =
        runCatching { snapshot().choices.isNotEmpty() }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val selection = runCatching { snapshot() }.getOrNull() ?: return
        if (selection.choices.isEmpty()) return
        val writer = sink.bufferedWriter(Charsets.UTF_8)
        writer.write(SoundCodec.encode(selection))
        writer.flush()
    }
}

class SoundFileExportProvider(
    private val role: SoundRole,
    private val store: SoundStore,
) : ExportProvider {
    override val kind = ItemKind.SOUND_FILE
    override val displayName = "Custom ${role.name.lowercase()} sound"
    override val group = "Sounds"

    private fun candidate(): SoundFileCandidate? {
        val uri = runCatching { store.read(role) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { store.customFile(role, uri) }.getOrNull()
    }

    override suspend fun available(): Boolean =
        candidate()?.let {
            SoundFileHeader(it.role, it.displayName, it.mimeType, it.byteLength).sanitizedOrNull() != null
        } ?: false

    override suspend fun exportTo(sink: OutputStream) {
        val file = candidate() ?: return
        val header = SoundFileHeader(file.role, file.displayName, file.mimeType, file.byteLength)
            .sanitizedOrNull() ?: return
        SoundFileCodec.writeHeader(sink, header)
        file.openStream().use { source ->
            val copied = SoundFileCodec.stream(source, sink, header.byteLength)
            check(copied == header.byteLength) { "sound file length changed before transfer" }
            check(source.read() == -1) { "sound file grew before transfer" }
        }
        sink.flush()
    }
}

fun soundFileExportProviders(store: SoundStore): List<ExportProvider> =
    SoundRole.entries.map { SoundFileExportProvider(it, store) }

class SoundFileRemap {
    private val byRole = mutableMapOf<SoundRole, String>()

    fun clear() {
        byRole.clear()
    }

    fun put(role: SoundRole, uri: String) {
        byRole[role] = uri
    }

    fun uriFor(role: SoundRole): String? = byRole[role]
}

class SoundFileApplyProvider(
    private val store: SoundStore,
    private val remap: SoundFileRemap,
) : ApplyProvider, TransferScopedApplyProvider {
    override val kind = ItemKind.SOUND_FILE

    override fun beginTransfer() {
        remap.clear()
    }

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val header = SoundFileCodec.readHeader(source)?.sanitizedOrNull()
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "invalid sound-file header")
        val prefix = source.readNBytes(SOUND_SNIFF_BYTES)
        if (!looksLikeAudio(prefix)) {
            return ApplyOutcome(ItemStatus.SKIPPED, "custom sound did not look like audio")
        }
        val replayingSource = SequenceInputStream(ByteArrayInputStream(prefix), source)
        val uri = runCatching { store.registerSoundFile(header, replayingSource) }.getOrNull()
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "could not register custom sound")
        if (!SoundUri.isAcceptable(uri)) {
            return ApplyOutcome(ItemStatus.WRITE_ERROR, "registered custom sound has invalid URI")
        }
        remap.put(header.role, uri)
        return ApplyOutcome(ItemStatus.OK, "saved custom ${header.role.name.lowercase()} sound")
    }

    private fun looksLikeAudio(prefix: ByteArray): Boolean {
        if (prefix.size >= 12 &&
            prefix.copyOfRange(0, 4).contentEquals(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())) &&
            prefix.copyOfRange(8, 12).contentEquals(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        ) {
            return true
        }
        if (prefix.size >= 4 && String(prefix.copyOfRange(0, 4), Charsets.US_ASCII) in setOf("OggS", "fLaC")) {
            return true
        }
        if (prefix.size >= 3 && String(prefix.copyOfRange(0, 3), Charsets.US_ASCII) == "ID3") {
            return true
        }
        if (prefix.size >= 2 && prefix[0] == 0xFF.toByte() && (prefix[1].toInt() and 0xE0) == 0xE0) {
            return true
        }
        if (prefix.size >= 12 && String(prefix.copyOfRange(4, 8), Charsets.US_ASCII) == "ftyp") {
            return true
        }
        return false
    }

    private companion object {
        const val SOUND_SNIFF_BYTES = 16
    }
}

/**
 * Receiver side: for each carried role, resolve a LOCAL URI and set it as the default — best-effort
 * and per-role resilient (a single unresolvable role never aborts the item; PROTOCOL.md §5). The
 * critical robustness property (PRP-04 §3, §7): the receiver NEVER writes a sender-supplied URI. It
 * re-resolves each BUILTIN title against its own enumerated built-ins; if the target has no match,
 * the role is left at its existing default (skipped), never written with a dangling URI. The
 * resolved URI is additionally validated by [SoundUri] before any write.
 *
 * Writes need the "Modify system settings" special access ([SoundStore.canWrite]); when it is
 * absent every role self-skips and the outcome carries the grant hint, mirroring the settings
 * provider's grant-blocked path.
 *
 * USER_FILE roles resolve through [fileRemap], which is populated by earlier SOUND_FILE items in
 * the same transfer. Missing files skip rather than writing a dangling sender URI.
 */
class SoundSelectionApplyProvider(
    private val store: SoundStore,
    private val fileRemap: SoundFileRemap = SoundFileRemap(),
) : ApplyProvider {

    override val kind = ItemKind.SOUND_SELECTION

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val selection = SoundCodec.decode(source)
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable sound selection payload")

        if (!runCatching { store.canWrite() }.getOrDefault(false)) {
            return ApplyOutcome(
                ItemStatus.SKIPPED,
                "sound selections need the modify system settings access",
            )
        }

        var applied = 0
        var skipped = 0
        var writeFailed = 0
        for (choice in selection.choices) {
            val targetUri = resolveLocalUri(choice)
            if (targetUri == null) {
                skipped++
                continue
            }
            // targetUri is locally resolved + scheme-validated; a false return here means the
            // platform refused a URI we derived ourselves (distinct from a no-match skip).
            val wrote = runCatching { store.setDefault(choice.role, targetUri) }.getOrDefault(false)
            if (wrote) applied++ else writeFailed++
        }

        if (writeFailed > 0) {
            return ApplyOutcome(
                ItemStatus.WRITE_ERROR,
                "applied $applied, skipped $skipped, write failed $writeFailed",
            )
        }
        if (applied == 0 && skipped > 0) {
            return ApplyOutcome(
                ItemStatus.OK,
                "no matching built-in sounds on this device (skipped $skipped)",
            )
        }
        return ApplyOutcome(ItemStatus.OK, "applied $applied, skipped $skipped")
    }

    /**
     * Resolve the LOCAL URI to write for [choice], or null to skip the role. BUILTIN re-resolves
     * the carried title against THIS device's built-ins; USER_FILE uses the transfer-scoped remap
     * populated by SOUND_FILE; UNSET skips.
     */
    private fun resolveLocalUri(choice: SoundChoice): String? {
        val resolved = when (choice.source) {
            SoundSource.BUILTIN -> {
                val title = choice.builtinTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                runCatching { store.resolveBuiltin(choice.role, title) }.getOrNull()
            }
            SoundSource.USER_FILE -> fileRemap.uriFor(choice.role)
            SoundSource.UNSET -> null
        } ?: return null
        return resolved.takeIf { SoundUri.isAcceptable(it) }
    }
}
