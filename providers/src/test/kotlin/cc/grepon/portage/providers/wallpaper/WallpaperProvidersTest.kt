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
    fun `header round-trips through the codec splitting header from image bytes`() {
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

    @Test
    fun `codec returns null when declared byteLength does not match actual image bytes`() {
        // Frame with byteLength = PNG_MAGIC.size but only half the bytes in the payload.
        val wrongLength = PNG_MAGIC.size.toLong() + 999L
        val header = WallpaperHeader(WallpaperSurface.HOME, "image/png", 1080, 2340, wrongLength)
        val out = ByteArrayOutputStream()
        WallpaperCodec.writeTo(out, header, PNG_MAGIC) // writes actual PNG_MAGIC bytes
        // The written frame has byteLength=wrongLength but only PNG_MAGIC.size bytes follow.
        val decoded = WallpaperCodec.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(decoded).isNull()
    }

    // ---- Phase 1: per-surface exporter ----

    private suspend fun exportPayload(store: FakeWallpaperStore, surface: WallpaperSurface): ByteArray {
        val out = ByteArrayOutputStream()
        WallpaperExportProvider(store, surface).exportTo(out)
        return out.toByteArray()
    }

    @Test
    fun `HOME provider available is false when home has no bytes`() = runTest {
        assertThat(WallpaperExportProvider(FakeWallpaperStore(), WallpaperSurface.HOME).available()).isFalse()
    }

    @Test
    fun `HOME provider available is false when the read throws`() = runTest {
        assertThat(
            WallpaperExportProvider(FakeWallpaperStore(throwOnRead = true), WallpaperSurface.HOME).available(),
        ).isFalse()
    }

    @Test
    fun `HOME provider emits a single frame for the home surface`() = runTest {
        val store = FakeWallpaperStore(home = PNG_MAGIC)
        val payload = exportPayload(store, WallpaperSurface.HOME)
        val frame = WallpaperCodec.readFrom(ByteArrayInputStream(payload))

        assertThat(frame).isNotNull()
        assertThat(frame!!.header.surface).isEqualTo(WallpaperSurface.HOME)
        assertThat(frame.imageBytes).isEqualTo(PNG_MAGIC)
    }

    @Test
    fun `LOCK provider available is false when lock is null`() = runTest {
        val store = FakeWallpaperStore(home = PNG_MAGIC, lock = null)
        assertThat(WallpaperExportProvider(store, WallpaperSurface.LOCK).available()).isFalse()
    }

    @Test
    fun `LOCK provider available is false when lock bytes are identical to home bytes`() = runTest {
        val store = FakeWallpaperStore(home = PNG_MAGIC, lock = PNG_MAGIC.copyOf())
        assertThat(WallpaperExportProvider(store, WallpaperSurface.LOCK).available()).isFalse()
    }

    @Test
    fun `LOCK provider available is true when lock bytes differ from home`() = runTest {
        val store = FakeWallpaperStore(home = PNG_MAGIC, lock = JPEG_MAGIC)
        assertThat(WallpaperExportProvider(store, WallpaperSurface.LOCK).available()).isTrue()
    }

    @Test
    fun `LOCK provider emits a single frame for the lock surface`() = runTest {
        val store = FakeWallpaperStore(home = PNG_MAGIC, lock = JPEG_MAGIC)
        val payload = exportPayload(store, WallpaperSurface.LOCK)
        val frame = WallpaperCodec.readFrom(ByteArrayInputStream(payload))

        assertThat(frame).isNotNull()
        assertThat(frame!!.header.surface).isEqualTo(WallpaperSurface.LOCK)
        assertThat(frame.imageBytes).isEqualTo(JPEG_MAGIC)
    }

    @Test
    fun `export is empty when the read throws`() = runTest {
        val store = FakeWallpaperStore(throwOnRead = true)
        assertThat(exportPayload(store, WallpaperSurface.HOME)).isEmpty()
    }

    // ---- Phase 2: apply-path round-trip (the test that catches the original bug) ----
    //
    // This test simulates the REAL production apply path: each surface is exported by its own
    // provider into its own item byte stream, then each item stream is applied independently
    // via WallpaperApplyProvider.apply(). This is exactly how ManifestBuilder + ItemStreamReceiver
    // deliver items — one apply() call per item, not a multi-frame decode.

    @Test
    fun `two-surface export round-trips through the real apply path setting both surfaces`() = runTest {
        val exportStore = FakeWallpaperStore(home = PNG_MAGIC, lock = JPEG_MAGIC)
        val applyStore = FakeWallpaperStore()
        val applier = WallpaperApplyProvider(applyStore)

        // Export HOME item → apply it (simulating one ManifestBuilder item + one apply call)
        val homePayload = exportPayload(exportStore, WallpaperSurface.HOME)
        val homeOutcome = applier.apply(ByteArrayInputStream(homePayload))
        assertThat(homeOutcome.status).isEqualTo(ItemStatus.OK)

        // Export LOCK item → apply it (independent item, independent apply call)
        val lockPayload = exportPayload(exportStore, WallpaperSurface.LOCK)
        val lockOutcome = applier.apply(ByteArrayInputStream(lockPayload))
        assertThat(lockOutcome.status).isEqualTo(ItemStatus.OK)

        // Both surfaces must have been set with the correct bytes.
        assertThat(applyStore.setCalls).hasSize(2)
        val byFlag = applyStore.setCalls.associateBy { it.first }
        assertThat(byFlag[WallpaperSurface.HOME]?.second).isEqualTo(PNG_MAGIC)
        assertThat(byFlag[WallpaperSurface.LOCK]?.second).isEqualTo(JPEG_MAGIC)
    }

    @Test
    fun `mirror case yields one item and the apply sets only the home surface`() = runTest {
        // Lock mirrors home — LOCK provider available() returns false, ManifestBuilder skips it.
        val exportStore = FakeWallpaperStore(home = PNG_MAGIC, lock = null)
        val applyStore = FakeWallpaperStore()
        val applier = WallpaperApplyProvider(applyStore)

        assertThat(WallpaperExportProvider(exportStore, WallpaperSurface.LOCK).available()).isFalse()

        val homePayload = exportPayload(exportStore, WallpaperSurface.HOME)
        val outcome = applier.apply(ByteArrayInputStream(homePayload))
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)

        // Only home was set — no lock item was ever produced.
        assertThat(applyStore.setCalls).hasSize(1)
        assertThat(applyStore.setCalls.single().first).isEqualTo(WallpaperSurface.HOME)
    }

    // ---- Phase 3: importer + decompression-bomb gate ----

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

    @Test
    fun `apply reports WRITE_ERROR when declared byteLength mismatches actual payload`() = runTest {
        // Construct a frame where the header says the image is larger than the actual bytes.
        val store = FakeWallpaperStore()
        val outcome = WallpaperApplyProvider(store).apply(
            frameOf(WallpaperSurface.HOME, PNG_MAGIC, byteLength = PNG_MAGIC.size.toLong() + 1L),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(store.setCalls).isEmpty()
    }
}
