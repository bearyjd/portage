/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.model

import kotlinx.serialization.Serializable

/** Capability tier an item belongs to. See docs/prp/portage-prp-prompt.md §3. */
@Serializable
enum class Tier { TIER0, TIER1 }

/**
 * What a transferable item is. The string wire form lives in [wire]; it doubles as the
 * receiver's dispatch key into its compiled handler set — the receiver NEVER acts on a
 * kind it does not recognize (see docs/prp/THREAT_MODEL.md, malicious-sender row).
 */
@Serializable
enum class ItemKind(val wire: String, val tier: Tier) {
    CONTACTS_VCF("contacts.vcf", Tier.TIER0),
    CALENDAR_ICS("calendar.ics", Tier.TIER0),
    CALL_LOG("calllog", Tier.TIER0),
    SMS("sms", Tier.TIER0),
    APP_INVENTORY("inventory", Tier.TIER0),
    APK("apk", Tier.TIER1),
    SETTINGS("settings", Tier.TIER1),
    // APPEND-ONLY wire bump (PRP-02 §4): the active home/lock wallpaper image, the first kind
    // whose payload is a large binary blob rather than structured text. Adding an enum entry is
    // backward-compatible by design — an older receiver lacking this handler returns UNKNOWN_KIND
    // (Providers.kt, ApplyProviderRegistry) rather than crashing (PROTOCOL.md §3-5). The pairing
    // PROTOCOL_VERSION (Pairing.kt) is NOT bumped: it versions the QR trust anchor, not the
    // append-only kind vocabulary, and bumping it would reject every existing v1 pairing QR.
    WALLPAPER("wallpaper", Tier.TIER0),
    // NOTE: no SEEDVAULT_BLOB. Couriering a Seedvault file would imply app-DATA transfer,
    // which contradicts the Seedvault division of labor (PRP §2, DEVILS_ADVOCATE Q5). If
    // ever wanted, it goes in a v2 protocol bump behind explicit "carrying, not backing up"
    // UX — never silently in the frozen v1 enum.
}

/**
 * Per-item metadata advertised in the manifest. Display fields are NOT trusted for any
 * filesystem operation — the receiver stages every item under a generated filename
 * (THREAT_MODEL.md, path-traversal mitigation).
 */
@Serializable
data class ItemMeta(
    val itemId: Int,
    val kind: ItemKind,
    val size: Long,
    val sha256: String,
    val displayName: String,
    val group: String,
)

/** The sender's advertised inventory. Receiver selects a subset from this. */
@Serializable
data class TransferManifest(
    val senderName: String,
    val items: List<ItemMeta>,
    val totalBytes: Long,
)
