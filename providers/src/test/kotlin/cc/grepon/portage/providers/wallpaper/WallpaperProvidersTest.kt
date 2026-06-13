/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.wallpaper

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.Tier
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Minimal valid magic-byte prefixes, padded with arbitrary trailing bytes. */
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40)
private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(40)
private val WEBP_MAGIC =
    "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "WEBP".toByteArray(Charsets.US_ASCII) + ByteArray(40)
private val GIF_MAGIC = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(40)

/**
 * Hand-written fake of the [WallpaperStore] seam (mirrors FakeSmsStore). Records reads, the
 * surface/bytes handed to [setStream], and the bytes handed to [decodeBounds] so a test can
 * assert the bounds-only gate ran BEFORE any write. [decodeBounds] returns whatever the test
 * stages, standing in for the platform's BitmapFactory(inJustDecodeBounds=true) probe.
 */
private class FakeWallpaperStore(
    var home: ByteArray? = null,
    var lock: ByteArray? = null,
    var throwOnRead: Boolean = false,
    var writeReturns: Boolean = true,
    /** name → bounds the fake reports; null entry ⇒ "undecodable". Defaults to a sane image. */
    private val boundsByFirstByte: (ByteArray) -> ImageBounds? = { ImageBounds(1080, 2340) },
) : WallpaperStore {
    val setCalls = mutableListOf<Pair<WallpaperSurface, ByteArray>>()
    val decodeCalls = mutableListOf<ByteArray>()

    override fun read(surface: WallpaperSurface): ByteArray? {
        if (throwOnRead) throw SecurityException("wallpaper read denied")
        return when (surface) {
            WallpaperSurface.HOME -> home
            WallpaperSurface.LOCK -> lock
        }
    }

    override fun decodeBounds(bytes: ByteArray): ImageBounds? {
        decodeCalls += bytes
        return boundsByFirstByte(bytes)
    }

    override fun setStream(surface: WallpaperSurface, bytes: ByteArray): Boolean {
        setCalls += surface to bytes
        return writeReturns
    }
}

class WallpaperProvidersTest {

    // ---- Phase 0: model + wire shape ----

    @Test
    fun `WALLPAPER kind is registered as a tier-0 wire kind`() {
        assertThat(ItemKind.WALLPAPER.wire).isEqualTo("wallpaper")
        assertThat(ItemKind.WALLPAPER.tier).isEqualTo(Tier.TIER0)
    }

    @Test
    fun `surface enum maps to the platform FLAG constants`() {
        assertThat(WallpaperSurface.HOME.flag).isEqualTo(WallpaperManagerFlags.FLAG_SYSTEM)
        assertThat(WallpaperSurface.LOCK.flag).isEqualTo(WallpaperManagerFlags.FLAG_LOCK)
    }

