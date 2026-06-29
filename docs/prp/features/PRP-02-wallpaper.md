# PRP-02 — Wallpaper (home + lock screen)

> Backlog #2 (`docs/prp/feature-research-2026-06.md` row 2). The cheapest high-visibility win:
> Tier 0, no privilege, but the FIRST portage item kind whose payload is a large binary image
> blob rather than structured text. Read this PRP top-to-bottom before touching `ItemKind`.

## 1. Summary & user value

Transfer the device's **active home-screen and lock-screen wallpaper image bytes** from the old
phone (`app-send`) to the new one (`app-recv`) over the existing Noise LAN channel, then set them
via `WallpaperManager`. No cloud, no media library — just the two images currently in use.

**Sourced signal** (`feature-research-2026-06.md:16`): *"Nearly every competitor moves it"* —
Google's cable transfer, Samsung Smart Switch, OnePlus Clone Phone, Swift, and Migrate all carry
the wallpaper. **Seedvault gap:** Seedvault may carry the *setting reference* but NOT the image
bytes, so a Seedvault restore leaves a default wallpaper — a visible "did my stuff actually move?"
regression portage is positioned to close (`feature-research-2026-06.md:16,37-41`).

User value: the new phone *looks like the old phone* the instant the transfer completes — the
highest perceived-fidelity signal for the lowest engineering cost in the backlog.

## 2. Scope & non-goals

**In scope:** the *active* `FLAG_SYSTEM` (home) wallpaper and, separately, the *active* `FLAG_LOCK`
wallpaper, as raw image bytes, applied on the receiver with `setStream(..., which=…)`.

**Non-goals (hard boundaries):**
- **Not a photo / media-library transfer.** User files are out of scope; this is ONLY the
  wallpaper(s) currently set. No gallery walk, no `MediaStore` enumeration.
- **Clear of Seedvault.** Wallpaper image bytes are *not* app data — Seedvault owns app *data*
  (`CLAUDE.md` scope-discipline line; `Manifest.kt:32-35` no-`seedvault.blob` rule). Wallpaper is
  a device-presentation artifact, squarely in portage's lane.
- **No live wallpaper / `WallpaperService`.** A live wallpaper is an installed app behind the
  app-inventory kind (`ItemKind.APP_INVENTORY`), not an image. If `FLAG_SYSTEM` resolves to a live
  wallpaper component, the exporter reports the surface unavailable (no static bytes to read).
- **No crop/parallax metadata, no per-home-screen-page wallpapers.** v1 sets the full image on
  each surface; the platform applies its own default crop. Recorded as an open question (§9).
- **Owner profile only** (consistent with ADR-001 §2.4 / `CLAUDE.md`).

## 3. Feasibility & privilege — **Tier 0**

`WallpaperManager` (`android.app.WallpaperManager`, `Context.getSystemService`). Confirmed against
the AOSP/Android-16 (GOS compileSdk 36, `CLAUDE.md` Build) API surface; **on-device VERIFY_FIRST
still required** (§9, §8).

**Read (exporter, app-send):**
| Surface | Read path | Permission |
|---|---|---|
| `FLAG_SYSTEM` (home) | `getWallpaperFile(FLAG_SYSTEM)` → `ParcelFileDescriptor`; fall back to `drawable`/`builtInDrawable` if null | **None** for the caller's own active wallpaper |
| `FLAG_LOCK` (lock) | `getWallpaperFile(FLAG_LOCK)` → `ParcelFileDescriptor`; **null means "lock mirrors home"** — do NOT synthesize a second copy, emit only the home item | **None** in current AOSP for reading your *own* device's lock wallpaper file via `getWallpaperFile` |

- `getWallpaperFile(which)` returns a real `ParcelFileDescriptor` we stream from; prefer it over
  `getDrawable()` because it yields the **original bytes** (lossless, correct dimensions) instead
  of a rasterized `Drawable`. `getDrawable()` is the degraded fallback only.
