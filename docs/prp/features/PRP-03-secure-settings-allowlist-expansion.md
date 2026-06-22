# PRP-03 — Secure-settings allowlist expansion to Seedvault's named gaps

> Backlog #3 from `docs/prp/feature-research-2026-06.md` (line 17). Tier 1 (allowlisted
> `Settings.Secure`/`Global` via the one-shot `WRITE_SECURE_SETTINGS` grant). This is the
> **most native** extension portage has: the data path, wire item kind, validators, and
> guardrail invariant already exist — the work is almost entirely *new allowlist rows + a
> validator each*, plus an honest triage of which named candidates are actually plain
> Settings keys versus GOS-private package state that falls out of scope.

---

## 1. Summary & user value

CalyxOS's Seedvault documentation publishes an explicit list of settings it does **not**
back up; `docs/prp/feature-research-2026-06.md` (line 48) records that authoritative
exclusion list (Wi-Fi, BT timeout, Panic settings, USB/peripheral, priority conversations,
emergency owner info, notifications "planned"). portage's brand is *the reliable
complementary LAN parity layer for the categories it owns* (research doc §"Strategic
note", lines 36–41). Closing named Seedvault gaps that a privacy/GrapheneOS audience
actually re-configures by hand — DND/Zen behavior, Bluetooth scan-availability timeout,
emergency owner info on the lock screen, priority conversations — is directly on-brand and
high-signal: these are the "did my preferences move?" papercuts that make a restore feel
incomplete.

**Sourced signal.** CalyxOS Seedvault exclusion docs (research doc line 48); GrapheneOS
migration pain points (research doc Sources, lines 43–55). **User value:** fewer manual
re-toggles after a device move, specifically in the categories the privacy audience cares
about most, with zero new privilege and zero new wire surface.

This PRP **expands the compiled catalog** in
`settings-catalog/src/main/kotlin/com/ventouxlabs/portage/settings/SettingsAllowlist.kt`. It adds
rows; it does not change the data path, the model, or the protocol.

---

## 2. Scope & non-goals

**In scope** — new `SettingKey` rows (SAFE or DEVICE_SPECIFIC-as-documented-trap) + a
validator per writable key, riding the existing `ItemKind.SETTINGS` path:

- **DND / Zen global state** — `Settings.Global.ZEN_MODE` (the current zen mode int). The
  design doc already lists this and classifies the broader DND *config* as **RISKY**
  (`docs/prp/settings_allowlist.md` line 41). In scope as a **RISKY** opt-in for the plain
  `ZEN_MODE` int; the structured zen *rules* blob is out of scope (see below).
- **Bluetooth scan-availability timeout** — candidate `Settings.Global` /
  `Settings.Secure` integer (key name is a **spike**, §9). If it resolves to a real
  Settings key, **SAFE or RISKY** with an `IntEnum`/`IntRange` validator.
- **Priority conversations** — the people-space "priority" surface; key name is a
  **spike** (§9). Likely lives in `NotificationManager` channel/conversation state, **not**
  a Settings key — if so it is **out of scope here** and belongs to backlog #5 (notification
  channel parity).
- **Emergency owner info / owner name** — `Settings.Global.DEVICE_NAME` (owner-set device
  name, a real Global string) is in scope, **SAFE** with a length/charset
  `StringPattern`. The lock-screen **emergency owner info** text is a **spike** (§9):
  historically `Settings.Secure` `lock_screen_owner_info` + `lock_screen_owner_info_enabled`,
  but these may be deprecated/internal on current AOSP/GOS — flag, do not promise.
- **Starred-contact status** — explicitly **NOT a Settings key**. Contact "starred" is a
  column in the Contacts provider, already portage's `CONTACTS_VCF` territory, not
  settings-catalog. **Out of scope for this PRP** (noted so it is not silently dropped).

**Punted / out of scope (with rationale):**

- **GrapheneOS Panic settings (panic-wipe config)** — `docs/prp/settings_allowlist.md`
  line 104 *already* classifies "panic settings" as **DEVICE_SPECIFIC — exclude**: GOS
  security/hardware state with no stable plain-Settings key. These live in GOS-private
  package state, not `Settings.Secure/Global`. **Out of scope.** If a stable key is ever
  found it would be DEVICE_SPECIFIC (never transferred) regardless. Honesty: do **not**
  promise panic config.
