# PRP-06 — Guided LAN relay for app-owned encrypted backups (Signal/Molly, Aegis)

> Backlog #6 (`docs/prp/feature-research-2026-06.md:20`). The strategically loaded one: portage
> **orchestrates** a device-to-device hand-off of a file the *app* encrypted and the *user*
> exported. portage never backs up, never parses, never decrypts. Read §2 before touching
> `ItemKind` — the scope line here is the riskiest call in the whole backlog, and getting it
> wrong turns portage into the app-data competitor `CLAUDE.md` forbids.

## 1. Summary & user value

For apps that maintain their **own** encrypted backup export — Signal / Molly (message history)
and Aegis (2FA vault) — portage guides the device-to-device hand-off of the resulting encrypted
file over the existing Noise LAN channel. The flow, end to end:

1. **Detect** the installed app on the old phone via the existing inventory seam
   (`InventorySource.installedUserApps()`, `providers/.../inventory/AndroidInventorySource.kt:22`).
2. **Guide** the user to trigger that app's *native* encrypted export — portage cannot do this for
   them; Signal/Molly/Aegis opt out of system backup (`allowBackup=false`, FLAG_SECURE) by design,
   so there is no API to read their data and no intent to silently produce a backup.
3. **Pick:** the user points portage at the encrypted file the app just wrote (SAF file picker).
4. **Ferry** that **opaque** file over the mutually-authenticated Noise channel to the new device,
   reusing the existing item-stream machinery byte-for-byte.
5. **Re-link** on the receiver: present an import prompt — open the file in the target app (launch
   the app / its import intent where one exists) and a reminder that **only the user holds the
   passphrase**. portage never sees it.

**Sourced signal** (`feature-research-2026-06.md:20,36-41`): Signal/Molly and Aegis are flagship
de-Googled apps; the canonical community advice for moving them with no cloud is *"copy the
encrypted file and re-type the passphrase by hand"* — there is no good no-cloud tool today.
portage fills exactly that gap as an **orchestrator, not an absorber** (`feature-research-2026-06.md:40-41`).

User value: the two hardest-to-migrate privacy apps move in the same LAN session as everything
else, with zero cloud and zero passphrase exposure — a hand-carry the user would otherwise do over
USB or a sketchy file-share, made first-class and authenticated.

## 2. Scope & non-goals — THE KEY SECTION

portage relays an **opaque, user-initiated app export**. It is categorically NOT app-data backup,
NOT parsing, NOT `seedvault.blob`. Three distinctions, each made airtight:

**(a) Relay of a user export ≠ reading app data.** Every existing portage provider *reads
system-owned data* through a `ContentResolver`/`Manager` seam — contacts
(`providers/.../contacts/`), SMS (`SmsStore`, `providers/.../sms/SmsProviders.kt:41`), settings
(`providers/.../settings/`). The relay provider reads **nothing app-internal**. It receives a file
*handle the user explicitly picked* — a file the app itself produced and encrypted. portage holds
ciphertext it cannot open. The export is a **user gesture**, exactly like every reinstall is a user
tap and never a silent install (`InventoryProviders.kt:44-54`, "NEVER a silent install").

**(b) This is NOT `seedvault.blob`.** The frozen-v1 prohibition (`Manifest.kt:32-35`,
`PROTOCOL.md:126-128`, `CLAUDE.md` scope-discipline line, DEVILS_ADVOCATE Q5) forbids portage
*couriering a Seedvault file* because that implies portage backing up app *data*. The line that
keeps the relay on the right side of it:

| | Seedvault blob (forbidden) | App-backup relay (this PRP) |
|---|---|---|
| Who produces the artifact | Seedvault (a backup engine) backs up app data | The **app itself**, on a **user-triggered** export |
| What portage would imply | portage owns/produces app-data backups | portage is a **courier** for a file the user made |
| Can portage read it | (Seedvault-encrypted; portage protects anyway) | **No** — app-encrypted with a user-only passphrase |
| Trigger | implies an automated app-data capture path | explicit per-app user export, picked by the user |

The deciding test, stated once: **portage must never be the thing that creates the backup.** If
portage ever gains a code path that *produces* an app's data export (reads its private files,
drives an automated backup), that is `seedvault.blob` by another name and is OUT. Relaying a file
the user handed it is courier work, not backup work.