- **`READ_EXTERNAL_STORAGE` is NOT requested.** It is broadly denied-by-default on GOS and is the
  legacy path; `getWallpaperFile` on the *active* wallpaper does not need it on modern Android.
  If VERIFY_FIRST shows GOS gates lock-wallpaper *file* reads, the exporter degrades to
  `getDrawable(FLAG_LOCK)` (rasterized) or drops the lock surface — never requests storage perms.

**Write (importer, app-recv):**
| Op | Path | Permission |
|---|---|---|
| Set home | `setStream(stream, null, true, FLAG_SYSTEM)` | **`SET_WALLPAPER`** (normal, install-time, auto-granted; declared in `app-recv` manifest) |
| Set lock | `setStream(stream, null, true, FLAG_LOCK)` | same |

- `SET_WALLPAPER` is a **normal** permission — install-time, no runtime prompt, no privilege
  bridge. This stays entirely on the Tier-0 data path; `:adb-bridge` is NOT involved and MUST NOT
  be touched (`CLAUDE.md`: only `:adb-bridge` speaks the wire protocol — irrelevant here, but the
  reviewer should confirm no privileged call sneaks in).
- `setStream` lets us hand the platform the **raw bytes** and let *it* decode/scale, so the
  importer never holds a full decoded bitmap (decompression-bomb relevant — §7).

**GOS constraints:** owner-profile only; no GOS-specific wallpaper hardening is known, but the
`getWallpaperFile(FLAG_LOCK)` read path and the `setStream` round-trip are VERIFY_FIRST items
(§8) — a verdict is only valid for the recorded build fingerprint (ADR-001 §2.6 discipline).

## 4. Architecture fit

Wallpaper slots into the **exact same provider seam** as every Tier-0 kind — the only novelty is a
binary payload. Mirror the SMS/settings pair shape (`providers/.../sms/SmsProviders.kt`,
`providers/.../settings/SettingsProviders.kt`):

- **core-model** (`core-model/.../Manifest.kt:24`): add one `ItemKind.WALLPAPER` enum entry,
  `Tier.TIER0`. Enum additions are append-only and require protocol-version review because old
  builds cannot decode unknown enum values. No `ItemMeta` changes:
  `size`/`sha256`/`displayName`/`group` already carry everything (`Manifest.kt:43-51`).
- **providers** (`providers/.../wallpaper/WallpaperProviders.kt`, new): a `WallpaperStore` seam
  (the `ContentResolver`/`WallpaperManager` boundary, mirroring `SmsStore` / `SystemSettingsStore`)
  plus `WallpaperExportProvider : ExportProvider` and `WallpaperApplyProvider : ApplyProvider`
  (`providers/.../Providers.kt:26,52`). Providers stay framework-thin; the Android implementation
  (`AndroidWallpaperStore`) wraps `WallpaperManager` exactly as `AndroidSmsStore` wraps Telephony.
- **transport** is **untouched**. `ItemStreamReceiver` already streams arbitrary bytes chunk-by-
  chunk with an incremental sha256 and the per-item `OVERSIZE` cap (`ItemStreamReceiver.kt:117-119,
  145-146`); `ItemStatus.OVERSIZE` already exists (`Messages.kt:34`). Wallpaper is just the first
  payload that *approaches* `DEFAULT_MAX_ITEM_BYTES = 64L*1024*1024` (`ItemStreamReceiver.kt:219`).
- **send UI** (`app-send/.../MainActivity.kt:95`): add `WallpaperExportProvider(AndroidWallpaperStore(context))`
  to the compiled provider `listOf`. It surfaces as a checklist item like every other kind.
- **recv UI** (`app-recv/.../MainActivity.kt:109`): add `WallpaperApplyProvider(AndroidWallpaperStore(context))`
  to the `ApplyProviderRegistry` `listOf`. Unregistered-kind safety is automatic
  (`Providers.kt:64-73`): an old receiver that lacks the handler returns `UNKNOWN_KIND`, never
  crashes — so a new sender talking to an old receiver degrades cleanly.

**Two items or one?** Emit **up to two** manifest items — one `WALLPAPER` for home, one for lock —
each with a `which`-surface flag in its payload (§5), so the user can deselect lock independently
and a null/mirrored lock surface simply produces no second item.

