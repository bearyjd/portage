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
    // APPEND-ONLY wire bump (PRP-04 §4): the three default-sound role selections (ringtone /
    // notification / alarm) as a small TEXT snapshot. Tier 0 — applied via the normal
    // Settings.System "modify system settings" special access, no privilege bridge. Phase 1
    // carries built-in/system selections ONLY: the receiver re-resolves each built-in to a LOCAL
    // URI by title and never writes a sender-supplied URI verbatim (THREAT_MODEL.md). Phase 2
    // (custom user-supplied sound FILES → a SOUND_FILE binary kind + MediaStore re-register + URI
    // remap) is DEFERRED to a follow-up PR. As with WALLPAPER, an older receiver lacking this
    // handler degrades via UNKNOWN_KIND (Providers.kt, ApplyProviderRegistry); PROTOCOL_VERSION
    // (Pairing.kt) is NOT bumped — it versions the QR trust anchor, not the kind vocabulary.
    SOUND_SELECTION("sound.selection", Tier.TIER0),
    // APPEND-ONLY wire bump (PRP-07, public-API approach): the list of bonded Bluetooth devices
    // (display name + MAC + device type/major-class) as a small JSON snapshot. Tier 0 — the SENDER
    // reads its own roster via the PUBLIC, NON-PRIVILEGED BluetoothAdapter.getBondedDevices() API,
    // guarded only by the normal BLUETOOTH_CONNECT runtime permission (NO ADB bridge, NO privilege
    // escalation — the on-device spike confirmed the privileged bt_config.conf read is DENIED to
    // shell uid anyway, see docs/prp/features/SPIKE-RESULTS-2026-06-12.md). Phase 1 transfers the
    // LIST ONLY and presents it as a "re-pair each on this device" checklist; it NEVER carries link
    // keys / bond secrets (cryptographically controller-bound and non-transferable — re-pairing is
    // unavoidable and honest) and NEVER calls createBond (assisted programmatic re-pair is DEFERRED
    // to a Phase 2 follow-up). As with WALLPAPER/SOUND_SELECTION, an older receiver lacking this
    // handler degrades via UNKNOWN_KIND (Providers.kt, ApplyProviderRegistry); PROTOCOL_VERSION
    // (Pairing.kt) is NOT bumped — it versions the QR trust anchor, not the append-only kind vocab.
    BLUETOOTH_DEVICES("bluetooth.devices", Tier.TIER0),
    // APPEND-ONLY wire bump (PRP-06 §4): an OPAQUE, user-initiated app-backup export ferried
    // device-to-device — Signal/Molly (message history) and Aegis (2FA vault) keep their OWN
    // encrypted backups and opt out of system backup by design. portage is a COURIER here, NOT a
    // backup engine: the USER triggers the app's native export (portage cannot — these apps deny
    // programmatic backup), the USER points portage at the resulting file via SAF, and portage
    // relays it as opaque bytes it NEVER decrypts, parses, or interprets. The passphrase never
    // touches portage. This is categorically NOT the forbidden SEEDVAULT_BLOB below: that would
    // imply portage owns/produces an app-DATA backup; this relays a file the USER already made
    // (PRP-06 §2 deciding test — "portage must never be the thing that creates the backup"). Tier 0
    // — pure file transfer + guided UX, no privilege. The per-item byte cap is raised FOR THIS KIND
    // ONLY (ItemStreamReceiver.maxBytesByKind) because app backups routinely exceed the 64 MiB
    // Tier-0 ceiling; the raise must NEVER leak into the Tier-0/PII item paths. As with the kinds
    // above, an older receiver lacking this handler degrades via UNKNOWN_KIND (Providers.kt,
    // ApplyProviderRegistry); PROTOCOL_VERSION (Pairing.kt) is NOT bumped.
    APP_BACKUP_RELAY("app.backup.relay", Tier.TIER0),
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