- **USB-peripheral hardening toggle ("USB-C port / accessories when locked")** — same line
  104 already classifies "USB-when-locked" as **DEVICE_SPECIFIC — exclude**. It is a GOS
  security-state toggle, not a portable preference; copying a hardening setting onto a
  fresh device is exactly the "looks SAFE, isn't" hazard. **Out of scope** (or, if a key is
  located, DEVICE_SPECIFIC and never written).
- The structured **zen *rules* / `AutomaticZenRule`** blob — referenced via
  `NotificationManager`, not a plain Settings value; couriering it risks colliding with
  notification-channel parity (backlog #5) and silencing the new phone. Out of scope.
- Anything implying **app-data transfer** — no `seedvault.blob`; the
  `core-model/.../Manifest.kt` `ItemKind` enum deliberately omits it. This PRP adds **zero**
  new item kinds (§5).

**Clear of app-data.** Every in-scope item is a `Settings.{Secure,Global}` key/value pair —
a user *preference*, never app content. Seedvault's app-data division of labor holds
(`CLAUDE.md` "Scope discipline").

---

## 3. Feasibility & privilege (per candidate)

All writable candidates ride **Tier 1**: `Settings.Secure`/`Global` after the one-shot
`WRITE_SECURE_SETTINGS` grant the privilege bridge installs once
(`AndroidSecureGlobalSettingsStore`, ADR-001 §1). Reading needs no privilege; the receiver
self-skips Tier-1 keys cleanly until the grant lands (`SettingsApplyProvider.tier1Writable`).

| Candidate | Real Settings key (or status) | Namespace | Class | Validator approach | Spike? |
|---|---|---|---|---|---|
| DND / Zen mode | `zen_mode` (`Settings.Global.ZEN_MODE`) | GLOBAL | **RISKY** (line 41) | `IntEnum(setOf(0,1,2,3))` — off / important / none / alarms | no — key is stable AOSP |
| Zen *config* blob / rules | `AutomaticZenRule` via `NotificationManager` — **not a Settings key** | — | out of scope | — | confirm-and-drop |
| Bluetooth scan/availability timeout | candidate `bluetooth_off_timeout` / GOS-specific — **unverified** | GLOBAL? | SAFE-or-RISKY *if* it resolves | `IntRange` (ms) or `IntEnum` | **YES** |
| Priority conversations | `NotificationManager` conversation state — likely **not** a Settings key | — | out of scope if not a key | — | **YES** |
| Emergency owner info (text) | `lock_screen_owner_info` + `_enabled` (`Settings.Secure`, possibly deprecated/internal on GOS) | SECURE | SAFE *if* writable + present | `_enabled`: `IntEnum(0,1)`; text: `StringPattern` bounded charset/length | **YES** |
| Device / owner name | `device_name` (`Settings.Global.DEVICE_NAME`) | GLOBAL | **SAFE** | `StringPattern("""[\p{L}\p{N} _.\-]{1,48}""")` | no |
| Panic-wipe config | GOS-private package state — **no plain Settings key** | — | **DEVICE_SPECIFIC** / out of scope | n/a (`Validator.None`, `reach=NA`) | drop |
| USB-when-locked hardening | GOS security state — **no portable Settings key** | — | **DEVICE_SPECIFIC** / out of scope | n/a | drop |
| Starred contacts | Contacts provider column — **not settings** | — | out of scope (CONTACTS_VCF) | n/a | drop |

**Honesty note (load-bearing).** The strongest "privacy-audience" candidates the prompt
names — Panic config and USB hardening — are **precisely the two `settings_allowlist.md`
line 104 already excludes as DEVICE_SPECIFIC GOS security state**. They are not plain
Settings keys and must not be promised. The PRP's *deliverable* candidates are the smaller,
genuinely-portable set: `zen_mode`, `device_name`, and (pending spike) BT timeout +
emergency owner info text.

Validators reuse the existing sealed `Validator` set (`SettingKey.kt` lines 41–50):
`IntRange`, `FloatRange`, `IntEnum`, `StringEnum`, `StringPattern`, evaluated fail-closed by
`Validator.accepts` (`Validation.kt`). No new validator *type* is needed; if a new shape is
unavoidable (e.g. a bounded multi-token list) it is a small, separately-reviewed addition to
the sealed interface with its own `accepts` branch and `ValidationTest` cases.

---

## 4. Architecture fit

Almost entirely **catalog data**. The end-to-end path already exists and is key-agnostic:

- **Catalog row** — add `SettingKey(name, namespace, classification, reach, reason,
  validator)` to `SettingsAllowlist.all` (`SettingsAllowlist.kt` lines 33–93). Follow the
  existing seed exactly: e.g. `ui_night_mode` (SECURE/SAFE/T1_GRANT with
  `IntEnum(setOf(0,1,2))`, line 45) is the template for a SAFE Tier-1 enum key;
  `window_animation_scale` (GLOBAL/SAFE/T1_GRANT, line 80) for a Global one;
  `screen_brightness` (DEVICE_SPECIFIC/NA, line 41) for a documented trap.
- **Export** — `SettingsExportProvider` already reads every SAFE key with a value across
  namespaces via `exportableSafeKeys()` (`SettingsProviders.kt` lines 98–142). A new SAFE
  key is exported automatically; **no code change**.
- **Apply** — `SettingsApplyProvider.apply` already gates on `byName` ∈ allowlist, SAFE,
  reach ∈ {T0_SYSTEM, T1_GRANT}, and `validator.accepts` (lines 170–176), then routes by the
  matched key's own namespace/reach (lines 195–201). A new key is applied automatically;
  **no code change**. RISKY keys (e.g. `zen_mode`) are read/written by the data path **only**
  if/when an opt-in surface promotes them — the default cut is SAFE-only, so adding a RISKY
  row is inert in the default flow until an opt-in path exists.
- **Privilege** — unchanged. `:adb-bridge` remains the only privileged entry; no new
  `shell()` call sites (CI greps for those, `build.yml`).

**New code is minimal:** new `SettingKey` rows; new `ValidationTest`/`SettingsAllowlistTest`
cases; possibly one new `Validator` subtype + its `accepts` branch *only if* a candidate
needs a shape the five existing validators cannot express. No changes to `:providers`,
`:core-model`, the wire protocol, or the privilege stack.

---

## 5. Data model

**Confirmed: these ride the EXISTING settings item kind — no new kind is invented.**

- The wire enum `ItemKind.SETTINGS("settings", Tier.TIER1)` is frozen in
  `core-model/.../Manifest.kt` and already carries settings parity. New allowlist rows are
  serialized on the wire as the existing `SettingEntry(name, value)` /
  `SettingsSnapshot(entries)` pair (`SettingsProviders.kt` lines 33–37). **Name + value
  only** — the namespace is never on the wire; the receiver derives it from the compiled
  `byName` match (`SettingsProviders.kt` lines 28–32, and `SettingsAllowlist.byName` lines
  99–105). Adding keys cannot change the wire shape.
- No manifest change, no protocol-version bump, no new `Tier`. A new key is just one more
  entry inside an already-defined `ItemKind.SETTINGS` payload.

---

## 6. Phased implementation plan (TDD, one cluster per phase, small & mergeable)

Each phase: write the failing `ValidationTest` + `SettingsAllowlistTest` assertions first
(RED), add the catalog row(s) (GREEN), confirm the guardrail invariant stays green, update
`docs/prp/settings_allowlist.md` to match (it is the human source of truth, line 3–5).
Branch-per-feature → independent `code-reviewer` + **mandatory `security-reviewer`** (the
catalog is a privilege boundary) → merge (`CLAUDE.md` cadence).

**Phase 0 — Spikes (no catalog change; resolves §9).** Dump `Settings.Global` /
`Settings.Secure` on a Pixel/GOS device (VERIFY_FIRST #2 provider dump, already a runbook
step) to pin the real key names + storage types for: BT timeout, emergency owner info text,
priority conversations. Output: a key-confirmed table; any unpinned candidate is marked
"deferred — key not located" and excluded. Drop panic/USB/starred per §2/§3.

**Phase 1 — DND / Zen (`zen_mode`), RISKY.** Tests assert: `zen_mode` present, GLOBAL,
RISKY, `T1_GRANT`, `IntEnum(0,1,2,3)`; rejects `4`/`"none"`/`""`; the guardrail "every
non-DEVICE_SPECIFIC key has a concrete validator" stays green; `zen_mode` is NOT in
`defaultSyncSet` (RISKY). Add the row. (RISKY → inert in the default flow; documents intent
and pre-stages the opt-in cluster.)

**Phase 2 — Device / owner name (`device_name`), SAFE.** Tests assert: GLOBAL, SAFE,
`T1_GRANT`, `StringPattern` bounded length/charset; accepts `"JD Pixel 9"`, rejects a
2 KB string / control chars / newline-injection. Add the row. This one *is* in the default
SAFE cut and exports/applies immediately via the existing path.

**Phase 3 — Emergency owner info (gated on Phase-0 spike).** Only if the keys resolve and
are writable with `WRITE_SECURE_SETTINGS` on GOS: add `lock_screen_owner_info_enabled`
(SECURE/SAFE, `IntEnum(0,1)`) and the text key (SECURE/SAFE, bounded `StringPattern`).
Tests mirror Phase 2. If the spike shows the keys are deprecated/internal/system-uid-gated
on GOS → mark DEVICE_SPECIFIC or drop, with the reason recorded in the row's `reason` string
and the design doc.

**Phase 4 — Bluetooth timeout (gated on Phase-0 spike).** Only if a real key resolves: add
it SAFE-or-RISKY with the appropriate `IntRange`/`IntEnum`. Tests mirror Phase 1/2. If
unpinned → stays a documented open question (§9), no row.

**Phase 5 — Document the excluded traps.** Add explicit DEVICE_SPECIFIC/`reach=NA` *or*
documented-exclusion rows/notes for panic config, USB-when-locked, starred contacts, and
priority conversations, so the "looks SAFE, isn't" reasoning is **encoded, not merely
omitted** (mirrors the `SettingsAllowlist.kt` design intent, lines 28–29, and the
`known trap keys are not classified SAFE` guardrail test). For items that are not Settings
keys at all, a note in `settings_allowlist.md` (not a catalog row) is the right home.

Each phase is one small PR. Phases 1, 2, 5 are unblocked; 3 and 4 depend on Phase 0.

---

## 7. Security considerations (SAFETY-CRITICAL)

A bad allowlist row is the worst failure mode portage has: it could write a harmful secure
setting onto a freshly-set-up device. The controls are the **compiled allowlist + per-value
validator** (`CLAUDE.md` "Settings safety boundary"; THREAT_MODEL §10, malicious-sender
row). This PRP only *adds rows*, so the entire risk is "is each new row classified and
validated correctly."

**The invariants every new row must preserve (all enforced by `SettingsAllowlistTest`):**

- **`defaultSyncSet` is SAFE-only** — a key must be genuinely device-independent and
  harmless-by-copy to be SAFE. DND (`zen_mode`) is RISKY, not SAFE, precisely because a
  copied "none" state can silence the new phone (line 41). When in doubt, RISKY or
  DEVICE_SPECIFIC.
- **DEVICE_SPECIFIC ⇒ `reach == NA`** (never writable) and `Validator.None`. Panic / USB /
  enabled-a11y-class keys must be unreachable, not merely off-by-default.
- **Every SAFE/RISKY key has a concrete validator** (`Validator.None` is reserved for
  excluded keys). This is the named guardrail the prompt calls out — it **must stay green**.
- **Globally-unique key names** and **reach↔namespace agreement** (T1_GRANT ⇒ SECURE or
  GLOBAL) — a new row must not collide or mis-route.

**Validator design rules for new keys** (fail-closed, mirror `Validation.kt`):

- Enums/ranges must be *tight*: only values that are safe on any device. For `device_name`
  and owner-info text, bound **length and charset** (`StringPattern`) — reject control
  characters, newlines, and oversized strings to prevent lock-screen/UI injection or
  truncation surprises. Treat the value as hostile input (THREAT_MODEL §10: a hostile sender
  can send garbage values for SAFE keys).
- No free-form string key without a bounding pattern. No `IntRange` wider than the values
  the platform actually accepts.

**What `security-reviewer` MUST check on every PR (mandatory — privilege boundary,
`CLAUDE.md`):**

1. Each new SAFE row is **truly device-independent** (no panel-relative, component-
   referencing, or security-state value masquerading as a preference — the brightness /
   enabled_accessibility_services / adb_enabled trap pattern, lines 41/67/86).
2. The validator **cannot accept** a dangerous value (review the enum/range/pattern against
   the platform's real value space; check the hostile-input cases exist in `ValidationTest`).
3. No DEVICE_SPECIFIC row has a writable reach; no SAFE/RISKY row has `Validator.None`.
4. RISKY keys are genuinely kept out of `defaultSyncSet` (no accidental opt-in).
5. The guardrail test changes (if any) still *test the same control for the right reason* —
   a green test that no longer exercises the invariant is worse than none (`CLAUDE.md`).

**Never transfer DEVICE_SPECIFIC.** Panic config, USB hardening, and anything tied to this
device's hardware/security posture is DEVICE_SPECIFIC by definition and is never written.

---

## 8. Test plan & CI gates

- **Named CI gate:** `gradle :settings-catalog:test` (`.github/workflows/build.yml`) — the
  safety-critical allowlist-invariant suite. Every phase must keep it green; this is the
  primary acceptance signal.
- **`SettingsAllowlistTest`** (guardrail): extend `known trap keys are not classified SAFE`
  with the new excluded names (panic/USB if catalogued as traps); the existing five
  invariant tests (SAFE-only default set, excluded-unreachable, unique names,
  reach↔namespace, every-applied-key-validated) automatically cover the new rows — confirm
  they still pass.
- **`ValidationTest`**: add per-validator cases for each new key — in-range/in-enum accepts,
  and the hostile set (`""`, oversized, wrong-type, injection) rejects. Reuse the
  `every SAFE and RISKY key's validator can run` belt-and-braces sweep (it already iterates
  the whole catalog over hostile input).
- **`SettingsProvidersTest`** (`:providers:testDebugUnitTest`, also a CI gate): add an
  apply/export round-trip for `device_name` (SAFE) proving it flows through the existing
  data path; add a refusal case proving `zen_mode` (RISKY) is **not** applied in the default
  SAFE-only path even with a valid value (mirrors the existing
  `apply refuses a RISKY Tier-1 key…` test, lines 284–296).
- **No protocol/manifest tests change** — the wire is unchanged (§5).

Coverage bar (`rules/common/testing.md`): each new key needs validator unit coverage plus
catalog-invariant coverage; the existing data-path tests already cover routing.

---

## 9. Open questions / spikes

Persist these to `.omc/plans/open-questions.md` as well.

1. **Bluetooth timeout key name** — is there a real `Settings.{Global,Secure}` key for
   "Bluetooth scan-availability / off timeout" on current GOS (Android 16), or is it a
   GOS-private toggle with no Settings key? **Resolve via VERIFY_FIRST #2 provider dump.**
   If unpinned → no row, documented exclusion.
2. **Emergency owner info keys** — are `lock_screen_owner_info` /
   `lock_screen_owner_info_enabled` still real, readable, and writable with
   `WRITE_SECURE_SETTINGS` on GOS, or deprecated/internal/system-uid-gated (the
   VERIFY_FIRST #2 "additionally system-uid-gated even with the grant" open question,
   `settings_allowlist.md` lines 129–132)? Determines SAFE vs drop.
3. **Priority conversations** — confirm this is `NotificationManager` conversation/channel
   state and **not** a Settings key. If confirmed → out of scope here, belongs to backlog #5
   (notification parity). Spike = read the people-space/priority storage on-device.
4. **`zen_mode` semantics across devices** — confirm the int enum domain `{0,1,2,3}` is
   stable on GOS and that copying a non-zero zen mode cannot silently silence the new phone
   before the user sees it (keeps it RISKY, opt-in — never promote to SAFE without this).
5. **Panic config / USB-when-locked** — confirm (to close the loop) there is genuinely **no
   stable plain-Settings key**; if one is ever found it is DEVICE_SPECIFIC regardless. Record
   the finding so future agents do not re-litigate (these are already excluded at
   `settings_allowlist.md` line 104).
