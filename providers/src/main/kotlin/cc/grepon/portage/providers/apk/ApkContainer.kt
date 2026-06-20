/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.apk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream

/**
 * The role a single APK file plays inside a split-APK set (ADR-006 D1). `BASE` is the one mandatory
 * file; the others are config/density/abi/language/feature splits. The receiver re-derives role and
 * target-compatibility (ADR-006 D3) — these tags are advisory and never trusted as the install plan.
 *
 * [SerialName] is explicit on every constant (the same discipline
 * [cc.grepon.portage.providers.relay.RelayApp] applies) so a future Kotlin rename cannot silently
 * break the wire format.
 */
@Serializable
enum class ApkFileRole {
    @SerialName("base")
    BASE,

    @SerialName("config")
    CONFIG,

    @SerialName("language")
    LANGUAGE,

    @SerialName("feature")
    FEATURE,
}

/**
 * One-line structured header that precedes the framed split files in an APK container (ADR-006 D1).
 * Mirrors the relay/wallpaper header+blob shape, with the same "advisory fields are re-validated,
 * never trusted" rule applied by [ApkContainerValidation]:
 *  - [packageName] is re-validated against the package grammar; it is never used as a path.
 *  - [fileCount] is bounded to `1..MAX_APK_FILES` (ADR-006 D4) and cross-checked against the number
 *    of entry lines actually streamed.
 *  - [capturedPermissions] is ADVISORY and locked into the wire now (ADR-006 D1/D5) so Phase-5
 *    permission parity causes no wire churn; nothing reads it until then.
 */
@Serializable
data class ApkContainerHeader(
    val packageName: String,
    val versionCode: Long,
    val fileCount: Int,
    val capturedPermissions: List<String> = emptyList(),
)

/**
 * One per-file entry line that precedes that file's bytes in the framed container (ADR-006 D1). The
 * per-split [abi]/[density]/[lang] tags are derived on the sender and locked into the wire now because
 * the receiver needs them to reconcile against the target device (ADR-006 D3) — they are advisory and
 * re-derived on apply, never trusted blindly.
 *
 *  - [name] is `"base"` or a split name; it is validated by [validatedSplitNameOrNull] BEFORE it is
 *    ever used as a staged filename (ADR-006 AC-6b) — a hostile name (path traversal, separators,
 *    shell metacharacters) is rejected, never sanitized-in-place.
 *  - [length] is the number of bytes that follow this entry line; it is cross-checked against the
 *    actual streamed count. A negative length is rejected.
 */
@Serializable
data class ApkFileEntry(
    val name: String,
    val role: ApkFileRole,
    val abi: String? = null,
    val density: String? = null,
    val lang: String? = null,
    val length: Long,
)

/**
 * Sender-side source for one APK file: the wire [entry] metadata plus an [open] opener that yields a
 * fresh stream of exactly [ApkFileEntry.length] bytes. The opener is injected by the (future) app-send
 * caller from `PackageManager` source dirs — the codec never opens an Android file itself, keeping
 * `:providers` Android-type-free (ADR-006 D2). [ApkCodec.writeContainer] streams [open]'s bytes
 * verbatim and does NOT close the caller-owned source stream.
 */
class ApkSourceFile(val entry: ApkFileEntry, val open: () -> InputStream)

/**
 * A fully-materialized decoded container (header + every file's bytes). Used ONLY by
 * [ApkCodec.readFrom] for byte-exact round-trip test assertions — production reads stream each file
 * straight to a staged file via [ApkCodec.readHeaderFrom]/[ApkCodec.readEntryFrom]/[ApkCodec.streamBlob]
 * and NEVER materializes the whole container in memory (ADR-006 D1; an item can be up to 1 GiB).
 */
class ApkContainer(val header: ApkContainerHeader, val files: List<DecodedApkFile>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApkContainer) return false
        return header == other.header && files == other.files
    }

    override fun hashCode(): Int = 31 * header.hashCode() + files.hashCode()
}

/** One decoded file (entry + its materialized bytes) inside an [ApkContainer]. Test-only. */
class DecodedApkFile(val entry: ApkFileEntry, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedApkFile) return false
        return entry == other.entry && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * entry.hashCode() + bytes.contentHashCode()
}