## 5. Data model & wire representation

The payload is **raw image bytes**, but the receiver must know *which surface* to set and must
*validate* before handing bytes to `setStream`. Two viable shapes — chosen one documented per the
RALPLAN-DR §9 invalidation note:

**Chosen: a 1-line header + raw bytes, one item per surface.** The `which` flag and a small format
descriptor ride a fixed-size structured prefix; the image bytes are the remainder of the item
stream. This keeps the transport contract ("an item is a byte stream") intact while giving the
receiver the surface + a cheap pre-decode sanity gate.

```
// providers/.../wallpaper/WallpaperProviders.kt
@Serializable
enum class WallpaperSurface { HOME, LOCK }          // → maps to FLAG_SYSTEM / FLAG_LOCK

@Serializable
data class WallpaperHeader(
    val surface: WallpaperSurface,
    val format: String,        // "image/jpeg" | "image/png" | "image/webp" — declared, re-verified
    val width: Int,            // sender-reported, advisory only
    val height: Int,
    val byteLength: Long,      // image-byte count following the header; cross-checked vs ItemMeta.size
)
// wire item = JSON(WallpaperHeader) + "\n" + <byteLength raw image bytes>
```

- **Surface is in the payload, not the kind** — exactly the settings precedent where namespace is
  derived, never trusted from a free field (`SettingsProviders.kt:28-30`). The receiver maps
  `WallpaperSurface` → `WallpaperManager.FLAG_*` from the enum, so a bad value can't redirect.
- **`format`/`width`/`height` are advisory.** Validation does NOT trust them — it re-derives the
  real format from the magic bytes (§7).
- **Size cap:** the receiver already rejects `begin.size > maxItemBytes`
  (`ItemStreamReceiver.kt:118`); the provider adds a *second, tighter* `MAX_WALLPAPER_BYTES`
  (proposed 32 MiB — a 4K lossless PNG is well under that) so wallpaper can't consume the full
  64 MiB Tier-0 budget. Both caps are enforced; the tighter one wins.
- **Validation that it's a real image (the apply gate):** before `setStream`, the provider reads
  the header bytes and runs `BitmapFactory.decodeStream` with `inJustDecodeBounds = true` (bounds
  only — never allocates the pixels). It accepts ONLY if: magic bytes match an allowlisted MIME
  (JPEG/PNG/WebP), `outWidth > 0 && outHeight > 0`, and `outWidth * outHeight ≤ MAX_PIXELS`
  (proposed 64 MP, ~the largest sane wallpaper). Anything else → `ApplyOutcome(SKIPPED, …)` and
  zero bytes reach `setStream` (§7). A failed surface is a per-item skip, never a batch abort
  (`PROTOCOL.md §4-5`; `Providers.kt:52-57`).

## 6. Phased implementation plan (TDD)

Footing per `CLAUDE.md`: branch `feat/wallpaper`, author → independent `code-reviewer` (this
touches no crypto/privilege/wire-protocol, so `security-reviewer` is *recommended* not mandatory —
but the binary-payload validation gate is worth a security glance). Tests-first, green bar each
phase. JVM unit tests run in CI (`:providers:test`); on-device steps are §8 VERIFY_FIRST.

**Phase 0 — model + wire shape (core-model + providers, pure JVM, no Android).**
- *Test first:* `WallpaperProvidersTest` (mirror `SmsProvidersTest.kt`): header round-trips
  through JSON; `WALLPAPER` kind present and `TIER0`; surface enum maps to the right `FLAG_*` int.
- *Green:* add `ItemKind.WALLPAPER("wallpaper", Tier.TIER0)` to `Manifest.kt`; add
  `WallpaperSurface`, `WallpaperHeader`, a `WallpaperCodec` (encode header line; split
  header-from-bytes on read), all in `providers/.../wallpaper/WallpaperProviders.kt`.

