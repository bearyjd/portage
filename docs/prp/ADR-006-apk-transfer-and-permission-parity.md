# ADR-006 — APK transfer (silent batched install) & runtime-permission parity

**Status:** ACCEPTED — design freeze (2026-06-20). Locks the wire format, module placement, fallback policy,
byte ceilings, permission model, and capability plumbing for the keystone feature so the implementation phases
(P1–P6) start from settled decisions. Supersedes nothing; extends ADR-001 (privilege feasibility) and ADR-003
(self-contained ADB bridge). Full plan: `.omc/plans/portage-full-transfer.md`.

## Context

portage's app set is currently transferred as an **inventory only** (`APP_INVENTORY` → a tap-per-app reinstall
checklist of store deep links; `providers/.../inventory/InventoryProviders.kt:108-139`). Apps do not actually
arrive — the user re-downloads each by hand. This is the largest gap between portage and a "the new phone feels
like the old one" transfer, and the biggest click cost.

The privileged machinery to close it already exists but is dormant: `AdbBridge.installApk()` runs a
`pm install-create/-write/-commit` session (`LocalAdbBridge.kt:161-195`, single hardcoded `base.apk`), the probe
already reports a `SILENT_INSTALL` capability (`AdbBridge.kt` `PrivilegedCapability`, enum at `:123-141`), and
`grantRuntimePermission()` (`AdbBridge.kt:92-93`) is defined with zero production call sites. The `APK("apk",
Tier.TIER1)` wire kind is already reserved in the frozen enum (`core-model/.../Manifest.kt:30`) with no providers.

The keystone wires this into the receiver transfer flow: a real **APK item kind** that ships base + split APK bytes,
installed **silently and batched** when the bridge is present, with an honest per-app `PackageInstaller`-confirm
fallback when it is not, plus **default-safe runtime-permission parity**. This touches the wire protocol, the
privilege boundary, and `pm install`, so every implementation phase carries a mandatory `security-reviewer` lane.

The chosen architecture is Option C (hybrid, capability-gated) from the plan; the consensus review (Architect
SOUND-WITH-AMENDMENTS, Critic APPROVED-WITH-NITS) surfaced a module-boundary hazard (a `:providers` apply provider
must NOT hold `AdbBridge`), a streaming-vs-OOM codec hazard, and the split-APK **target-compatibility** landmine
(byte-exact reconstruction ≠ installability on a different-ABI/density/locale device). This ADR locks the decisions
that resolve them before any wire code is written.

## Decision

Add a self-describing, **streamed** multi-file container for the existing `ItemKind.APK`, install it through
**narrow injected seams** (never a direct `:providers → :adb-bridge` dependency), reconcile splits against the
target device, and layer **deferred, default-safe** permission parity on top. No `PROTOCOL_VERSION` bump (the kind
is already in the append-only enum; an older receiver lacking the handler degrades via `UNKNOWN_KIND`, consistent
with how `WALLPAPER`/`SOUND_SELECTION`/`APP_BACKUP_RELAY` were added).

## Decisions locked

### D1 — Wire payload format (framed, streamed multi-file container)

The APK item payload is a recursive application of the proven `RelayCodec` framing (`UTF-8 JSON line + '\n' +
bytes`, `AppBackupRelayProviders.kt:157-239`), extended from one blob to N:

```
<JSON ApkContainerHeader> '\n'
  then, repeated fileCount times:
    <JSON ApkFileEntry> '\n'
    <entry.length bytes, streamed>
```

```kotlin
@Serializable
data class ApkContainerHeader(
    val packageName: String,        // re-validated against the package grammar; never a path
    val versionCode: Long,
    val fileCount: Int,             // bounded: 1..MAX_APK_FILES (see D4)
    val capturedPermissions: List<String> = emptyList(), // ADVISORY; Phase-5 only; see D5
)

@Serializable
data class ApkFileEntry(
    val name: String,               // "base", or a split name (validated; see AC-6b)
    val role: ApkFileRole,          // BASE | CONFIG | LANGUAGE | FEATURE
    val abi: String? = null,        // e.g. "arm64_v8a"   — derived on sender, nullable
    val density: String? = null,    // e.g. "xxhdpi"
    val lang: String? = null,       // e.g. "en"
    val length: Long,               // bytes that follow this entry line; cross-checked vs streamed count
)
```

