/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.wallpaper

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.wire.JsonLines
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * Tighter wallpaper-specific byte cap layered UNDER the transport's 64 MiB
 * DEFAULT_MAX_ITEM_BYTES (ItemStreamReceiver). A 4K lossless PNG sits well below 32 MiB, so a
 * wallpaper can never consume the whole Tier-0 budget (PRP-02 §5, §7.3). Package-internal so the
 * test can frame an oversize payload against the exact threshold.
 */
internal const val MAX_WALLPAPER_BYTES: Long = 32L * 1024 * 1024

/**
 * Bounds-only pixel ceiling for the decompression-bomb gate (PRP-02 §7.1): the apply path rejects
 * any image whose decoded WIDTH*HEIGHT exceeds this BEFORE a bitmap is ever allocated. 64 MP is
 * larger than any sane wallpaper yet far below the multi-gigapixel decode a hostile sender would
 * use to OOM the receiver.
 */
internal const val MAX_PIXELS: Long = 64L * 1024 * 1024

/**
 * The platform FLAG_* values for [WallpaperManager], mirrored as plain constants so the model and
 * its tests stay JVM-only (no android.* on the unit-test classpath). [AndroidWallpaperStore] is the
 * single place these are crossed back into the real framework constants — and they match by value.
 */
object WallpaperManagerFlags {
    const val FLAG_SYSTEM: Int = 1 // WallpaperManager.FLAG_SYSTEM
    const val FLAG_LOCK: Int = 2 // WallpaperManager.FLAG_LOCK
}

/**
 * Which surface an image sets. The receiver maps this typed enum to a [WallpaperManagerFlags] int
 * itself, so a payload can never steer a write into the wrong surface via a raw int — the same
 * "derive, never trust" discipline the settings provider applies to namespaces (SettingsProviders).
 */
@Serializable
enum class WallpaperSurface(val flag: Int) {
    HOME(WallpaperManagerFlags.FLAG_SYSTEM),
    LOCK(WallpaperManagerFlags.FLAG_LOCK),
}

/** Decoded image dimensions from a bounds-only probe — no pixels allocated. */
data class ImageBounds(val width: Int, val height: Int)

/**
 * One-line structured header that precedes the raw image bytes in a wallpaper item (PRP-02 §5).
 * [format]/[width]/[height] are advisory ONLY: the receiver re-derives the real format from magic
 * bytes and the real bounds from a bounds-only decode, never trusting these fields.
 */
@Serializable
data class WallpaperHeader(
    val surface: WallpaperSurface,
    val format: String,
    val width: Int,
    val height: Int,
    val byteLength: Long,
)

/** A decoded wallpaper item: its header plus the raw image byte payload that followed it. */
data class WallpaperFrame(val header: WallpaperHeader, val imageBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WallpaperFrame) return false
        return header == other.header && imageBytes.contentEquals(other.imageBytes)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + imageBytes.contentHashCode()
}

/**
 * Wire codec for a wallpaper item: `JSON(header) + "\n" + <raw image bytes>`. Keeps the transport
 * contract ("an item is a byte stream") intact while giving the receiver a typed surface + a cheap
 * pre-decode descriptor. Streams are NOT closed here — the staging layer owns their lifecycle, as
 * with [JsonLines].
 */
object WallpaperCodec {

    private const val NEWLINE = '\n'.code

    fun writeTo(sink: OutputStream, header: WallpaperHeader, imageBytes: ByteArray) {
        sink.write(JsonLines.format.encodeToString(WallpaperHeader.serializer(), header).toByteArray(Charsets.UTF_8))
        sink.write(NEWLINE)
        sink.write(imageBytes)
        sink.flush()
    }

