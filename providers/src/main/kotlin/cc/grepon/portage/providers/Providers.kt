/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import java.io.InputStream
import java.io.OutputStream

/**
 * Sender side of a Tier-0 domain (contacts, calendar, call log, SMS, app inventory,
 * SAFE system settings). Works with normal runtime permissions, no privilege bridge
 * (portage-prp-prompt.md §3, Tier 0).
 *
 * Implementations MUST degrade gracefully when their permission is denied or the domain
 * is empty: [available] returns false (so the item never enters the manifest) and
 * [exportTo] writes an empty payload — neither ever throws for a denied permission.
 */
interface ExportProvider {

    val kind: ItemKind

    /** Manifest display fields ([cc.grepon.portage.model.ItemMeta.displayName]/`group`). */
    val displayName: String
    val group: String

    /** Whether this domain has anything to offer (permission granted AND data present). */
    suspend fun available(): Boolean

    /** Stream this domain's serialized form (vCard/ICS/JSON-lines/JSON) into [sink]. */
    suspend fun exportTo(sink: OutputStream)
}

/**
 * What applying one staged item produced. The transport layer owns item ids; providers
 * report only status + a human-readable detail line for the done-summary.
 */
data class ApplyOutcome(val status: ItemStatus, val detail: String? = null)

/**
 * Receiver side of a Tier-0 domain: apply a staged, hash-verified item read from a stream.
 * MUST be best-effort and per-record resilient — a single bad record never aborts the item,
 * and a failed item never aborts the batch (PROTOCOL.md §5).
 */
interface ApplyProvider {

    val kind: ItemKind

    suspend fun apply(source: InputStream): ApplyOutcome
}

/**
 * Maps a manifest [ItemKind] to its compiled apply handler. The receiver NEVER acts on a
 * kind it does not recognize (THREAT_MODEL.md, malicious-sender row): an unregistered kind
 * yields [ItemStatus.UNKNOWN_KIND] without touching the payload.
 */
class ApplyProviderRegistry(providers: List<ApplyProvider>) {

    private val byKind: Map<ItemKind, ApplyProvider> = providers.associateBy { it.kind }

    fun forKind(kind: ItemKind): ApplyProvider? = byKind[kind]

    suspend fun apply(kind: ItemKind, source: InputStream): ApplyOutcome =
        byKind[kind]?.apply(source)
            ?: ApplyOutcome(ItemStatus.UNKNOWN_KIND, "no apply handler for '${kind.wire}'")
}
