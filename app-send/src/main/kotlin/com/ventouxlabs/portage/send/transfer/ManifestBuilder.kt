/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.transfer

import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.ManifestValidation
import com.ventouxlabs.portage.model.TransferManifest
import com.ventouxlabs.portage.providers.ExportProvider
import java.io.File
import java.security.SecureRandom
import java.io.FilterOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One advertised item: its manifest metadata plus the staged payload on disk. */
data class StagedItem(val meta: ItemMeta, val file: File)

/** The built manifest plus its staged payloads, aligned by item id. */
data class StagedManifest(
    val manifest: TransferManifest,
    val items: List<StagedItem>,
) {
    fun itemById(itemId: Int): StagedItem? = items.firstOrNull { it.meta.itemId == itemId }

    /** Delete every staged payload (call on reset/done; payloads hold personal data). */
    fun cleanup() {
        items.forEach { runCatching { it.file.delete() } }
    }
}

/**
 * Exports every available provider into [stagingDir] and assembles the [TransferManifest]
 * with real sizes + sha256 hashes (PROTOCOL.md §4, manifest-first). Faulty providers are
 * EXCLUDED, never fatal: available() throwing, exportTo() throwing, or an empty export all
 * just drop that item (DEVILS_ADVOCATE: degrade gracefully, don't crash the home screen).
 */
class ManifestBuilder(
    private val providers: List<ExportProvider>,
    private val stagingDir: File,
    private val senderName: String,
    private val lineageId: String,
    private val random: SecureRandom = SecureRandom(),
    private val newOccurrenceId: () -> String = {
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun build(): StagedManifest = withContext(ioDispatcher) {
        val context = currentCoroutineContext()
        ManifestValidation.requireIdentity(lineageId)
        stagingDir.mkdirs()
        val staged = mutableListOf<StagedItem>()
        var nextId = 1

        for (provider in providers) {
            context.ensureActive()
            val available = try { provider.available() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (!available) continue

            val itemId = nextId
            val occurrenceId = newOccurrenceId()
            ManifestValidation.requireIdentity(occurrenceId)
            check(staged.none { it.meta.occurrenceId == occurrenceId }) { "duplicate generated occurrence" }
            val file = File(stagingDir, "$lineageId-$occurrenceId.bin")
            check(file.createNewFile()) { "staging occurrence already exists" }
            val exported = try {
                file.outputStream().use { output ->
                    provider.exportTo(object : FilterOutputStream(output) {
                        override fun write(value: Int) { context.ensureActive(); out.write(value) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            context.ensureActive()
                            out.write(bytes, offset, length)
                        }
                    })
                }
                true
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                false
            }
            if (!exported || file.length() == 0L) {
                runCatching { file.delete() }
                continue
            }

            val sha256 = file.inputStream().use { sha256Hex(it, context::ensureActive) }
            staged += StagedItem(
                meta = ItemMeta(
                    itemId = itemId,
                    kind = provider.kind,
                    size = file.length(),
                    sha256 = sha256,
                    displayName = provider.displayName,
                    group = provider.group,
                    occurrenceId = occurrenceId,
                ),
                file = file,
            )
            nextId++
        }

        StagedManifest(
            manifest = TransferManifest(
                senderName = senderName,
                items = staged.map { it.meta },
                totalBytes = staged.sumOf { it.meta.size },
                lineageId = lineageId,
            ),
            items = staged,
        )
    }
}