    /**
     * Decode a single framed wallpaper item, or null if the header line is absent/unparseable.
     * As a defensive cross-check, the actual image byte count is compared against
     * [WallpaperHeader.byteLength]: a mismatch indicates a truncated or corrupt frame and is
     * treated as invalid (PRP-02 §5 "byteLength cross-checked"). This also prevents the apply
     * path from silently accepting a frame where the exporter's declared length doesn't match
     * the actual payload.
     */
    fun readFrom(source: InputStream): WallpaperFrame? {
        val all = source.readBytes()
        val split = all.indexOf(NEWLINE.toByte())
        if (split < 0) return null
        val header = runCatching {
            JsonLines.format.decodeFromString(
                WallpaperHeader.serializer(),
                String(all, 0, split, Charsets.UTF_8),
            )
        }.getOrNull() ?: return null
        val imageBytes = all.copyOfRange(split + 1, all.size)
        // Cross-check: actual byte count must match the header's declared byteLength.
        if (imageBytes.size.toLong() != header.byteLength) return null
        return WallpaperFrame(header, imageBytes)
    }

    private fun ByteArray.indexOf(target: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == target) return i
        return -1
    }
}

/**
 * The [WallpaperManager] boundary, mirroring SmsStore / SystemSettingsStore. Reads MAY throw when
 * the surface is unavailable (e.g. a live wallpaper has no static file); the providers wrap every
 * call in runCatching so a denied/absent surface degrades to "nothing to send".
 *
 * [decodeBounds] abstracts the platform's `BitmapFactory.decodeStream(inJustDecodeBounds=true)`
 * probe so the decompression-bomb gate is unit-testable without android.* on the classpath. It
 * MUST NOT allocate the decoded bitmap — it returns dimensions only, or null when undecodable.
 */
interface WallpaperStore {
    /** Original active-wallpaper bytes for [surface], or null when it has no static file. */
    fun read(surface: WallpaperSurface): ByteArray?

    /** Bounds-only decode of [bytes] (no pixels allocated), or null if it isn't a decodable image. */
    fun decodeBounds(bytes: ByteArray): ImageBounds?

    /** Hand the raw [bytes] to the platform to decode + set on [surface]. False on write failure. */
    fun setStream(surface: WallpaperSurface, bytes: ByteArray): Boolean
}

/**
 * Sender side: export EXACTLY ONE surface per instance. Register two instances on the send side —
 * `WallpaperExportProvider(store, HOME)` and `WallpaperExportProvider(store, LOCK)` — so
 * [ManifestBuilder] produces up to two separate WALLPAPER items, each with its own item id. The
 * apply path ([WallpaperApplyProvider]) receives them independently, one item per [apply] call,
 * with the surface encoded in the frame header.
 *
 * The LOCK provider's [available] returns false when the lock wallpaper is null (mirrors home) OR
 * when its bytes are byte-for-byte identical to the home wallpaper (structural mirror). In either
 * case only the HOME item is emitted and the receiver sets the same image on both surfaces via the
 * HOME item, which is the correct "mirror" outcome (PRP-02 §4-5).
 */
class WallpaperExportProvider(
    private val store: WallpaperStore,
    private val surface: WallpaperSurface,
) : ExportProvider {

    override val kind = ItemKind.WALLPAPER
    override val displayName = if (surface == WallpaperSurface.HOME) "Wallpaper" else "Lock wallpaper"
    override val group = "Appearance"

    private fun readSurface(): ByteArray? =
        runCatching { store.read(surface) }.getOrNull()?.takeIf { it.isNotEmpty() }

    override suspend fun available(): Boolean = runCatching {
        val bytes = readSurface() ?: return@runCatching false
        // For the LOCK surface, suppress when it mirrors home (null or byte-identical).
        if (surface == WallpaperSurface.LOCK) {
            val home = runCatching { store.read(WallpaperSurface.HOME) }.getOrNull()
            if (home != null && bytes.contentEquals(home)) return@runCatching false
        }
        true
    }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val bytes = readSurface() ?: return
        val format = ImageFormat.sniff(bytes)?.mime ?: "application/octet-stream"
        val bounds = runCatching { store.decodeBounds(bytes) }.getOrNull()
        WallpaperCodec.writeTo(
            sink,
            WallpaperHeader(surface, format, bounds?.width ?: 0, bounds?.height ?: 0, bytes.size.toLong()),
            bytes,
        )
    }
}

