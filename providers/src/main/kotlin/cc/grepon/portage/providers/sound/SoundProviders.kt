/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.sound

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProvider
import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.providers.wire.JsonLines
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * The three default-sound roles this feature carries (PRP-04 §2). The typed enum is the dispatch
 * key the receiver maps to a platform `RingtoneManager.TYPE_*` itself ([AndroidSoundStore]), so a
 * payload can never steer a write into the wrong role — the same "derive, never trust" discipline
 * the settings provider applies to namespaces (SettingsProviders.kt).
 */
@Serializable
enum class SoundRole { RINGTONE, NOTIFICATION, ALARM }

/**
 * Where a role's chosen sound comes from. Phase 1 carries BUILTIN only; USER_FILE is reserved for
 * the deferred Phase 2 (custom file copy + MediaStore re-register + URI remap) and, if it ever
 * appears on the wire in this version, the apply path SKIPS it (no backing file is delivered).
 * UNSET means the role had no default set on the sender.
 */
@Serializable
enum class SoundSource { BUILTIN, USER_FILE, UNSET }

/**
 * One role → sound selection on the wire (PRP-04 §5). For BUILTIN, [builtinTitle] is the stable
 * identity the receiver matches against its OWN enumerated built-ins to resolve a LOCAL URI — the
 * carried value is a lookup key, NEVER a destination. [fileSha256]/[fileDisplayName] are Phase-2
 * fields (deferred); they are accepted on decode for forward-compat but unused in Phase 1.
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

    /** This device's local URI for the built-in named [title] under [role], or null if absent. */
    fun resolveBuiltin(role: SoundRole, title: String): String?

    /** Whether default-sound writes are permitted now (the "modify system settings" access). */
    fun canWrite(): Boolean

    /** Set [role]'s default to [uri] via `setActualDefaultRingtoneUri`. False on write failure. */
    fun setDefault(role: SoundRole, uri: String): Boolean
}

/**
 * Sender side: snapshot the three role → built-in-identity selections (PRP-04 §4). Only roles with
 * a non-null default that resolves to a BUILTIN identity are emitted — a user-file-backed role has
 * no portable identity in Phase 1, so it is dropped entirely rather than carried as something the
 * target cannot honor. Mirrors SettingsExportProvider's snapshot shape; reads degrade to "nothing
 * to send" on any failure (no permission is required to read these).
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
            // Phase 1: carry only built-in selections (a resolvable title). A user-file URI has no
            // built-in title here, so it is omitted — Phase 2 (deferred) carries the file itself.
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
 * USER_FILE roles are SKIPPED in Phase 1 (no backing file is delivered) — Phase 2 is deferred.
 */
class SoundSelectionApplyProvider(
    private val store: SoundStore,
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
        for (choice in selection.choices) {
            val targetUri = resolveLocalUri(choice)
            if (targetUri == null) {
                skipped++
                continue
            }
            val wrote = runCatching { store.setDefault(choice.role, targetUri) }.getOrDefault(false)
            if (wrote) applied++ else skipped++
        }

        return ApplyOutcome(ItemStatus.OK, "applied $applied, skipped $skipped")
    }

    /**
     * Resolve the LOCAL URI to write for [choice], or null to skip the role. BUILTIN re-resolves
     * the carried title against THIS device's built-ins and validates the result; USER_FILE/UNSET
     * (and any unmatched/invalid case) yield null so the role is left at its existing default.
     */
    private fun resolveLocalUri(choice: SoundChoice): String? {
        if (choice.source != SoundSource.BUILTIN) return null // USER_FILE deferred (Phase 2); UNSET skipped
        val title = choice.builtinTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolved = runCatching { store.resolveBuiltin(choice.role, title) }.getOrNull() ?: return null
        return resolved.takeIf { SoundUri.isAcceptable(it) }
    }
}
