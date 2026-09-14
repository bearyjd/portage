/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only the pre-v6 cache staging directory is obsolete; the no-backup lineage store owns itself. */
internal suspend fun cleanupLegacyStaging(
    cacheDir: File,
    noBackupFilesDir: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) = withContext(ioDispatcher) {
    val context = currentCoroutineContext()
    val legacy = File(cacheDir, "portage-staging")
    val retained = File(noBackupFilesDir, "portage-staging").canonicalFile.toPath()
    val legacyCanonical = legacy.canonicalFile.toPath()
    require(legacyCanonical.parent == cacheDir.canonicalFile.toPath() && !retained.startsWith(legacyCanonical)) {
        "legacy staging must be isolated from retained move data"
    }
    if (!Files.exists(legacy.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@withContext
    // walkFileTree does not follow symlinks; an old cache link cannot target retained data.
    Files.walkFileTree(legacy.toPath(), object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            context.ensureActive()
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
            context.ensureActive()
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
    Unit
}