**(c) Non-goals (hard boundaries):**
- **No decryption, no parsing, no inspection of contents.** The payload is opaque bytes. portage
  reads only the length and computes a SHA-256 for integrity (the transport already does this for
  every item) — never the plaintext.
- **No app-data reading.** portage does not touch Signal's `/data` dir, does not request
  `BACKUP`-style privilege, does not use `:adb-bridge` (`CLAUDE.md`: only `:adb-bridge` speaks the
  ADB wire — irrelevant here, reviewer confirms none sneaks in). Tier 0, no privilege (§3).
- **The passphrase NEVER touches portage.** portage shows a *reminder* to bring the passphrase; it
  neither captures, stores, transmits, nor logs it. The user re-types it into the target app only.
- **portage cannot trigger the export itself.** These apps deny programmatic backup by design;
  portage *guides* (deep-links / launches the app) but the user performs the export.
- **No auto-import of the app's data.** On the receiver, portage hands the file to the user /
  launches the target app's import surface; it does not write into the app's private store.
- **Owner profile only** (ADR-001 §2.4 / `CLAUDE.md`).
- **No new network/discovery surface, no protocol-version bump** (it reuses the v1 item stream —
  see §4/§5 for why a new `ItemKind` is additive, not a v2 break, unlike `seedvault.blob`).

## 3. Feasibility & privilege — **Tier 0** (file transfer + UX)

No privilege at all. This is the existing Tier-0 byte-stream path plus guided UX and a file picker.

**Detect (sender):** reuse `InventorySource.installedPackageNames()` /
`installedUserApps()` (`AndroidInventorySource.kt:22,30`; needs `QUERY_ALL_PACKAGES`, already
install-time-granted on GOS per its KDoc) to check for the known relay-capable packages:

| App | Package(s) | Export the user triggers | Import on the new device |
|---|---|---|---|
| Signal | `org.thoughtcrime.securesms` | Settings → Chats → Chat backups (writes an encrypted `signal-YYYY-…backup` + 30-digit passphrase) | Re-link is install-time restore from local backup folder |
| Molly / Molly-FOSS | `im.molly.app`, `im.molly.foss` | same Signal backup mechanism | same |
| Aegis | `com.beemdevelopment.aegis` | Settings → Import & Export → Export (encrypted vault, password-protected) | Settings → Import |

- **File pick:** Storage Access Framework `ACTION_OPEN_DOCUMENT` (`GetContent`/`OpenDocument`
  ActivityResult contract) — **no storage permission requested** (SAF grants per-URI access). The
  user navigates to the file the app just wrote.