- Production read uses a `readHeaderFrom`-style newline scan (≤ `MAX_HEADER_BYTES` = 4 KiB per line, as relay)
  then a `streamBlob`-style chunked copy of exactly `entry.length` bytes **straight to a per-split staged file** —
  the full container is **never materialized in memory** (RelayCodec precedent; the OOM-prone full-materialization
  `readFrom` is a TEST helper only — Critic M1).
- `capturedPermissions[]` is locked into the header **now** so Phase 5 (permission parity) causes no wire churn,
  even though nothing reads it until then.
- Per-split `abi`/`density`/`lang` tags are locked **now** because the receiver needs them to reconcile against the
  target device (D3). They are advisory; the receiver re-derives role/compatibility, never trusts them blindly.
- Integrity: the whole-item SHA-256 + declared `size` agreement already enforced by `ItemStreamReceiver`
  (`:124-134` + running-size guard) covers the container; the sum of `entry.length` is additionally cross-checked
  against the item size, and each name is validated (D-AC-6b) before it becomes a staged filename.

### D2 — Module placement

- The **codec + header data classes** live in `:providers` (package `cc.grepon.portage.providers.apk`), beside
  `RelayCodec`. They need only kotlinx-serialization + the `:providers` `JsonLines` helper + I/O streams — **no
  Android types** — so `:core-model` (Android-free, no `JsonLines`) is the wrong home and `:providers` is correct.
  (`:providers` is already a `com.android.library`, so keeping the codec Android-type-free is a deliberate discipline,
  mirroring `RelayCodec`/`WallpaperCodec` — not a module-graph constraint.)
- `ApkExportProvider` and `ApkApplyProvider` also live in `:providers`. Android-specific reading
  (`PackageManager.applicationInfo.sourceDir` + `splitSourceDirs`, target `Build.SUPPORTED_ABIS`/`densityDpi`/
  locales) is done in the apps and **injected via seams**, exactly as the relay provider injects `openPickedFile`/
  `handoff` and settings injects `TierOneGrant` (`SettingsProviders.kt:82-90`, adapted at `MainActivity.kt:172-178`).
- **`:providers` keeps its dependency set at `:core-model` + `:settings-catalog` only** (`providers/build.gradle.kts`).
  It MUST NOT gain a `:adb-bridge` edge — that would leak the bridge toward the sender (`:app-send → :providers`).
  The `AdbBridge`-backed install seam is adapted inside `:app-recv` (Critic C1).

### D3 — Split target-compatibility policy (the install-time decision tree)

Byte-exact reconstruction does not guarantee installability: the source device's config splits (e.g.
`arm64_v8a`/`xxhdpi`/`en`) may not match the target's ABI/density/locale, and the source **never held** the
target's splits. The receiver therefore RECONCILES before committing, and uses this ordered policy:

1. **Reconcile + subset-install.** Compute the target's required config splits (`Build.SUPPORTED_ABIS`,
   `densityDpi`, configured locales) and install `base` + the matching subset of the source's splits + all language
   splits the user kept. For the dominant GOS migration (Pixel→Pixel, same ABI) this succeeds cleanly. **Mechanics
   (P3):** the install session MUST issue one `pm install-write` per staged file (base + each kept split) into the
   single `install-create` session before `install-commit` — today's `installApk` writes exactly one hardcoded
   `base.apk` (`LocalAdbBridge.kt:171`), so P3 must not ship a base-only session by omission.
2. **Required split genuinely absent → explicit per-app SKIP** with a surfaced "incompatible on this device —
   install from store" outcome and the existing inventory deep-link. portage cannot synthesize a split it never
   had; pretending otherwise produces a broken app. This is the honest terminal.
3. **Commit still rejects for a split/ABI/density/locale reason despite reconciliation → Tier-0 `PackageInstaller`
   confirm retry**, then the (2) skip if that also fails.
