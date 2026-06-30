# PRP-04 — Ringtone / notification / alarm sound selection (+ custom sound files)

Backlog item #4 (`docs/prp/feature-research-2026-06.md:18`). Status: SHIPPED. Privilege ceiling: **Tier 0**.

---

## 1. Summary & user value

Move the user's three *active default sound selections* — ringtone, notification sound, alarm —
from the old phone to the new one, **including the backing audio file** when the user picked a
custom sound rather than a built-in one. Portage explicitly avoids a bare settings-key copy: the
catalog still tags `ringtone` as `DEVICE_SPECIFIC, NA` with the note
"TRAP: content URI to on-device media absent on the new phone → silent/crash"
(`settings-catalog/src/main/kotlin/com/ventouxlabs/portage/settings/SettingsAllowlist.kt:59-60`). That
exclusion is correct for a *bare-URI* copy — this PRP is the salvage that makes the selection
portable by carrying the URI's *meaning* (a built-in identity, or the file itself), not the raw URI.

**Signal:** Samsung Smart Switch moves Clock/sound preferences; the feature-research scan ranks this
#4 by value × feasibility and notes Seedvault coverage is only *Partial*
(`docs/prp/feature-research-2026-06.md:18`). It is squarely on portage's owned turf (settings +
device parity), not Seedvault's (app data).

**User story:**
> As someone migrating to a new GrapheneOS phone,
> I want my chosen ringtone, notification sound, and alarm to be set the same way on the new device,
> so that the phone "sounds like mine" without me re-picking each one in Settings.

---

## 2. Scope & non-goals

**In scope (exactly three roles):**
- The default **ringtone** (`RingtoneManager.TYPE_RINGTONE` / `Settings.System.RINGTONE`).
- The default **notification sound** (`TYPE_NOTIFICATION` / `Settings.System.NOTIFICATION_SOUND`).
- The default **alarm** (`TYPE_ALARM` / `Settings.System.ALARM_ALERT`).
- For each role: whether the source is a **built-in/system** sound or a **user-supplied file**, and
  in the user-supplied case, the audio file bytes so the selection survives on a device that has
  never seen that file.

**Non-goals (hard boundaries):**
- NOT a music/media library transfer. Only the (≤3) files *currently bound to these three roles*
  travel — never the contents of `/Music`, `/Ringtones`, or the media store at large.
- NOT per-app / per-channel notification sounds — that's backlog #5 (privileged) and out of frame.
- NOT a `seedvault.blob`-style app-data move; this stays inside the settings/parity remit
  (`CLAUDE.md` "Scope discipline"; `core-model/.../Manifest.kt:32-35`).
- NOT "Do Not Disturb" sound rules, vibration patterns, or volume levels (`volume_alarm` is a
  separate existing key, `SettingsAllowlist.kt:55`).
- The legacy `ringtone` catalog key stays `DEVICE_SPECIFIC, NA` — this feature does NOT route
  through `SettingsApplyProvider`; it gets its own item kind(s) (see §4).

---

## 3. Feasibility & privilege

| Aspect | Finding |
|---|---|
| Read selection | `Settings.System.getString(cr, RINGTONE\|NOTIFICATION_SOUND\|ALARM_ALERT)` returns a content URI string. No permission to read (`AndroidSystemSettingsStore.read`, `providers/.../AndroidSystemSettingsStore.kt:23-24`). |
| Write selection | `RingtoneManager.setActualDefaultRingtoneUri(context, type, uri)` — under the hood this writes the same `Settings.System` keys, so it needs the **"Modify system settings" special access** (`Settings.System.canWrite`), already modeled by `SystemSettingsStore.canWrite()` (`providers/.../SettingsProviders.kt:57-61`). **Tier 0 — no `WRITE_SECURE_SETTINGS`, no ADB bridge.** |
| Resolve identity | `RingtoneManager.getRingtone(context, uri)?.getTitle(context)` yields a human title for a built-in; the canonical built-in URIs come from `RingtoneManager.getValidRingtoneUri` / the `MediaStore` system-ringtone cursor. |
| Custom-file write-back | `MediaStore.Audio.Media.getContentUriForPath` + an insert with `IS_RINGTONE/IS_NOTIFICATION/IS_ALARM=1` re-registers a copied file and returns a *new* content URI. **The stored Settings value must be remapped to that new URI** before writing — the old device's URI is meaningless on the new one. |
| Storage of the copied file | On API 31+ (minSdk 31, `CLAUDE.md` Build), write into the public `Ringtones/Notifications/Alarms` relative paths via `MediaStore` `RELATIVE_PATH` + `IS_PENDING`; **no broad storage permission** (scoped storage owns its own inserts). This keeps the feature Tier 0. |