**Phase 1 — exporter (provider + fake store).**
- *Test first:* a `FakeWallpaperStore` (hand-written, like `FakeSmsStore`) returns home bytes,
  lock bytes (or null = mirrored). Assert: two items when both present; one item when lock is
  null; `available()==false` and empty export when the store throws (denied/absent), matching the
  graceful-degrade contract (`Providers.kt:26-39`).
- *Green:* `WallpaperExportProvider(store)` reads each surface, prepends the header, streams bytes
  to `exportTo(sink)`. Never throws on a denied read (`runCatching`, per `SmsExportProvider`).

**Phase 2 — importer (apply provider + validation gate).**
- *Test first:* feed valid JPEG/PNG/WebP magic-byte fixtures → `setStream` called with the right
  `FLAG_*`, `OK`. Feed: non-image bytes → `SKIPPED`, store never called; a declared-PNG-but-
  truncated payload → `SKIPPED`; an over-`MAX_PIXELS` bounds → `SKIPPED`; a write that returns
  false → `WRITE_ERROR`. Assert the decode uses bounds-only (the fake records that no full bitmap
  was allocated).
- *Green:* `WallpaperApplyProvider(store)` — parse header, run the `inJustDecodeBounds` + magic +
  pixel-cap gate, then `store.setStream(which, bytes)`; map results to `ApplyOutcome`
  (`Providers.kt:45,52-57`). Mirror `SmsApplyProvider`'s status mapping.

**Phase 3 — Android seam + wiring (the only Android-dependent code).**
- *Green (thin, integration-tested on device per §8):* `AndroidWallpaperStore(context)` over
  `WallpaperManager` — `getWallpaperFile(FLAG_SYSTEM/FLAG_LOCK)` to read, `setStream(...)` to
  write, `SET_WALLPAPER` in the `app-recv` manifest only. Add the provider to
  `app-send/.../MainActivity.kt:95` and `app-recv/.../MainActivity.kt:109`.
- *Test:* extend `ApplyProviderRegistryTest`-style coverage so the registry routes `WALLPAPER`.

**Phase 4 — docs.** Add `wallpaper` to the `ItemMeta.kind` example list in `PROTOCOL.md:122`;
note the binary-payload + image-validation rule in `THREAT_MODEL.md` row 10's spirit (§7).

## 7. Security considerations

Image bytes are **low confidentiality risk** (they already ride the AEAD channel —
`THREAT_MODEL.md §2 row 1`) and carry no credentials. The real risk is a **malicious sender
feeding a hostile "image"** to the receiver (THREAT_MODEL.md row 10, malicious-sender):

1. **Decompression bomb / OOM.** A small payload can decode to a multi-gigapixel bitmap. Mitigated
   by the apply-gate's `inJustDecodeBounds=true` (bounds without allocating pixels) + a
   `MAX_PIXELS` cap (proposed 64 MP) — the receiver NEVER allocates the decoded bitmap; it hands
   raw bytes to `setStream` and lets the platform's own decoder (which enforces its own limits)
   do the work. This is the single most important control in this PRP.
2. **Format confusion / parser exploits.** Only an allowlisted set of magic-byte-verified MIMEs
   (JPEG/PNG/WebP) is accepted; the sender's declared `format` is advisory and re-verified. No
   SVG/exotic decoders.
3. **Oversize DoS.** Two enforced caps: transport `DEFAULT_MAX_ITEM_BYTES` 64 MiB
   (`ItemStreamReceiver.kt:118-119,145-146`, already live → `OVERSIZE`) and the provider's tighter
   `MAX_WALLPAPER_BYTES` (proposed 32 MiB) → `SKIPPED`. Staging files are deleted after apply and
   the dir is swept in a `finally` (`ItemStreamReceiver.kt` class doc) — image partials never
   survive the session.
4. **Path traversal — N/A.** Staging uses generated filenames (`ItemStreamReceiver` /
   `THREAT_MODEL.md` row 10); the wallpaper header carries no filename and none is honored.
5. **Surface redirection — blocked** by deriving `FLAG_*` from the typed `WallpaperSurface` enum,
   never from a raw int (§5).