4. **base-only install is NOT an automatic fallback** — a base missing its required ABI/density split installs but
   crashes/renders wrong. base-only is used only for apps that are genuinely single-APK (no required splits), which
   is just the trivial case of (1).

A split-attributable commit rejection is a **distinct, surfaced `ApplyOutcome`** (not a generic failure). Its
real-device closure is **OPEN**: single-device smoke has source == target and cannot exercise it (joins the
cross-device OPEN set, ADR-003 §7).

### D4 — Byte ceilings

Reasoned against the **≈60-min data-phase budget** (`DATA_PHASE_TIMEOUT_MS` = 60 min, `TransferTimeouts.kt:39`, +
at most one ~10-min idle `soTimeout` per `:27-29` — "not a crisp 60 min") **and** the double-stage storage cost
(`cacheDir` item file `ItemStreamReceiver.kt:137` → N per-split staged files → `pm` install-session copy ≈ 2–3×):

- `MAX_APK_ITEM_BYTES = 1 GiB` (per single app's full split set). Covers ~all real apps; the rare larger game is a
  D3-style "too large — install from store" skip. Comfortably within the streaming machinery proven at the 2 GiB
  relay cap (`MAX_RELAY_ITEM_BYTES`, `ReceiverViewModel.kt:372`).
- `MAX_APK_TOTAL_BYTES = 8 GiB` (aggregate across all selected APK items) — there is **no aggregate cap today**
  (`capFor`/`maxBytesByKind` is per-item, `ItemStreamReceiver.kt:51`). 8 GiB clears a heavy app set, transfers in
  ~11–55 min across 20–100 Mbit/s LAN, and bounds runaway. Over budget → the user deselects apps / installs the
  largest from store (honest message).
- These are scoped to `ItemKind.APK` via `maxBytesByKind` and MUST NOT leak into Tier-0/PII kinds (the 64 MiB
  `DEFAULT_MAX_ITEM_BYTES`, `ItemStreamReceiver.kt:232`, stays the guard everywhere else).
- A receiver-side `getUsableSpace()` pre-check (AC-16) is the **dynamic** backstop: require headroom ≥ (largest item
  × 2) and an aggregate check; fail closed with a clear message. Both ceilings are tunable post-hardware.

`MAX_APK_FILES` (D1 `fileCount` bound) = 64 — generous for base + config/density/abi/language/feature splits while
bounding a malformed header.

### D5 — Permission-parity model (default-safe; the dangerous set is opt-in)

Runtime-permission parity is `PERMISSION_PARITY`-gated and **deferred to Phase 5 behind a GO/NO-GO gate** — it is
the first production call site of `grantRuntimePermission()` and is cuttable without affecting the landed keystone.

- **Default mode** re-grants ONLY the lower-sensitivity, GOS-user-controllable network/sensor special-permission
  parity set. On GOS these toggles are modeled AS the `INTERNET` / `OTHER_SENSORS` permissions, reachable via
  `pm grant/revoke` (ADR-001 §2 row 5) — unlike stock Android where `INTERNET` is an install-time/normal perm.
  Seed allowlist `{ INTERNET, OTHER_SENSORS }`, best-effort, failure logged not fatal — but **`INTERNET` only is
  verified** (V7 PASS, `VERIFICATION-RUNBOOK.md:109`); **`OTHER_SENSORS` is PROVISIONAL** (V7 TENTATIVE, `:110` —
  `pm grant` returned exit 0 against an app that did not declare it) and MUST be re-tested against a
  manifest-declared sensor app in Phase 5 before it is trusted in the default-grant set. (Ordinary
  "normal"-protection perms are auto-granted at install, so they need no action.) **No dangerous runtime permission
  group is ever auto-granted in default mode.**
- **Opt-in "match app permissions" surface**: only if the user explicitly enables it, an itemized review lists, per
  app, the dangerous perms the source held; each requires an explicit confirmation before any `pm grant`. Nothing
  dangerous is granted without a confirmed item.
- **Never granted:** signature/system perms, perms the target app did not declare, and anything outside the source's
  actual `capturedPermissions[]` set. Every grant is logged by name + result for audit.

The exact final membership of the default allowlist and whether the opt-in confirms per-app or per-permission stay
open for Phase 5; the **model** above is locked now.

### D6 — Capability-set plumbing

The probed `Set<PrivilegedCapability>` lives only in the wizard's `StateFlow` (`PrivilegeWizard.step` →
`Step.Ready(capabilities)`, set at `PrivilegeWizard.kt:223`) held by the process-scoped `PrivilegeWizardHolder`
(`app-recv/.../privilege/PrivilegeWizardHolder.kt`); it is **not durably persisted**.