- **Launch the export UI (best-effort guidance):** an explicit intent to the app's launcher
  activity, or a documented deep link where the app publishes one (most do not — see §9). Where no
  intent exists, portage shows step text ("open Signal → Settings → Chats → Chat backups → Create
  backup, then come back and pick the file"). Guidance degrades to instructions; it never blocks.
- **Receiver re-link:** `ACTION_VIEW`/launcher intent to the target package after the file lands in
  a user-visible location (Downloads via SAF or a shared `content://` URI). Same "one user tap"
  shape as `InstallAction` (`InventoryProviders.kt:44-83`), and like that path, package names are
  regex-validated before they reach any intent (`InventoryProviders.kt:62`).

GOS constraints: owner-profile only; `QUERY_ALL_PACKAGES` visibility on GOS already relied upon by
the inventory kind. On-device VERIFY_FIRST items in §8 (which apps actually expose a launch/import
intent; SAF round-trip; cross-app `content://` grant on GOS).

## 4. Architecture fit

The relay reuses the **item-stream / size-cap / hash machinery wholesale** — but it does *not* fit
the silent auto-export `ExportProvider` model, and that difference is the whole design.

- **Why not a plain `ExportProvider`.** `ManifestBuilder` auto-runs every provider's `available()`
  then `exportTo(sink)` with no user interaction (`ManifestBuilder.kt:50-66`). A relay item has no
  data portage can read or auto-stage — it needs a **user file pick first**. So the relay enters
  the manifest through a **separate, UI-driven staging path** that produces a `StagedItem`
  (`ManifestBuilder.kt:18`) the normal way *after* the user picks a file, then merges into the
  manifest item list. The transport sees an ordinary item; only the *staging origin* differs.
- **core-model** (`Manifest.kt:24`): add `ItemKind.APP_BACKUP_RELAY("app.backup.relay", Tier.TIER0)`.
  Enum additions are append-only and require protocol-version review because old builds cannot
  decode unknown enum values. Unlike `seedvault.blob`, this kind is admissible precisely because it
  is courier-not-backup (§2) — but the enum comment must say so explicitly so a future reader does
  not confuse the two.
- **`ItemMeta` carries the association + restore note.** No new transport field is needed: reuse
  the existing display-only `displayName` (e.g. "Signal backup") and `group` (e.g. "App backups")
  (`Manifest.kt:48-50`). The structured **app id + human restore note** ride a small typed header
  *inside the payload* (§5), exactly the wallpaper-surface precedent (`PRP-02-wallpaper.md:107-131`)
  — never in a trusted free field.
- **transport is untouched.** `ItemStreamReceiver` already streams arbitrary opaque bytes with an
  incremental SHA-256 and per-item caps (`ItemStreamReceiver.kt:101-160`), and the manifest/wire
  hash agreement (`begin.size != meta.size → OVERSIZE`, `ItemStreamReceiver.kt:116-119`;
  end-vs-manifest sha) already validates the blob end-to-end **without interpreting it**. That is
  the perfect fit: the machinery's contract is "an item is opaque bytes," which is exactly what a
  relay payload is.
- **The 64 MiB cap is the one real adjustment.** `DEFAULT_MAX_ITEM_BYTES = 64L*1024*1024`
  (`ItemStreamReceiver.kt:219`) is a *per-item* ceiling, enforced at `:118` and re-checked at
  `:145`. **Signal backups routinely exceed 64 MiB.** The `ItemStreamReceiver` constructor already
  takes `maxItemBytes` as a parameter (`ItemStreamReceiver.kt:41`) — the receiver raises it for the
  relay path to a documented, still-bounded ceiling (§5, proposed 2 GiB). This is the load-bearing
  feasibility fact; see §9 risk.
- **recv apply** mirrors `AppInventoryApplyProvider` (`InventoryProviders.kt:108-139`): the relay's
  "apply" does **not** write into any app — it surfaces a re-link action (open the file / launch
  the target app) the user taps, exactly like inventory produces tap-through `InstallAction`s.
  Registry safety is automatic: an old receiver lacking the handler returns `UNKNOWN_KIND`
  (`Providers.kt:64-73`), never crashes.
- **send UI / recv UI:** new guided screens (`app-send/.../relay/…`, `app-recv/.../relay/…`) for
  the detect → guide → pick (send) and receive → re-link prompt (recv) steps. Provider wiring slots
  into the existing `listOf` in `app-send/.../MainActivity.kt:95` and `app-recv/.../MainActivity.kt:109`.

## 5. Data model & wire representation

The payload is **opaque app-encrypted bytes** plus a tiny typed header so the receiver knows which
app to point the user at and what note to show — **never any interpretation of the contents**.

```
// providers/.../relay/AppBackupRelayProviders.kt
@Serializable
enum class RelayApp { SIGNAL, MOLLY, AEGIS, OTHER }   // closed set; "OTHER" = generic relay (Phase 1)

@Serializable
data class RelayHeader(
    val app: RelayApp,                 // typed; receiver derives the target package from THIS enum,
                                       //   never from a free string (cf. WallpaperSurface precedent)
    val targetPackage: String,         // advisory; re-validated against the RelayApp enum + the
                                       //   InventoryProviders package-name regex before any intent
    val originalName: String,          // display-only; NEVER used as a filesystem path
    val restoreNote: String,           // human reminder shown on the receiver (e.g. "Open Signal →
                                       //   Restore from backup; you'll need your 30-digit passphrase")
    val byteLength: Long,              // opaque-blob length following the header; cross-checked vs ItemMeta.size
)
// wire item = JSON(RelayHeader) + "\n" + <byteLength opaque encrypted bytes, never inspected>
```

- **App is in a typed enum, package derived from it** — exactly the settings/wallpaper precedent
  where namespace/surface is derived, never trusted from a free field
  (`SettingsProviders.kt` / `PRP-02-wallpaper.md:120-136`). `targetPackage`/`originalName`/
  `restoreNote` are advisory display strings; the receiver re-validates `targetPackage` against the
  `RelayApp` enum and the `InventoryProviders` regex (`InventoryProviders.kt:62`) before it ever
  reaches an intent — a hostile sender cannot redirect the re-link to an arbitrary package or
  smuggle a scheme into a deep link (the exact HIGH finding that hardened `InstallAction`).
- **The blob is never parsed.** The receiver reads `byteLength` and stages the bytes; it does
  *not* decode, sniff magic bytes, or validate the format — it is the *app's* job to reject a bad
  file when the user imports it. This is the deliberate inversion of PRP-02's image-decode gate:
  there, portage validated because portage owned the surface; here, portage owns nothing and must
  not pretend to understand the ciphertext.
- **Size cap (the key change):** the relay path constructs `ItemStreamReceiver` with a raised
  `maxItemBytes` (proposed `2L*1024*1024*1024` = 2 GiB) so large Signal backups fit, while the cap
  stays finite and the staging-quota / disk checks still bound it. Both the manifest size and the
  wire size are still cross-checked (`ItemStreamReceiver.kt:116-119,145-146`); the tighter relevant
  cap wins. Document the new ceiling next to `DEFAULT_MAX_ITEM_BYTES` (`:219`).
- **Hash agreement, unchanged:** `ITEM_END.sha256` vs the manifest sha verifies the *opaque* blob
  arrived intact (`PROTOCOL.md:130-136`) — integrity without confidentiality loss and without
  interpretation.

## 6. Phased implementation plan (TDD)

Footing per `CLAUDE.md`: branch `feat/app-backup-relay`, author → independent review. This touches
**no crypto and no privilege**, but it sits adjacent to the protocol's scope boundary, so
**`security-reviewer` is mandatory** for the scope-discipline call and the passphrase/residual
handling (§7), in addition to `code-reviewer`. Tests-first, green bar each phase, small mergeable
PRs. JVM unit tests run in CI (`:providers:test`, `:app-recv:testDebugUnitTest`); on-device steps
are §8.

**Phase 1 — generic "relay an encrypted file for app X" (manual file pick). Mergeable alone.**
- *Test first (`AppBackupRelayProvidersTest`, mirror `SmsProvidersTest`):* `RelayHeader` JSON
  round-trips; `APP_BACKUP_RELAY` kind present and `TIER0`; codec splits header-from-opaque-bytes
  on read **without** reading past `byteLength`; a `RelayApp.OTHER` generic item builds.
- *Test first (model/transport):* a relay item flows through `ItemStreamReceiver` with the raised
  `maxItemBytes`: a 65 MiB opaque fixture is accepted (proves the cap fix); a blob over the relay
  ceiling → `OVERSIZE`; sha/size disagreement → the existing per-item failure, batch survives.
- *Green:* add `ItemKind.APP_BACKUP_RELAY` to `Manifest.kt` (with the courier-not-backup comment);
  add `RelayApp`, `RelayHeader`, `RelayCodec` in `providers/.../relay/AppBackupRelayProviders.kt`;
  add the UI-driven staging path that turns a picked `InputStream` + `RelayHeader` into a
  `StagedItem` (`ManifestBuilder.kt:18`); wire the relay `ApplyProvider` that emits a re-link
  action (no app write) analogous to `AppInventoryApplyProvider` (`InventoryProviders.kt:108-139`).
- *Green (Android seam):* SAF file-pick on `app-send`; receiver writes the staged blob to a
  user-visible location and surfaces a re-link prompt on `app-recv`. Provider wiring into both
  `MainActivity` `listOf`s.

**Phase 2 — per-app guided export/import for Signal/Molly + Aegis. Mergeable on top.**
- *Test first:* detection maps each known package → `RelayApp` (table §3) via the inventory seam
  (fake `InventorySource`); unknown package → `OTHER`; the receiver derives `targetPackage` from
  the `RelayApp` enum and rejects a mismatched/invalid advisory package (regex + enum gate).
- *Green:* per-app guidance copy + best-effort launch intents (export side) and re-link/import
  intents (import side) for `org.thoughtcrime.securesms`, `im.molly.app`/`im.molly.foss`,
  `com.beemdevelopment.aegis`; fall back to step-text where the app exposes no intent (§9).

**Phase 3 — docs.** Add `app.backup.relay` to the `ItemMeta.kind` example list (`PROTOCOL.md:122`);
record the courier-not-backup distinction in `THREAT_MODEL.md` (it is a new opaque-secret asset,
§7) and a one-line note in the §2 Seedvault-division section of the top-level PRP.

## 7. Security considerations

The relayed file is **an opaque user secret of the highest sensitivity** (a full message history /
2FA vault, app-encrypted) — handle it like `THREAT_MODEL.md §1`'s residual-secret boundary, the
same class of concern as the non-zeroizable QR-encoded PSK (`THREAT_MODEL.md` §1 residual;
`SenderViewModel` QR-PSK residual note per `CLAUDE.md`).

1. **In transit:** rides the mutually-authenticated Noise ChaCha20-Poly1305 channel like every item
   (`THREAT_MODEL.md §2 row 1`; ADR-002). A same-LAN adversary sees an opaque ciphertext blob
   *inside* an authenticated channel — double-wrapped (app crypto + transport crypto).
2. **At rest (staging):** the blob lands in app-private staging and is deleted after the re-link
   step; the staging dir is swept in a `finally` (`ItemStreamReceiver.kt:96-98`, class doc) — relay
   partials never survive the session, same hygiene as PII items. The one *new* wrinkle: the
   receiver must hand the file to the target app, which means a brief user-visible copy (Downloads
   / a `content://` grant). That copy is **the user's** file in **the user's** chosen location —
   document it, scope the `content://` grant to the target package only, and prefer a
   delete-after-import nudge. Treat any longer-lived copy as an accepted, documented residual (§9).
3. **Passphrase:** portage **never** captures, stores, transmits, or logs the app passphrase. It
   shows a reminder only. The passphrase stays a user-only secret end to end — this is the single
   most important non-negotiable; a reviewer must confirm there is no field, log, or analytics line
   that could ever carry it.
4. **No logging of contents.** The blob bytes are never logged, never previewed, never decoded.
   Logs may carry sizes/hashes/app-id (already exposed by the manifest) — nothing else.
5. **Re-link redirection blocked.** Target package derived from the typed `RelayApp` enum and
   re-validated (regex + enum) before any intent (§5) — a hostile sender cannot point the re-link
   at a malicious package or smuggle a scheme/query (the hardened `InstallAction` precedent,
   `InventoryProviders.kt:56-83`).
6. **Confirm it does NOT become a covert app-data path (the central review item).** The
   `security-reviewer` MUST verify: (a) portage has **no** code path that *produces* an app's
   backup (no private-dir read, no automated export) — only a user-picked file enters; (b) the blob
   is never parsed/decrypted; (c) the kind cannot be repurposed to imply `seedvault.blob` semantics
   (§2 deciding test). If any of these is violable, the feature is mis-scoped and must change before
   merge — track in an ADR if contested.

No new network surface, no new privilege, no `:adb-bridge` involvement. Mandatory
`security-reviewer` pass specifically for the scope boundary (#6) and passphrase residual (#3).

## 8. Test plan & CI gates

**Unit (JVM, `:providers:test` + `:app-recv:testDebugUnitTest` / `:app-send:testDebugUnitTest`):**
- `RelayHeader` JSON round-trip; `RelayCodec` header/blob split stops at `byteLength` (never reads
  the blob into a parser); `APP_BACKUP_RELAY` present and `TIER0`.
- **Cap fix (load-bearing):** a relay item of 65 MiB passes `ItemStreamReceiver` under the raised
  `maxItemBytes`; an item over the relay ceiling → `OVERSIZE`; the default 64 MiB path for *other*
  kinds is unchanged (regression guard so the relay's raised cap does not leak into Tier-0 PII
  items).
- Detection: package→`RelayApp` mapping; unknown→`OTHER`; receiver rejects an advisory
  `targetPackage` that disagrees with the `RelayApp` enum or fails the package regex.
- Apply: emits a re-link action and writes **no** app data; the fake target-app gateway records
  that import was *offered*, not performed; a denied/cancelled re-link is a clean per-item result,
  not a batch abort (`PROTOCOL.md §5`).
- **Negative/scope guards:** assert the apply path never calls any decrypt/parse hook; assert no
  log/field carries blob bytes or a passphrase (a test that greps the emitted log lines / result
  details for the fixture's plaintext marker and fails if found).
- Registry routes `APP_BACKUP_RELAY` (extend `ApplyProviderRegistryTest`).
- Truth + `runTest` + backtick names; avoid the illegal-char test-name pitfall (MEMORY index:
  `Kotlin backtick test-name illegal chars` — no `; . : / [ ] < >` inside `fun ` `` `…` `` ()`).

**CI gate:** add the relay cases to the existing `:providers:test` and app unit-test runs; no new
workflow (`.github/workflows/build.yml` already runs provider + app tests + `assembleDebug` for
both APKs, plus the app-send no-escalation assert — which the relay does not touch, confirming
Tier 0). OSV-Scanner `dependency-audit.yml` covers any new transitive deps (none expected; SAF is
platform).

**On-device VERIFY_FIRST (record the GOS fingerprint per ADR-001 §2.6):**
1. SAF `ACTION_OPEN_DOCUMENT` round-trip on GOS: pick a Signal backup file, stream it without a
   storage permission, sha matches on the far side.
2. Large-file reality: a real >64 MiB (and a >1 GiB) Signal backup streams end-to-end under the
   raised cap without OOM (the stream is chunked, never fully buffered — confirm).
3. Which of Signal / Molly / Aegis actually expose a launch or import intent vs. need step-text
   (§9) — record per app, per version.
4. Cross-app `content://` grant to the target package on GOS (does the re-link open the file in the
   app, or only launch the app?).
5. The passphrase reminder copy is accurate for current Signal (30-digit) and Aegis (user password)
   export formats.

## 9. Open questions / risks

- **RALPLAN-DR option invalidation.** Two alternatives were rejected. (1) *Carry the app id in
  `ItemMeta.group` and ship pure bytes* — rejected: `group` is a display-only field the threat
  model forbids driving logic from (`Manifest.kt:43-50`), and it leaves no validated home for the
  restore note or the typed app enum; the header-prefix shape (§5) keeps app-id + note inside a
  typed, re-validated structure (the same reasoning that settled PRP-02). (2) *Let portage produce
  the export itself via app-private reads or an automated backup intent* — rejected hard: that is
  `seedvault.blob` by another name (§2 deciding test), violates the `CLAUDE.md` scope rule, and is
  impossible anyway since these apps deny programmatic backup. Only a **user-picked file** is in.
- **The 64 MiB cap (highest technical risk).** Signal backups commonly exceed `DEFAULT_MAX_ITEM_BYTES`
  (`ItemStreamReceiver.kt:219`). The fix raises `maxItemBytes` for the relay path *only* (constructor
  param `:41`), with a finite documented ceiling (proposed 2 GiB) and unchanged streaming/staging
  bounds. Risk: a multi-GiB blob stresses staging disk + transfer time — VERIFY_FIRST #2; consider a
  pre-pick size warning. Must NOT raise the cap for Tier-0 PII items (regression test §8).
- **Which apps expose export/import intents** (VERIFY_FIRST #3). Signal's backup is install-time
  restore from a folder, not a runtime import intent; Aegis has an in-app import; Molly mirrors
  Signal. Where no intent exists, guidance degrades to step-text — acceptable, but it means the
  "re-link" UX varies per app and may be instructions-only for Signal.
- **The scope-discipline review is the gating risk, not a technical one.** §2/§7#6 must survive a
  `security-reviewer` pass that explicitly rules the relay is courier-not-backup. If reviewers judge
  the kind blurs the Seedvault line, it changes or does not ship (ADR if contested).
- **Passphrase-handling residual.** portage shows a reminder but never holds the passphrase. The
  residual is purely human: a user who forgets the passphrase cannot restore — portage cannot help,
  by design. Document prominently in the guidance copy. This mirrors the THREAT_MODEL §1
  "secret the user must carry out of band" shape (QR-PSK residual).
- **User-visible staging copy on the receiver** (§7#2): handing the file to the target app needs a
  brief copy in a user location. Scope the `content://` grant to the target package, prefer
  delete-after-import; any longer-lived copy is an accepted documented residual.
- **App-version drift:** export formats and package names can change (Molly-FOSS vs Molly; Signal
  backup format revisions). The `RelayApp` table (§3) is data, not protocol — update without a wire
  change; `OTHER` always covers the generic case.