6. **EXIF/metadata leakage (sender side).** The exporter sends the original wallpaper file as-is,
   which MAY contain EXIF GPS. Accepted residual for v1 (the user is sending to their *own* new
   phone over an authenticated channel); noted in §9 as an optional strip.

No new network surface, no new privilege, no `:adb-bridge` involvement. `security-reviewer` is
recommended specifically for the decode-gate (control #1/#2).

## 8. Test plan & CI gates

**Unit (JVM, `:providers:test` — the new CI line):**
- Header JSON round-trip; surface→`FLAG_*` mapping.
- Exporter: two-item / one-item(mirrored) / denied(empty) / absent cases via `FakeWallpaperStore`.
- Apply-gate matrix: valid JPEG/PNG/WebP → `OK`; non-image → `SKIPPED`; truncated/declared-mismatch
  → `SKIPPED`; over-`MAX_PIXELS` → `SKIPPED`; over-`MAX_WALLPAPER_BYTES` → `SKIPPED`/`OVERSIZE`;
  store write false → `WRITE_ERROR`. Assert **bounds-only** decode (no full-bitmap allocation).
- Registry routes `WALLPAPER` (extend `ApplyProviderRegistryTest.kt`).
- Use Truth + `runTest` + backtick names; avoid the illegal-char test-name pitfall (MEMORY index:
  `Kotlin backtick test-name illegal chars` — no `; . : / [ ] < >` in `fun ` `` `…` `` ()`).

**CI gate:** add `:providers:test` wallpaper cases to the existing providers test run; no new
workflow needed (`.github/workflows/build.yml` already runs provider tests + `assembleDebug` for
both APKs — the new `ItemKind` enum entry and provider wiring are covered by compilation + the
no-escalation assert, which wallpaper does not touch).

**On-device VERIFY_FIRST (record GOS fingerprint per ADR-001 §2.6):**
1. `getWallpaperFile(FLAG_SYSTEM)` returns non-null bytes for a set home wallpaper.
2. `getWallpaperFile(FLAG_LOCK)` behavior: real bytes when a distinct lock wallpaper is set; null
   when lock mirrors home (confirms the "emit one item" branch).
3. Whether reading the lock wallpaper file needs any permission on current GOS (expected none).
4. `setStream(FLAG_SYSTEM)` and `setStream(FLAG_LOCK)` visibly change both surfaces; round-trip
   home→home and lock→lock fidelity (no unexpected re-compression artifacts beyond platform crop).
5. Live-wallpaper home: confirm `getWallpaperFile(FLAG_SYSTEM)` is null and the exporter omits the
   item rather than erroring.

## 9. Open questions / risks

- **RALPLAN-DR option invalidation:** the alternative wire shape — *carry the surface in
  `ItemMeta.group` and ship pure bytes* — was rejected: it overloads a display-only field the
  threat model says must NOT drive logic (`Manifest.kt:43-46`), and gives the receiver no place
  for the declared format. The header-prefix shape keeps surface + format inside a typed,
  validated structure. A second alternative — *one combined item with both images* — was rejected
  because it breaks independent home/lock selection and resume granularity.
- **Lock == home detection** (VERIFY_FIRST #2): if `getWallpaperFile(FLAG_LOCK)` returns home when
  there's no distinct lock wallpaper (vs null), the exporter would double-send. Must distinguish.
- **Crop/parallax fidelity:** v1 ships the full image and accepts the receiver's default crop;
  competitors that preserve crop set a higher bar. Deferred — possible v1.1 if users complain.
- **Re-compression:** `setStream` with original bytes should be lossless; confirm GOS doesn't
  transcode (VERIFY_FIRST #4).
- **EXIF GPS strip (sender):** optional privacy hardening; currently an accepted residual (§7.6).
- **WebP/HEIF coverage:** confirm WebP decodes via `BitmapFactory` bounds on GOS; HEIF wallpaper is
  unlikely but, if seen, either add to the allowlist after a decoder review or skip it.
- **`MAX_WALLPAPER_BYTES` / `MAX_PIXELS` tuning:** proposed 32 MiB / 64 MP are conservative
  starting points — tune against real 4K/lossless wallpapers on-device.