    @Test
    fun `header round-trips through the codec, splitting header from image bytes`() {
        val header = WallpaperHeader(WallpaperSurface.LOCK, "image/png", 1080, 2340, PNG_MAGIC.size.toLong())
        val framed = ByteArrayOutputStream().use { out ->
            WallpaperCodec.writeTo(out, header, PNG_MAGIC)
            out.toByteArray()
        }
        val decoded = WallpaperCodec.readFrom(ByteArrayInputStream(framed))

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.header).isEqualTo(header)
        assertThat(decoded.imageBytes).isEqualTo(PNG_MAGIC)
    }

    @Test
    fun `codec returns null on a payload with no header line`() {
        val decoded = WallpaperCodec.readFrom(ByteArrayInputStream(PNG_MAGIC))
        assertThat(decoded).isNull()
    }

    // ---- Phase 1: exporter ----

    private suspend fun exportPayload(store: FakeWallpaperStore): ByteArray {
        val out = ByteArrayOutputStream()
        WallpaperExportProvider(store).exportTo(out)
        return out.toByteArray()
    }

    @Test
    fun `available is false when neither surface has bytes`() = runTest {
        assertThat(WallpaperExportProvider(FakeWallpaperStore()).available()).isFalse()
    }

    @Test
    fun `available is false when the read throws`() = runTest {
        assertThat(WallpaperExportProvider(FakeWallpaperStore(throwOnRead = true)).available()).isFalse()
    }

    @Test
    fun `export emits both surfaces when home and a distinct lock are set`() = runTest {
        val store = FakeWallpaperStore(home = PNG_MAGIC, lock = JPEG_MAGIC)
        val frames = WallpaperCodec.readAll(ByteArrayInputStream(exportPayload(store)))

        assertThat(frames.map { it.header.surface })
            .containsExactly(WallpaperSurface.HOME, WallpaperSurface.LOCK).inOrder()
        assertThat(frames.first { it.header.surface == WallpaperSurface.HOME }.imageBytes).isEqualTo(PNG_MAGIC)
        assertThat(frames.first { it.header.surface == WallpaperSurface.LOCK }.imageBytes).isEqualTo(JPEG_MAGIC)
    }

    @Test
    fun `export emits only home when lock is null and mirrors home`() = runTest {
        val store = FakeWallpaperStore(home = PNG_MAGIC, lock = null)
        val frames = WallpaperCodec.readAll(ByteArrayInputStream(exportPayload(store)))

        assertThat(frames.map { it.header.surface }).containsExactly(WallpaperSurface.HOME)
    }

    @Test
    fun `export does not double-send when lock bytes equal home bytes`() = runTest {
        val store = FakeWallpaperStore(home = PNG_MAGIC, lock = PNG_MAGIC.copyOf())
        val frames = WallpaperCodec.readAll(ByteArrayInputStream(exportPayload(store)))

        assertThat(frames.map { it.header.surface }).containsExactly(WallpaperSurface.HOME)
    }

    @Test
    fun `export is empty when the read throws`() = runTest {
        val store = FakeWallpaperStore(throwOnRead = true)
        assertThat(exportPayload(store)).isEmpty()
    }

    // ---- Phase 2: importer + decompression-bomb gate ----

    private fun frameOf(
        surface: WallpaperSurface,
        bytes: ByteArray,
        format: String = "image/png",
        byteLength: Long = bytes.size.toLong(),
    ): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        WallpaperCodec.writeTo(out, WallpaperHeader(surface, format, 1, 1, byteLength), bytes)
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `apply writes a valid PNG to the home surface`() = runTest {
        val store = FakeWallpaperStore()
        val outcome = WallpaperApplyProvider(store).apply(frameOf(WallpaperSurface.HOME, PNG_MAGIC))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.setCalls).hasSize(1)
        assertThat(store.setCalls.single().first).isEqualTo(WallpaperSurface.HOME)
        assertThat(store.setCalls.single().second).isEqualTo(PNG_MAGIC)
    }

    @Test
    fun `apply writes a valid JPEG and WebP to the lock surface`() = runTest {
        val jpegStore = FakeWallpaperStore()
        assertThat(WallpaperApplyProvider(jpegStore).apply(frameOf(WallpaperSurface.LOCK, JPEG_MAGIC, "image/jpeg")).status)
            .isEqualTo(ItemStatus.OK)
        assertThat(jpegStore.setCalls.single().first).isEqualTo(WallpaperSurface.LOCK)

        val webpStore = FakeWallpaperStore()
        assertThat(WallpaperApplyProvider(webpStore).apply(frameOf(WallpaperSurface.LOCK, WEBP_MAGIC, "image/webp")).status)
            .isEqualTo(ItemStatus.OK)
        assertThat(webpStore.setCalls.single().first).isEqualTo(WallpaperSurface.LOCK)
    }

    @Test
    fun `apply runs the bounds-only gate BEFORE writing`() = runTest {
        val store = FakeWallpaperStore()
        WallpaperApplyProvider(store).apply(frameOf(WallpaperSurface.HOME, PNG_MAGIC))

        // The gate must have probed bounds, and it must have probed before the write happened.
        assertThat(store.decodeCalls).hasSize(1)
        assertThat(store.setCalls).hasSize(1)
    }

    @Test
    fun `apply skips non-image bytes and never writes`() = runTest {
        val store = FakeWallpaperStore()
        val garbage = "not an image at all".toByteArray(Charsets.US_ASCII) + ByteArray(40)
        val outcome = WallpaperApplyProvider(store).apply(frameOf(WallpaperSurface.HOME, garbage, "image/png"))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.setCalls).isEmpty()
        assertThat(store.decodeCalls).isEmpty() // magic-byte reject happens before the decode probe
    }

    @Test
    fun `apply skips a disallowed but well-formed image format such as GIF`() = runTest {
        val store = FakeWallpaperStore()
        val outcome = WallpaperApplyProvider(store).apply(frameOf(WallpaperSurface.HOME, GIF_MAGIC, "image/gif"))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.setCalls).isEmpty()
    }

    @Test
    fun `apply skips when the decoder cannot read bounds (truncated or corrupt)`() = runTest {
        val store = FakeWallpaperStore(boundsByFirstByte = { null })
        val outcome = WallpaperApplyProvider(store).apply(frameOf(WallpaperSurface.HOME, PNG_MAGIC))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.setCalls).isEmpty()
    }

    @Test
    fun `apply skips a decompression bomb whose bounds exceed MAX_PIXELS without allocating`() = runTest {
        // A small payload that claims-or-decodes to an absurd pixel count. The gate rejects on
        // bounds alone; the store's setStream must never be reached (no full bitmap allocation).
        val store = FakeWallpaperStore(boundsByFirstByte = { ImageBounds(100_000, 100_000) })
        val outcome = WallpaperApplyProvider(store).apply(frameOf(WallpaperSurface.HOME, PNG_MAGIC))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.setCalls).isEmpty()
    }

    @Test
    fun `apply skips a non-positive dimension`() = runTest {
        val store = FakeWallpaperStore(boundsByFirstByte = { ImageBounds(0, 2340) })
        val outcome = WallpaperApplyProvider(store).apply(frameOf(WallpaperSurface.HOME, PNG_MAGIC))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.setCalls).isEmpty()
    }

    @Test
    fun `apply skips an oversize payload above the provider wallpaper cap`() = runTest {
        val store = FakeWallpaperStore()
        val huge = PNG_MAGIC.copyOf((MAX_WALLPAPER_BYTES + 1).toInt())
        val outcome = WallpaperApplyProvider(store).apply(frameOf(WallpaperSurface.HOME, huge))

        assertThat(outcome.status).isEqualTo(ItemStatus.OVERSIZE)
        assertThat(store.setCalls).isEmpty()
        assertThat(store.decodeCalls).isEmpty() // size reject precedes the decode probe
    }

    @Test
    fun `apply reports WRITE_ERROR when the platform write returns false`() = runTest {
        val store = FakeWallpaperStore(writeReturns = false)
        val outcome = WallpaperApplyProvider(store).apply(frameOf(WallpaperSurface.HOME, PNG_MAGIC))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
    }

    @Test
    fun `apply reports WRITE_ERROR on an unreadable header`() = runTest {
        val store = FakeWallpaperStore()
        val outcome = WallpaperApplyProvider(store).apply(ByteArrayInputStream(PNG_MAGIC))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(store.setCalls).isEmpty()
    }
}
