package com.ventouxlabs.portage.model

/** Pure identity validation. Call before constructing UI, allocating files, or touching a store. */
object ManifestValidation {
    private val identity = Regex("[0-9a-f]{32}")
    private val hash = Regex("[0-9a-f]{64}")
    const val MAX_ITEMS = 10_000

    fun requireIdentity(value: String) {
        require(identity.matches(value)) { "identity must be 32 lowercase hexadecimal characters" }
    }

    fun requireItem(item: ItemMeta) {
        requireIdentity(item.occurrenceId)
        require(item.itemId >= 0) { "negative item index" }
        require(item.wireSchemaVersion > 0) { "invalid item wire-schema version" }
        require(item.size >= 0) { "negative declared size" }
        require(hash.matches(item.sha256)) { "SHA-256 must be 64 lowercase hexadecimal characters" }
    }

    fun requireIdentities(manifest: TransferManifest) {
        requireIdentity(manifest.lineageId)
        require(manifest.items.size <= MAX_ITEMS) { "manifest exceeds item count limit" }
        manifest.items.forEach { requireIdentity(it.occurrenceId) }
        require(manifest.items.map { it.occurrenceId }.toSet().size == manifest.items.size) { "duplicate occurrence id" }
        require(manifest.items.map { it.itemId }.toSet().size == manifest.items.size) { "duplicate item index" }
    }

    fun requireValid(manifest: TransferManifest) {
        requireIdentities(manifest)
        manifest.items.forEach(::requireItem)
        var total = 0L
        manifest.items.forEach {
            require(it.size <= Long.MAX_VALUE - total) { "manifest size overflow" }
            total += it.size
        }
        require(manifest.totalBytes == total) { "manifest total disagrees with items" }
    }
}