**The hard part — the URI-remap problem.** A `Settings.System.RINGTONE` value is a content URI like
`content://media/internal/audio/media/47` (built-in) or `content://media/external/audio/media/123`
(user file). That integer id is device-local; copying the *string* to the new phone points at the
wrong sound or nothing. So restore must classify and remap:
- **Built-in/system sound** → look up the *equivalent* system sound on the target by stable identity
  (title + role), get *its* local URI, write that. If no equivalent exists on the target, **fall
  back gracefully** (see below) — never write a dangling URI.
- **User-supplied file** → copy the bytes, re-register via `MediaStore` to obtain the new URI, then
  write *that* URI. Only after a successful insert.

**System-sound-not-present-on-target fallback.** Built-in sets differ across builds/OEMs (and GOS
vs stock). If the carried built-in identity has no match on the target, the role is **left at the
device default** and reported as a per-item `SKIPPED`/partial detail — consistent with the
"a failed key is a per-key skip, never a transport error" discipline
(`providers/.../SettingsProviders.kt:148-150` comment; `PROTOCOL.md:144-146`).

**GOS constraints.** Owner profile only (`CLAUDE.md`). `POST_NOTIFICATIONS` denied-by-default on GOS
is irrelevant here (we don't notify). Scoped storage on GOS behaves as AOSP — `MediaStore` inserts
into `Ringtones/` are the supported, permission-light path. `RingtoneManager` is a stable framework
API, no GOS divergence expected; flagged for VERIFY_FIRST (§9).

---

## 4. Architecture fit

Mirror the **two-provider split** already used for settings (`ExportProvider` + `ApplyProvider`,
`providers/.../Providers.kt:26-57`) and the **staging-to-file** pattern for the audio bytes
(`app-recv/.../transfer/ItemStreamReceiver.kt`). Two item kinds, because the selection is tiny text
and the (optional) file is a binary blob with a different size profile.

**core-model — new wire kinds** (`core-model/.../Manifest.kt:24-36`, the FROZEN `ItemKind` enum).
Adding to this enum is a deliberate protocol act (same gate as the `SEEDVAULT_BLOB` note). Add:
```kotlin
SOUND_SELECTION("sound.selection", Tier.TIER0),  // the ≤3 role → identity mappings (text)
SOUND_FILE("sound.file", Tier.TIER0),            // one user-supplied audio file (binary)
```
Both Tier 0 — neither needs the privilege bridge. `SOUND_FILE` is the **first binary-file item kind
that actually streams bytes** (APK is declared but unimplemented), so it sets the precedent for the
staging path being reused beyond text.

**providers — reader + writer** (new package `providers/.../sound/`):
- `SoundSelectionExportProvider : ExportProvider` (kind `SOUND_SELECTION`) — reads the three
  `Settings.System` keys via `SystemSettingsStore`, classifies each as built-in vs file, resolves a
  portable identity, and emits a JSON snapshot. Mirror `SettingsExportProvider`
  (`providers/.../SettingsProviders.kt:111-142`): `available()` false when nothing is set,
  `exportTo` writes JSON via `JsonLines.format` (`providers/.../wire/JsonLines.kt`).
- `SoundFileExportProvider : ExportProvider` (kind `SOUND_FILE`) — for each role whose source is a
  user file, streams a role header plus the raw audio bytes (one item per role).
- `SoundSelectionApplyProvider : ApplyProvider` (kind `SOUND_SELECTION`) — decodes the snapshot,
  resolves/remaps each role's URI on the target, gates on `SystemSettingsStore.canWrite()`, applies
  via `RingtoneManager.setActualDefaultRingtoneUri`. Mirror `SettingsApplyProvider.apply`
  best-effort loop (`providers/.../SettingsProviders.kt:166-219`): per-role success/skip counting,
  `ApplyOutcome(OK, "applied N, skipped M …")`.
- `SoundFileApplyProvider : ApplyProvider` (kind `SOUND_FILE`) — validates the staged file is a real
  audio file, inserts it into `MediaStore` `Ringtones/`, records the resulting new URI in a small
  in-process **remap table** keyed by the file's content hash so the selection apply can look it up.
- New seam `RingtoneStore` (interface) wrapping `RingtoneManager` + `MediaStore`, with an
  `AndroidRingtoneStore` impl — exactly the `SystemSettingsStore` / `AndroidSystemSettingsStore`
  split (`providers/.../AndroidSystemSettingsStore.kt`) so providers stay unit-testable with fakes.
- Register in app-recv's `ApplyProviderRegistry` list and app-send's `ExportProvider` list
  (`app-recv/.../ReceiverViewModel.kt:57-58`, `app-send/.../SenderViewModel.kt:50` →
  `ManifestBuilder(providers, …)` `app-send/.../transfer/ManifestBuilder.kt:39-40`).

**Apply ordering.** `SOUND_FILE` items must apply **before** `SOUND_SELECTION` so the remap table is
populated when the selection resolves a file-backed role. The registry dispatches per kind; the
sender orders items file-first in the manifest, and the selection apply tolerates a missing remap
entry (role → `SKIPPED`, "backing file not delivered").

**send/recv UI.** Surface as two checklist rows under a "Sounds" group (mirror the `group` field on
`ItemMeta`, `core-model/.../Manifest.kt:50`). Selecting "Sounds" implies its file(s); show file
sizes in the item list (the consent-granularity rule, `THREAT_MODEL.md:48`,
`SenderViewModel` item list). No new screens — reuse `ChecklistScreen`.

**transport — size cap.** No transport change. The audio blob rides the existing per-item stream with
`ItemStreamReceiver`'s `DEFAULT_MAX_ITEM_BYTES = 64 MiB` cap
(`app-recv/.../transfer/ItemStreamReceiver.kt:219`), plus the manifest size/hash agreement gates
(`ItemStreamReceiver.kt:116-119, 145-146, 204`). A custom ringtone larger than 64 MiB is already
implausible; oversize → `ItemStatus.OVERSIZE`, role left at default. Optionally introduce a tighter
provider-advertised soft cap (e.g. 16 MiB) surfaced in the UI, but the hard ceiling is the existing
receiver cap — do not raise it.

---

## 5. Data model & wire representation

`SOUND_SELECTION` payload (JSON via `JsonLines.format`, same codec style as
`SettingsCodec`, `providers/.../SettingsProviders.kt:40-50`):

```kotlin
@Serializable enum class SoundRole { RINGTONE, NOTIFICATION, ALARM }

@Serializable enum class SoundSource { BUILTIN, USER_FILE, UNSET }

@Serializable data class SoundChoice(
    val role: SoundRole,
    val source: SoundSource,
    val builtinTitle: String? = null,  // BUILTIN: stable identity to match on target
    val fileSha256: String? = null,    // reserved; current implementation remaps USER_FILE by role
    val fileDisplayName: String? = null,
)
@Serializable data class SoundSelection(val choices: List<SoundChoice>)
```

`SOUND_FILE` payload = **one-line JSON `SoundFileHeader`** (`role`, display name, MIME, byte
length) followed by opaque audio bytes. The receiver stages and hash-verifies the item like every
other item, sniffs the payload as audio, inserts it into MediaStore, and stores the resulting local
URI in a transfer-scoped remap table keyed by `SoundRole`. The later `SOUND_SELECTION` item uses
that role remap for `USER_FILE` choices. This may ship the same physical file twice if two roles
point at it; the tradeoff keeps the receiver join simple and avoids trusting sender URIs.

**Validation (receiver-side, before any write):**
- `source == BUILTIN` → `builtinTitle` non-blank, matched against the target's enumerated built-ins;
  unmatched ⇒ role skipped.
- `source == USER_FILE` → a prior `SOUND_FILE` for that role was staged AND the staged bytes
  **sniff as audio**. Validate by content, not extension or display name: check a magic
  header (RIFF/WAVE, `ID3`/MPEG frame sync, `ftyp` for MP4/M4A, `OggS`, `fLaC`) and/or
  `MediaMetadataRetriever.extractMetadata(METADATA_KEY_HAS_AUDIO)`. Reject anything that doesn't
  decode as audio — never feed an unvalidated blob to `MediaStore`.
- Size: bounded by the receiver's per-item cap (§4) and by manifest size agreement.

---

## 6. Phased implementation plan (TDD)

Small, independently mergeable phases; tests-first per `CLAUDE.md` cadence (author → independent
review → merge). Each phase is its own feature branch + PR.

**Phase 0 — model + catalog (`feat/sound-model`).**
- Add `SOUND_SELECTION`, `SOUND_FILE` to `ItemKind` (`core-model`); add the `SoundRole/SoundSource/
  SoundChoice/SoundSelection` types. Update the catalog note on `ringtone` to point at this PRP
  (still `NA` — selections no longer travel as a raw key).
- Tests: `core-model` serialization round-trip; `settings-catalog` invariant test stays green
  (`SettingsAllowlistTest`).

**Phase 1 — built-in selections only, NO files (`feat/sound-builtin`).**
- `RingtoneStore` seam + `AndroidRingtoneStore`; `SoundSelectionExportProvider` (BUILTIN/UNSET only —
  a user-file role exports as `UNSET` in this phase so nothing dangles);
  `SoundSelectionApplyProvider` with built-in title matching + `setActualDefaultRingtoneUri`.
- Wire into the send/recv provider lists + checklist "Sounds" row.
- Tests (providers, fakes-over-mocks per kotlin/testing rules, mirror `SettingsProvidersTest`'s
  `FakeSystemSettingsStore` `providers/.../SettingsProvidersTest.kt:20-33`): export reads three keys;
  apply matches a built-in title; **unmatched built-in → role skipped, OK outcome**; `canWrite()`
  false → all roles skipped with the grant-needed hint.
- Ships value on its own: built-in selections are the common case.

**Phase 2 — custom file copy + URI remap (`feat/sound-files`).**
- `SoundFileExportProvider` (role-header + stream bytes); `SoundFileApplyProvider` (validate audio,
  `MediaStore` insert, populate role remap table); extend `SoundSelectionExportProvider` to emit
  `USER_FILE`; extend the selection apply to resolve file-backed roles via the remap table; enforce
  file-before-selection ordering in provider registration.
- Tests: audio-sniff validator accepts WAV/MP3/OGG/FLAC/M4A headers and **rejects** a renamed
  text/EXE blob; remap table join (role → new URI) drives the selection write; missing
  `SOUND_FILE` → file-backed role `SKIPPED`; oversize file → `OVERSIZE` (lean on
  `ItemStreamReceiverTest` `app-recv/.../ItemStreamReceiverTest.kt:137`).
- A `LoopbackTransferSmokeTest`-style end-to-end (`app-recv/.../LoopbackTransferSmokeTest.kt`) sends a
  small WAV + selection and asserts the role resolves to the new URI.

---

## 7. Security considerations

- **Untrusted audio bytes.** A hostile sender (`THREAT_MODEL.md:28-29`, malicious-sender row) can ship
  arbitrary bytes labeled `sound.file`. Mitigations, all receiver-side: staged under a **generated
  filename** (`ItemStreamReceiver.kt:123-124`, kills path traversal); hash/size-gated before apply;
  **content-sniffed as audio** before any `MediaStore` insert (§5); per-item 64 MiB cap. We never
  execute the file — it's media, decoded by the framework, not run.
- **URI-remap cannot be steered by the payload.** The receiver NEVER writes a sender-supplied URI
  string. For BUILTIN it writes a URI it *resolved locally* from the target's enumerated built-ins;
  for USER_FILE it writes the URI returned by *its own* `MediaStore` insert. The carried
  `builtinTitle`/`fileSha256` are *lookup keys*, not destinations — a crafted URI in the payload has
  nowhere to land (mirrors the settings rule: "name+value only — the namespace is NEVER carried; the
  receiver derives it," `providers/.../SettingsProviders.kt:28-34`).
- **Receiver sovereignty.** Same property as settings: nothing in the payload makes the receiver
  write outside its own resolution (`THREAT_MODEL.md:57-58`). Unknown role / unmatched built-in /
  unvalidated file ⇒ skip, never a partial dangling write.
- **No new privilege surface.** Tier 0 throughout: `Settings.System.canWrite` + scoped-storage
  `MediaStore`. No `WRITE_SECURE_SETTINGS`, no ADB bridge, no `shell()` — so the CI no-escalation grep
  (`.github/workflows/build.yml:78`) and the app-send no-link-privilege rule are untouched.
- **Staging hygiene.** Audio blobs are personal-ish; the existing delete-after-apply + finally sweep
  (`ItemStreamReceiver.kt:96-98, 184`) covers them. The `MediaStore` insert is the only persistent
  artifact and it *is* the user's intended ringtone.
- **`security-reviewer` is mandatory** here only if anything touches the wire protocol — adding
  `ItemKind` values does (`CLAUDE.md` cadence). Route the Phase-0 PR through `security-reviewer`.

---

## 8. Test plan & CI gates

Existing CI gates that MUST stay green (`.github/workflows/build.yml:28, 44, 46`):
`:settings-catalog:test`, `:core-model:test`, `:providers:testDebugUnitTest`,
`:app-recv:testDebugUnitTest`, `:app-send:testDebugUnitTest`, `assembleDebug` (both APKs), and the
`.shell(`-outside-bridge grep (`build.yml:78`).

| Layer | Test | Asserts |
|---|---|---|
| Unit (core-model) | selection round-trip | JSON encode/decode stable; enum order frozen |
| Unit (providers) | export reads 3 roles | each `Settings.System` key surfaced; UNSET when absent |
| Unit (providers) | built-in apply | title match → `setActualDefaultRingtoneUri` called with the *resolved* URI |
| Unit (providers) | unmatched built-in | role skipped, batch OK, detail explains fallback |
| Unit (providers) | `canWrite()` false | all roles skipped + "modify system settings" hint |
| Unit (providers) | audio sniff | WAV/MP3/OGG/FLAC/M4A accepted; renamed non-audio rejected |
| Unit (providers) | remap join | file hash → new URI drives the file-backed selection write |
| Unit (providers) | missing file | file-backed role with no staged `SOUND_FILE` → SKIPPED |
| Integration | oversize file | > per-item cap → `OVERSIZE`, role default (reuse `ItemStreamReceiverTest`) |
| E2E (loopback) | WAV + selection | smoke transfer resolves the role to the new device's URI |
| Manual / VERIFY_FIRST | on Pixel/GOS | real `RingtoneManager` write persists; `MediaStore` insert visible in Settings sound picker; camera/file handles released |

Coverage target: providers + new model paths ≥ 80% (`rules/common/testing.md`).

---

## 9. Open questions / risks

| # | Risk / unknown | Why it matters | Mitigation / disposition |
|---|---|---|---|
| 1 | **Cross-device built-in matching is fuzzy** — title strings can differ by locale/build; GOS built-ins may not equal the source's. | A mismatch silently drops the user's chosen built-in. | Match on title + role; on miss, leave default and report. Consider a normalized-title map. VERIFY_FIRST on two GOS builds. |
| 2 | **`MediaStore` insert reliability on GOS** — `IS_PENDING` flow, duplicate inserts on re-run, which collection (`internal` vs `external`) the picker reads. | A copied file that the sound picker can't see ≈ feature failure. | Insert into `external` `Ringtones/`, finalize `IS_PENDING=0`; dedup by display name + hash. Hardware VERIFY_FIRST. |
| 3 | **`setActualDefaultRingtoneUri` timing** — it writes `Settings.System` and may need the file's `MediaStore` row to be fully visible first. | Race: selection applied before the file row is queryable → dangling. | File-before-selection ordering + remap table populated only post-insert (§4). |
| 4 | **`READ_SMS`-style permission surprises** — does enumerating built-ins or reading the current URI need `READ_MEDIA_AUDIO` on the *sender*? | Could pull the feature off Tier 0 if a read permission is required. | Reading `Settings.System` needs none; only built-in *enumeration* might. Probe in VERIFY_FIRST; if needed, request narrowly on send and degrade gracefully. |
| 5 | **Soft size cap UX** — should there be a sub-64 MiB advertised cap so a huge "ringtone" is refused with a clear message rather than a generic OVERSIZE? | Cleaner consent than a transport-level reject. | Optional provider-advertised cap surfaced in the item list; hard ceiling stays the receiver's 64 MiB. |
| 6 | **`internal` (system) URIs for built-ins** are read-only and stable-ish but not guaranteed equal across builds. | Same as #1 from the URI angle. | Always re-resolve locally; never write the carried URI verbatim (§7). |

---

### Mandatory reading before implementing

| Priority | File:lines | Why |
|---|---|---|
| P0 | `providers/.../settings/SettingsProviders.kt:28-219` | The export/apply two-provider pattern + codec + best-effort apply loop to mirror |
| P0 | `core-model/.../Manifest.kt:24-51` | The FROZEN `ItemKind` enum + `ItemMeta` you extend |
| P0 | `app-recv/.../transfer/ItemStreamReceiver.kt:39-224` | Staging-to-file, 64 MiB cap, hash/size gates for the audio blob |
| P1 | `providers/.../settings/AndroidSystemSettingsStore.kt` | The thin Android-seam pattern for `RingtoneStore`/`AndroidRingtoneStore` |
| P1 | `providers/.../settings/SettingsProvidersTest.kt:20-67` | Fakes-over-mocks test pattern to copy |
| P1 | `settings-catalog/.../SettingsAllowlist.kt:59-60` | The existing `ringtone` exclusion this PRP supersedes |
| P2 | `app-recv/.../ReceiverViewModel.kt:57-76`, `app-send/.../SenderViewModel.kt:50-74` | Where to register the new providers |
| P2 | `docs/prp/PROTOCOL.md:106-155`, `docs/prp/THREAT_MODEL.md:28-29,57-58` | Wire sequence + malicious-sender / receiver-sovereignty properties |