- The `:app-recv` receiver apply factory reads the current capability set from `PrivilegeWizardHolder` at transfer
  start (`Ready → capabilities`, else `emptySet()`), and **chooses which install seam to inject** into
  `ApkApplyProvider`: the bridge-backed `ApkInstaller` iff `SILENT_INSTALL ∈ set`, otherwise the Tier-0
  action-emitter seam. The capability set is consumed **in `:app-recv`** and never reaches `:providers` (preserves
  C1/D2).
- Because the probe is point-in-time (the wizard's terminal disconnect-in-`finally` is `PrivilegeWizard.kt:218-221`;
  `LocalAdbBridge.kt:220-235` is the probe's own transient-retry reconnect), the silent seam must tolerate a
  **stale-positive**: on `BridgeUnavailable` at apply time it degrades to the Tier-0 fallback for the remaining apps.
- Process death between wizard and transfer loses the set → `emptySet()` → Tier-0 fallback. This is the **safe**
  failure direction (never a wrong silent install); durable persistence of the last probe result is explicitly out
  of scope.

## Consequences

- Largest wire surface portage has added (a multi-file streamed container) and the largest payloads; mitigated by
  per-item + aggregate caps, streaming (no full materialization), and the free-space pre-check.
- Biggest `security-reviewer` load to date: wire format, `pm install` session, seam discipline, split injection
  guard (AC-6b), and permission grants. Every keystone phase gets the lane; Phase 5 is the headline review.
- New production call sites: the split-aware `installApk()` (P3) and, when un-gated, `grantRuntimePermission()` (P5).
- The no-escalation CI gate (`build.yml:47-72`) scans the **sender** only and does NOT catch a recv-side
  `:providers → :adb-bridge` edge; module discipline rests on review + the raw-`.shell()` call-site scan
  (`build.yml:73-81`). Both gates are in the keystone CI list.
- "Keystone landed" = Phases 1–4 + 6 (install works, default-safe, honest fallback). Permission parity (P5) is a
  separately-gated, cuttable follow-on.

## Risks & contingencies

- **Silent install rejected by GOS at commit** (policy/signature/downgrade): session `install-abandon` → no
  half-install → per-app failure surfaced → Tier-0 retry. Never claim success on a non-zero commit exit code
  (`LocalAdbBridge.kt:179-191`).
- **Split target mismatch** (D3): reconcile-then-skip; OPEN on hardware (single device can't test source ≠ target).
- **Permission over-grant** (D5): default-safe model + itemized opt-in + mandatory `security-reviewer`; a regression
  that auto-granted a dangerous perm fails its AC in CI before merge.
- **Storage exhaustion** on a fresh phone (double-staging): `getUsableSpace()` pre-check fails closed.

## Follow-ups

- Record single-device smoke evidence (silent self-install + Tier-0 fallback) with the GOS build fingerprint
  (`ro.build.fingerprint`) and the literal `pm install-commit` exit code (ADR-001 §2.6 fingerprint discipline).
- **OPEN (no completion claim without two devices):** cross-device pair→install (ADR-003 §7) and split
  target-compatibility (D3).
- Phase 5 GO/NO-GO: finalize the default permission allowlist + the opt-in confirm granularity.
- UX: confirm whether GOS A16 can batch multiple `PackageInstaller` confirm intents into fewer taps; if not, the
  Tier-0 fallback is one-tap-per-app and the UX copy must say so.
- Deferred, not part of the keystone: F1 relay export SAF picker, F2 nav-mode parity, F3 wizard click reduction.
