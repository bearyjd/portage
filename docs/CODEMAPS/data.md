<!-- Generated: 2026-06-21 | Source: core-model, settings-catalog | Token estimate: ~650 -->

# Data model — wire protocol & allowlists

No database. "Data" is the LAN wire protocol (CBOR) + compiled safety allowlists.

## ItemKind registry (`core-model/Manifest.kt`) — APPEND-ONLY wire enum
| wire | tier | | wire | tier |
|------|------|-|------|------|
| contacts.vcf | TIER0 | | wallpaper | TIER0 |
| calendar.ics | TIER0 | | sound.selection | TIER0 |
| calllog | TIER0 | | bluetooth.devices | TIER0 |
| sms | TIER0 | | app.backup.relay | TIER0 |
| inventory | TIER0 | | **apk** | **TIER1** |
| | | | **settings** | **TIER1** |

Tier1 needs a one-shot grant via adb-bridge; Tier0 writes via normal Android APIs. Wire strings are
append-only (never renumber/reuse) so an old peer degrades gracefully.

## Protocol messages (`core-model/Messages.kt`, `MessageType`)
```
Hello → Manifest(TransferManifest) → Select(want[], resume[]) →
  ItemBegin → ItemData(chunk) → ItemEnd(sha256) → ItemAck(ItemResult) →
BatchEnd(sent[], summary) → BatchAck(results[]) ;  Ping (keepalive)
ItemStatus = OK · SKIPPED · HASH_MISMATCH · WRITE_ERROR · UNKNOWN_KIND · OVERSIZE
TransferManifest { items: ItemMeta[ id, kind, size, hash, … ] }   ResumePoint(itemId, offset)
Pairing.kt — QR PSK pairing payload
```

## Safety allowlists
- `settings-catalog/SettingsAllowlist` (118L): compiled SAFE allowlist; `SettingKey` + `Validation`.
  Receiver applies a settings key ONLY if allowlisted AND value-validated. Guardrail test:
  "no non-DEVICE_SPECIFIC key is unvalidated" (keep green).
- `providers/apk/ApkContainerValidation`: fileCount ≤ 64 (MAX_APK_FILES), per-item ≤ 1 GiB,
  split-name regex (pinned in `:providers` + `:adb-bridge` lockstep tests).

## Defense limits
per-item 64 MiB stream cap · u16 frame cap · single-use PSK · 10s handshake timeout · item-count cap.
