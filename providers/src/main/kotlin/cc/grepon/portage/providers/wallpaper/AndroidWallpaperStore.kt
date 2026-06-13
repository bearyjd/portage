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

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream

/**
 * Thin [WallpaperManager] adapter behind [WallpaperStore] (Tier 0, PRP-02 §3).
 *
 * READ uses `getWallpaperFile(which)` to yield the ORIGINAL active-wallpaper bytes (lossless,
 * correct dimensions) rather than a rasterized Drawable. A null descriptor means the surface has
 * no static file (lock mirrors home, or home is a live wallpaper) — returned as null so the
 * exporter omits the item. No storage permission is requested: reading one's own active wallpaper
 * file needs none on modern Android.
 *
 * WRITE uses `setStream(stream, null, true, which)` so the platform decodes/scales the raw bytes
 * itself — the receiver never holds a full decoded bitmap (the decompression-bomb gate in
 * [WallpaperApplyProvider] still runs a bounds-only check first). Needs only the normal, install-
 * time SET_WALLPAPER permission; NO privilege bridge is involved (Tier 0).
 *
 * [decodeBounds] is the platform half of the decompression-bomb gate: `inJustDecodeBounds = true`
 * decodes dimensions WITHOUT allocating the pixels.
 */
class AndroidWallpaperStore(context: Context) : WallpaperStore {

    private val wallpaperManager = WallpaperManager.getInstance(context.applicationContext)

    override fun read(surface: WallpaperSurface): ByteArray? = runCatching {
        wallpaperManager.getWallpaperFile(frameworkFlag(surface))?.let { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }.getOrNull()

    override fun decodeBounds(bytes: ByteArray): ImageBounds? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            ImageBounds(options.outWidth, options.outHeight)
        } else {
            null
        }
    }.getOrNull()

    override fun setStream(surface: WallpaperSurface, bytes: ByteArray): Boolean = runCatching {
        ByteArrayInputStream(bytes).use { stream ->
            wallpaperManager.setStream(stream, null, true, frameworkFlag(surface))
        }
        true
    }.getOrDefault(false)

    /** Map the typed surface to the real framework FLAG_* constant (never a raw wire int). */
    private fun frameworkFlag(surface: WallpaperSurface): Int = when (surface) {
        WallpaperSurface.HOME -> WallpaperManager.FLAG_SYSTEM
        WallpaperSurface.LOCK -> WallpaperManager.FLAG_LOCK
    }
}
