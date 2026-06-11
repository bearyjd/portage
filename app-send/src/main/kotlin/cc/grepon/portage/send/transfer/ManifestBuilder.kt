/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send.transfer

import cc.grepon.portage.model.ItemMeta
import cc.grepon.portage.model.TransferManifest
import cc.grepon.portage.providers.ExportProvider
import java.io.File

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
) {

    suspend fun build(): StagedManifest {
        stagingDir.mkdirs()
        val staged = mutableListOf<StagedItem>()
        var nextId = 1

        for (provider in providers) {
            if (!runCatching { provider.available() }.getOrDefault(false)) continue

            val itemId = nextId
            val file = File(stagingDir, "item-$itemId-${provider.kind.name.lowercase()}.bin")
            val exported = try {
                file.outputStream().use { provider.exportTo(it) }
                true
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                false
            }
            if (!exported || file.length() == 0L) {
                runCatching { file.delete() }
                continue
            }

            val sha256 = file.inputStream().use { sha256Hex(it) }
            staged += StagedItem(
                meta = ItemMeta(
                    itemId = itemId,
                    kind = provider.kind,
                    size = file.length(),
                    sha256 = sha256,
                    displayName = provider.displayName,
                    group = provider.group,
                ),
                file = file,
            )
            nextId++
        }

        return StagedManifest(
            manifest = TransferManifest(
                senderName = senderName,
                items = staged.map { it.meta },
                totalBytes = staged.sumOf { it.meta.size },
            ),
            items = staged,
        )
    }
}