/**
 * Receiver side: validate then set one staged wallpaper item. The validation gate (PRP-02 §7) is
 * the single most important control here — it runs BEFORE any bitmap is allocated and rejects:
 *  1. payloads over [MAX_WALLPAPER_BYTES] (OVERSIZE),
 *  2. bytes whose magic-byte MIME is not in the allowlist (PNG/JPEG/WebP) — sender's declared
 *     `format` is advisory and re-derived here, never trusted (SKIPPED),
 *  3. images whose bounds-only decode fails or exceeds [MAX_PIXELS] / has a non-positive dimension
 *     — the decompression-bomb gate; the platform decoder never sees a hostile multi-gigapixel
 *     image because [WallpaperManager.setStream] is only reached after this passes (SKIPPED).
 * The typed [WallpaperSurface] decides the FLAG_*, so a payload can never redirect the surface. A
 * failed item is a per-item skip, never a batch abort (PROTOCOL.md §5; Providers.kt).
 */
class WallpaperApplyProvider(private val store: WallpaperStore) : ApplyProvider {

    override val kind = ItemKind.WALLPAPER

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val frame = WallpaperCodec.readFrom(source)
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable wallpaper payload")
        val bytes = frame.imageBytes

        if (bytes.size.toLong() > MAX_WALLPAPER_BYTES) {
            return ApplyOutcome(ItemStatus.OVERSIZE, "wallpaper exceeds the $MAX_WALLPAPER_BYTES byte cap")
        }
        if (ImageFormat.sniff(bytes) == null) {
            return ApplyOutcome(ItemStatus.SKIPPED, "not an allowlisted image (PNG/JPEG/WebP)")
        }

        // Decompression-bomb gate: bounds-only decode, never the pixels. A null result means the
        // declared image did not decode (truncated/corrupt); oversized bounds mean a likely bomb.
        val bounds = runCatching { store.decodeBounds(bytes) }.getOrNull()
            ?: return ApplyOutcome(ItemStatus.SKIPPED, "image bounds undecodable")
        if (bounds.width <= 0 || bounds.height <= 0) {
            return ApplyOutcome(ItemStatus.SKIPPED, "non-positive image dimensions")
        }
        if (bounds.width.toLong() * bounds.height.toLong() > MAX_PIXELS) {
            return ApplyOutcome(
                ItemStatus.SKIPPED,
                "image ${bounds.width}x${bounds.height} exceeds the $MAX_PIXELS pixel cap",
            )
        }

        val wrote = runCatching { store.setStream(frame.header.surface, bytes) }.getOrDefault(false)
        return if (wrote) {
            ApplyOutcome(ItemStatus.OK, "set ${frame.header.surface.name.lowercase()} wallpaper")
        } else {
            ApplyOutcome(ItemStatus.WRITE_ERROR, "platform refused the wallpaper write")
        }
    }
}

/**
 * Magic-byte image sniffing — the format allowlist (PRP-02 §7.2). The sender's declared MIME is
 * advisory; the real format is re-derived from the leading bytes so a "declared PNG" that is
 * actually something else is rejected. Only PNG/JPEG/WebP are accepted: no SVG/exotic decoders.
 */
internal enum class ImageFormat(val mime: String) {
    PNG("image/png"),
    JPEG("image/jpeg"),
    WEBP("image/webp"),
    ;

    companion object {
        private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_SIG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        fun sniff(bytes: ByteArray): ImageFormat? = when {
            bytes.startsWith(PNG_SIG) -> PNG
            bytes.startsWith(JPEG_SIG) -> JPEG
            isWebp(bytes) -> WEBP
            else -> null
        }

        // WebP = "RIFF" + 4-byte size + "WEBP".
        private fun isWebp(bytes: ByteArray): Boolean =
            bytes.size >= 12 &&
                bytes.regionMatches(0, "RIFF") &&
                bytes.regionMatches(8, "WEBP")

        private fun ByteArray.startsWith(sig: ByteArray): Boolean {
            if (size < sig.size) return false
            for (i in sig.indices) if (this[i] != sig[i]) return false
            return true
        }

        private fun ByteArray.regionMatches(offset: Int, ascii: String): Boolean {
            if (offset + ascii.length > size) return false
            for (i in ascii.indices) if (this[offset + i] != ascii[i].code.toByte()) return false
            return true
        }
    }
}
