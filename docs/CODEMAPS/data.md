<!-- Generated: 2026-06-21 | Source: core-model, settings-catalog | Token estimate: ~650 -->

# Data model — wire protocol & allowlists

Data is the LAN wire protocol (CBOR), a durable versioned lineage snapshot, and compiled safety allowlists.

## ItemKind registry (`core-model/Manifest.kt`) — APPEND-ONLY wire enum
| wire | tier | | wire | tier |
|------|------|-|------|------|
| contacts.vcf | TIER0 | | wallpaper | TIER0 |
| calendar.ics | TIER0 | | sound.selection | TIER0 |
| calllog | TIER0 | | bluetooth.devices | TIER0 |
| sms | TIER0 | | app.backup.relay | TIER0 |
| mms | TIER0 | | user.file | TIER0 |
| inventory | TIER0 | | **apk** | **TIER1** |
| sound.file | TIER0 | | **settings** | **TIER1** |

Tier1 needs a one-shot grant via adb-bridge; Tier0 writes via normal Android APIs. Wire strings are
append-only (never renumber/reuse) so an old peer fails during protocol-version validation rather
than applying an unknown kind.

## Protocol messages (`core-model/Messages.kt`, `MessageType`)
```
Hello → LineageInit/LineageResume → LineageAck → Manifest(TransferManifest) → Select(want[], resume[]) →
  ItemBegin → ItemData(chunk) → ItemEnd(sha256) → ItemAck(ItemResult) →
BatchEnd(sent[], summary) → BatchAck(results[]) ;  Ping (keepalive)
ItemStatus = OK · SKIPPED · HASH_MISMATCH · WRITE_ERROR · UNKNOWN_KIND · OVERSIZE · UNKNOWN_INTERRUPTED
TransferManifest { lineageId, items: ItemMeta[ id, occurrenceId, kind, wireSchemaVersion, size, hash, … ] }
Pairing.kt — v6 QR PSK pairing payload with NEW/RESUME mode; no lineage or resume secret
Cancel(lineageId) → CancelAck(lineageId) ; peer deletion remains unconfirmed until acknowledgement
```

## Durable lineage (`core-lineage/LineageRepository.kt`)

`noBackupFilesDir/lineage/lineage.json` atomically stores the active lineage, credential,
prepared manifest, checkpoints, and terminal tombstones. `CheckpointKey.from()` validates
lineage + occurrence + kind + wire schema + size + SHA-256 before every lookup/mutation.
Two byte-identical selected files have separate occurrence keys. Staging uses owned files
under `lineage/staging`, revalidated by size and full hash before whole-item reuse.

`PREPARED → RECEIVED_VERIFIED → APPLYING → APPLIED_DURABLE`; typed failures do not become
success on restart. Reopen maps APPLYING to UNKNOWN_INTERRUPTED. Apply is never skipped
without provider-specific evidence (unavailable in PR 0a). Exact deadlines are 24 hours for
staging and 30 days for credentials/checkpoints since the last authenticated activity.

## Safety allowlists
- `settings-catalog/SettingsAllowlist` (118L): compiled SAFE allowlist; `SettingKey` + `Validation`.
  Receiver applies a settings key ONLY if allowlisted AND value-validated. Guardrail test:
  "no non-DEVICE_SPECIFIC key is unvalidated" (keep green).
- `providers/apk/ApkContainerValidation`: fileCount ≤ 64 (MAX_APK_FILES), per-item ≤ 1 GiB,
  split-name regex (pinned in `:providers` + `:adb-bridge` lockstep tests).

## Defense limits
per-item 64 MiB stream cap · u16 frame cap · single-use PSK · 10s handshake timeout · item-count cap.
