# PRP-05 — Notification channel / per-app notification parity

Status: **DECLINED — 2026-06-13 (per-channel parity infeasible via shell).** On-device probe
(rango, GOS A16): `cmd notification` exposes **no per-channel importance/block verb** (only
listeners, DND-per-app, bubbles), and per-app `NotificationChannel`s are app-owned and created at
runtime — not externally writable. The only reachable surface (DND-per-app) is too thin to justify
a new `ItemKind` + wire bump. DROPPED per user decision; see `SPIKE-RESULTS-2026-06-12.md`. Original
draft retained below for the record.

---

## 1. Summary & user value

Migrating to a new phone silently resets every app's notification behavior to its default.
A user who spent months muting a noisy app, silencing one chatty channel, or turning a
group's alerts down to "silent" arrives on the new device to a wall of restored noise. That
re-tuning is invisible work that no de-Googled migration path carries today.

**Signal.** CalyxOS's Seedvault docs explicitly list notification restore as *"planned, not
currently available"* (`docs/prp/feature-research-2026-06.md:19,49`); it is a named Seedvault
gap, and a loud, broad migration annoyance. portage's strategic position is to be the reliable
LAN parity layer for the categories it *can* own (`feature-research-2026-06.md:36-41`), and
notifications are named there alongside settings, Wi-Fi, and pairings.

**What this feature carries.** Per-app notification configuration, at two levels of ambition:
- **(a)** each app's master "notifications enabled" on/off state — the appop the OS enforces
  for `POST_NOTIFICATIONS`;
- **(b)** per-`NotificationChannel` state (blocked / importance / maybe sound+vibration) for
  channels that **already exist** on the target after the app is installed and run once.

The honest ceiling is **state parity for channels the target app creates itself** — portage
re-applies the user's prior choices onto them. It does **not** invent channels.

---

## 2. Scope & non-goals

### In scope (the transferable slice)
- Per-app master notification enabled/disabled state (the `POST_NOTIFICATIONS` appop / the
  "notifications: on/off" toggle the OS owns, not the app).
- Per-channel **blocked** state and **importance** for channels present on the target, keyed by
  `(packageName, channelId)`.
- Best-effort, per-record resilient apply, mirroring every other provider: a missing app, a
  missing channel, or a denied write **skips that record and never aborts the batch**
  (`providers/.../Providers.kt:50-57`, the `ApplyProvider` contract).

### Explicit non-goals
- **No channel creation.** `NotificationChannel` objects are created and owned by each app at
  runtime via its own `NotificationManager`. An external app — even with shell uid — generally
  cannot materialize another app's channels, their ids, names, or groups. We re-apply state onto
  channels the target app makes; we never fabricate them. (This is the feasibility crux — §3.)
- **No app *data*.** This is configuration state (toggles/importance), not notification content,
  history, or any app blob. It stays clear of the Seedvault division of labor — no
  `seedvault.blob`, no new app-data item kind (`core-model/.../Manifest.kt:32-35`).
- **No notification *posting* or content.** portage never reads or moves notifications.
- **No DND / Zen global rules.** Global DND/Zen is a *settings*-catalog candidate (backlog #3,
  `feature-research-2026-06.md:17`), owned by `settings-catalog`, not this feature.
- **No secondary profiles / Private Space.** Owner profile only (ADR-001 §2.4,
  `ADR-001-privilege-feasibility.md:65-67`).

### How this differs from — and must NOT duplicate — existing permission parity
portage already has a **runtime-permission parity *capability*** on the bridge:
`grantRuntimePermission` / `revokeRuntimePermission` and the `PERMISSION_PARITY` capability
(`adb-bridge/.../AdbBridge.kt:91-96,130-131`; probe at `LocalAdbBridge.kt:217-222`). Note: as of
this writing that capability has **no `ExportProvider`/`ApplyProvider` consumer** — the registry
in `app-recv/.../MainActivity.kt:109-130` wires contacts/calendar/call-log/SMS/inventory/settings
only. So there is no "permissions provider" to mirror verbatim; this PRP would be among the first
consumers of the parity capability.

The overlap to avoid: on Android 13+, the **master** notification toggle is partly the
`POST_NOTIFICATIONS` runtime permission, reachable via `pm grant/revoke <pkg>
android.permission.POST_NOTIFICATIONS`. If a future runtime-permission-parity provider lands and
includes `POST_NOTIFICATIONS`, then part (a) of this feature is **already covered there** and must
not be re-implemented. **Decision rule:** if/when a permission provider exists, this feature owns
only the part that runtime-permission grants do *not* express — the **appop** master toggle on
pre-13 channels and, primarily, **per-channel** state (§5). The spike (§3) must measure whether
the appop and the `POST_NOTIFICATIONS` permission are the same lever on GOS A16 or two separate
ones, and the PRP that lands first claims the master toggle.

---

## 3. Feasibility & privilege (the crux — be honest)

**Channel-ownership constraint.** `NotificationManager.createNotificationChannel()` is callable
only by the owning app; channels live in the app's own record in the system
`NotificationManagerService`. There is **no public API and no documented shell command to create a
channel on behalf of another package.** Therefore the only honest model is **state re-application
onto channels that exist after the target app installs and runs** — never recreation.

**What shell uid (via `AdbBridge`) *might* reach** — all UNVERIFIED on GOS A16, this is the spike:

| Lever | Candidate command (shell uid) | What it would set | Confidence |
|---|---|---|---|
| Master enabled (appop) | `cmd appops set <pkg> POST_NOTIFICATIONS allow\|ignore` | per-app on/off | medium |
| Master enabled (perm) | `pm grant/revoke <pkg> android.permission.POST_NOTIFICATIONS` | per-app on/off (A13+) | medium — overlaps §2 |
| Per-channel state | `cmd notification …` subcommands (e.g. `allow_listener`/channel ops, if any exist) | block / importance | **low — may not exist** |
| Read current state | `dumpsys notification`, `cmd notification` listings | enumerate channels+state to diff | medium |

`AdbBridge` runs exactly these as typed ops: it already exposes `setSmsRoleHolder` via
`cmd role …` (`AdbBridge.kt:104-106`) and `setNavigationMode` via `cmd overlay …`
(`AdbBridge.kt:98-102`), both built through `ShellArgs.command(...)` argv (`AdbBridge.kt:108-113`)
— so adding `cmd appops` / `cmd notification` typed ops is the established extension shape. The
`PERMISSION_PARITY` probe (`pm list permissions`, `LocalAdbBridge.kt:217-222`) is the model for a
new `NOTIFICATION_PARITY` capability probe.

**Critical unknown:** Android's `cmd notification` surface is sparse and version-volatile, and
GrapheneOS hardens shell access (ADR-001 §2, `ADR-001-privilege-feasibility.md:52-73`). Per-channel
writes via shell **may simply not be exposed**. ADR-001 already flags that GOS per-app levers are
"presented as permissions but the enforcement plumbing is custom; treat as a bonus"
(`ADR-001-privilege-feasibility.md:159-162`). Treat per-channel parity as a *bonus that the spike
must earn*, exactly as ADR-001 treats V7 specials.

**REQUIRED spike (blocking — no UI/provider work before it lands).** On a real Pixel/GOS A16
device, following the `VERIFICATION-RUNBOOK.md` V7 pattern (per-item, per-command, record
stdout/exit/fingerprint, `ADR-001-privilege-feasibility.md:129-137`):
1. `cmd appops set <victim.pkg> POST_NOTIFICATIONS ignore` then `allow` → does the master toggle
   actually flip and survive? Is it the same as `pm grant/revoke POST_NOTIFICATIONS`?
2. Enumerate `cmd notification ?`, `dumpsys notification --noredact <pkg>` → is there ANY shell
   verb that blocks a channel or sets channel importance for another package?
3. If a per-channel write verb exists: set it, reboot, re-read → does it persist?
4. Confirm channels are absent until the app runs once (justifies the deferred-apply sequencing).

The spike's verdict gates Phase 2 entirely (§6). **Do not overpromise full channel recreation in
any user-facing copy.**

**Tier.** Privileged. Unlike settings parity (one-shot grant then no live bridge,
`ADR-001-privilege-feasibility.md:21-39`), appop/notification writes need **shell uid at call
time**, like `pm grant/revoke` and `cmd role` (`ADR-001-privilege-feasibility.md:31-39` table).
So this feature requires the `AdbBridge` connection **live during the apply pass** — same hot-path
posture as runtime-permission parity and SMS-role restore.

---

## 4. Architecture fit

Mirror the **two existing shapes** that already match this problem:

- **`AppInventoryApplyProvider`** (`providers/.../inventory/InventoryProviders.kt:103-140`) for the
  *sequencing and gating* model: it carries per-app records keyed by `packageName`, partitions on
  "already installed", and emits actions rather than forcing writes. Notification parity carries
  per-app records keyed by `packageName` and partitions on "app present / channel present".
- **`SettingsApplyProvider`** (wired with a `TierOneGrant` shell adapter at
  `app-recv/.../MainActivity.kt:124-128`, `141-148`) for the *privileged-write seam*: a narrow
  interface (`NotificationConfigStore`) hides the `cmd appops` / `cmd notification` calls behind
  `AdbBridge`, and self-skips when the bridge is unavailable — exactly as Tier-1 settings keys
  self-skip without a grant.

**Provider pair** (new module dir `providers/.../notifications/`):
- `NotificationExportProvider : ExportProvider` — reads the source device's per-app + per-channel
  notification state into the wire form. Sender side needs no privilege to *read* its own state
  (`NotificationManagerService` dump is shell-readable; in-app, `NotificationManager` exposes the
  caller's own channels but **not** other apps' — so the export likely needs the bridge too; the
  spike settles whether a non-privileged read of all apps' channel state exists). Denied/empty ⇒
  `available()` false, empty export (`Providers.kt:22-25` contract).
- `NotificationApplyProvider : ApplyProvider` — parses, diffs against channels present on the
  target, applies via the `NotificationConfigStore` seam, best-effort per record.

**New `AdbBridge` typed ops** (the ONLY privileged entry point — `AdbBridge.kt:12-31`):
```kotlin
suspend fun setNotificationsEnabled(pkg: String, enabled: Boolean): OpResult =
    typedOp("cmd", "appops", "set", pkg, "POST_NOTIFICATIONS", if (enabled) "allow" else "ignore")
// per-channel op added ONLY if the spike proves a verb exists; otherwise this method never ships.
```
Plus a `NOTIFICATION_PARITY` entry in `PrivilegedCapability` (`AdbBridge.kt:122-141`) with a
read-only probe (`LocalAdbBridge.probeCapabilities`, `LocalAdbBridge.kt:202-247`).

**core-model item kind.** A new `ItemKind` value is needed — but the enum is **FROZEN per
protocol version** (`Messages.kt:14-30`, "Order/values are FROZEN"; `Manifest.kt:23-36`). Adding
`NOTIFICATIONS("notifications", Tier.TIER1)` is a **wire/protocol change** that must go in a
**deliberate v-bump**, appended at the end of the enum (never reordered), with the receiver
rejecting it as `UNKNOWN_KIND` on older builds (`Providers.kt:59-73`). This is the single most
consequential cross-cutting decision in the PRP and must be called out in the ADR.

**Apply-path ordering (the sequencing crux).** Channels exist only *after the app is installed and
launched*. The receiver's flow is: inventory apply → user taps the **reinstall checklist**
(`InventoryProviders.kt:103-140`) → apps install over time → apps must be *run once* to create
channels. So notification per-channel apply **cannot run in the same synchronous batch** as the
rest; it is a **deferred second pass**. Concretely:
- Part (a) master-enabled state can be applied **eagerly for apps already present** during the
  normal batch, and **deferred** for apps still on the reinstall checklist.
- Part (b) per-channel state is inherently deferred: it must be re-applicable **later**, after the
  user has installed + opened the apps. This implies a small persisted "pending notification
  parity" ledger (pattern precedent: the SMS role uses a persistent ledger + onResume reconcile,
  `CLAUDE.md` Post-Tier-0 notes; mirror that mechanism, not its SMS specifics) and an in-app
  "finish notification parity" action the user runs once apps are set up. The batch apply records
  `SKIPPED("app/channel not present yet — finish later")` for anything not yet applicable.

This deferred-pass design is the honest consequence of channel ownership; it must not be hidden.

---

## 5. Data model & wire representation

Per-app record carrying master state plus the channels we observed on the source:
```kotlin
@Serializable
data class NotificationChannelState(
    val channelId: String,     // app-owned id; opaque, matched verbatim on the target
    val blocked: Boolean,
    val importance: Int,       // NotificationManager.IMPORTANCE_* verbatim; passed through, validated as a known constant
)

@Serializable
data class AppNotificationConfig(
    val packageName: String,   // validated by the inventory PACKAGE_NAME grammar before any shell use
    val masterEnabled: Boolean,
    val channels: List<NotificationChannelState> = emptyList(),
)

@Serializable
data class NotificationParity(val apps: List<AppNotificationConfig>)
```
Wire form follows the inventory provider: a single JSON document via `JsonLines.format`
(`InventoryProviders.kt:95-100`).

**Validation (receiver sovereignty — THREAT_MODEL §4, `THREAT_MODEL.md:57-58`):**
- `packageName` **must** pass the existing `InstallAction.PACKAGE_NAME` regex
  (`InventoryProviders.kt:62`) before it is ever interpolated into a shell argv — sender-supplied
  names that don't match are **dropped** (same HIGH-severity fix rationale as inventory,
  `InventoryProviders.kt:56-62`). Everything else is funneled through `ShellArgs.command(...)`
  (`AdbBridge.kt:108-113`) which already rejects shell metacharacters.
- `importance` is validated against the known `IMPORTANCE_*` set; unknown values ⇒ record skipped,
  never written (`THREAT_MODEL.md:29`, malicious-sender row — apply only validated values).
- `channelId` is opaque and only ever matched against ids the *target app* already created; an id
  with no match on the target is a no-op skip (we never create it).
- A hostile sender's worst case is garbage importance values for real channels — clamped/rejected
  by the known-constant check, same posture as settings range clamps (`THREAT_MODEL.md:29`).

---

## 6. Phased implementation plan (TDD, small mergeable phases, honestly gated)

Every phase: branch off `main`, author → independent `code-reviewer`, and
**`security-reviewer` is mandatory** (this touches the privilege boundary + a wire-protocol
change — `CLAUDE.md` working cadence). Squash-merge, delete branch.

**Phase 0 — Spike (blocking, no production code).** Run the §3 spike on a Pixel/GOS A16 device.
Land the results as **ADR-005** (notification-parity feasibility) with the filled command/exit/
fingerprint table, in the `VERIFICATION-RUNBOOK.md` V7 style. Verdict picks the path:
- master-only feasible → Phases 1 only;
- per-channel feasible → Phases 1 + 2;
- neither → **decline the feature**, record the rationale in `feature-research-2026-06.md`
  declined list, ship nothing. (Honest gating: this is a real possible outcome.)

**Phase 1 — Per-app master-enabled parity (only if Phase 0 proves the appop lever).**
1. (RED) `core-model`: append `NOTIFICATIONS` `ItemKind`; test the wire string + that older
   receivers map it to `UNKNOWN_KIND`. Bump protocol version per `PROTOCOL.md`.
2. (RED→GREEN) `adb-bridge`: add `setNotificationsEnabled` typed op + `NOTIFICATION_PARITY`
   probe; `LocalAdbBridgeTest` fakes the shell exit codes (existing pattern,
   `LocalAdbBridgeTest.kt:386,412-425`); `NoopAdbBridge` returns `BRIDGE_UNAVAILABLE`
   (`NoopAdbBridge.kt`).
3. (RED→GREEN) `providers/notifications`: `NotificationExportProvider` +
   `NotificationApplyProvider` against a `FakeNotificationConfigStore`; cover denied-permission ⇒
   empty export, missing app ⇒ skip, bridge-unavailable ⇒ self-skip (mirror
   `SettingsProvidersTest` / `InventoryProvidersTest`).
4. (GREEN) `app-recv`: register the apply provider in `MainActivity` registry
   (`MainActivity.kt:109-130`); wire the `NotificationConfigStore` to `AdbBridge`; deferred-ledger
   reconcile on `onResume` for apps not yet present.
5. **De-dup gate:** confirm against the runtime-permission story (§2) that master parity isn't
   double-shipped; if a perm provider owns `POST_NOTIFICATIONS`, Phase 1 scopes to the appop-only
   delta.

**Phase 2 — Per-channel state parity (ONLY if Phase 0 found a working per-channel verb).**
1. Extend wire model with `channels` (already in §5 schema; feature-flag the field's *apply*).
2. Add the per-channel typed op to `AdbBridge` (guarded — never compiled in if the verb doesn't
   exist on the verdict device).
3. Deferred second-pass apply + the in-app "finish notification parity" action that diffs the
   pending ledger against channels now present and applies, per record, best-effort.
4. Persisted-ledger process-death safety net (mirror the SMS `SmsRoleLedger` reconcile pattern,
   `CLAUDE.md` Post-Tier-0 notes).

If Phase 0 yields master-only, Phase 2 is **deferred/declined** and the PRP closes at Phase 1
with an explicit "per-channel not reachable on the measured fingerprint" note.

---

## 7. Security considerations

- **Privilege boundary.** All writes go through `AdbBridge` typed ops only; **no module outside
  `:adb-bridge`/`:wizard` may speak ADB or call raw `shell()`** (`AdbBridge.kt:27-31`, `CLAUDE.md`
  hard rule). `NotificationConfigStore` is a narrow seam over the bridge, not a second ADB path.
  Raw `cmd notification`/`cmd appops` strings must be argv-built via `ShellArgs.command`
  (`AdbBridge.kt:108-113`) — string interpolation of a package name is a **review blocker**.
- **Mandatory reviews.** `security-reviewer` is required (privilege boundary + wire-protocol
  change), in a separate lane from the author (`CLAUDE.md` working cadence). The bar: tests must
  pass for the *right* reason — a green test that doesn't exercise the package-name drop or the
  bridge-unavailable self-skip is worse than none.
- **Don't re-enable what the user silenced — privacy direction matters.** A user who *disabled*
  notifications for an app on the old phone did so deliberately, often for privacy. Apply must be
  faithful in **both** directions (disabled→disabled), and the safer failure is to **leave the
  target at its current state** when uncertain, never to force-enable. Forcing notifications *on*
  is the more harmful error and must be the one we avoid; surface the apply as user-reviewable.
- **Receiver sovereignty (THREAT_MODEL §10, `THREAT_MODEL.md:29`).** The receiver acts only on its
  compiled `NOTIFICATIONS` handler, only for package names passing the grammar, only with
  validated `IMPORTANCE_*` constants, only on channels the target already owns. A hostile sender
  cannot create channels, cannot reach apps not installed, and cannot smuggle shell metacharacters
  past `ShellArgs`.
- **Live-bridge exposure window.** Because writes need shell uid at call time, the bridge is live
  during the apply/finish pass; it **must disconnect right after** (the wizard's
  disconnect-after-probe discipline, `CLAUDE.md` ADR-003 notes) — never hold shell uid open for a
  deferred ledger; reconnect on demand when the user runs "finish notification parity".

---

## 8. Test plan & CI gates

Unit (JVM, no device — the repo has **no local Android build**; CI is the only compile/test gate):
- `core-model`: new `ItemKind` wire string; frozen-order invariant (appended, not reordered);
  unknown-kind rejection on older dispatch.
- `adb-bridge`: `setNotificationsEnabled` builds the exact expected argv and folds exit codes to
  `OpResult` (pattern: `LocalAdbBridgeTest.kt:263-277`); `NOTIFICATION_PARITY` probe added to the
  capability-set assertion (`LocalAdbBridgeTest.kt:412-425`); `ShellArgs` rejects a metacharacter
  package name (`ShellArgsTest.kt`).
- `providers/notifications`: export degrades to empty on denial; apply skips missing app/channel,
  drops invalid package names, rejects unknown importance, self-skips on bridge-unavailable; a
  failed record never aborts the batch (`ApplyProviderRegistryTest` model).
- `app-recv`: registry wires the provider; deferred-ledger reconcile applies only newly-present
  apps and is idempotent.

CI gates to extend (`.github/workflows/build.yml`, `CLAUDE.md`): add
`:providers:testDebugUnitTest` notification cases to the existing provider job; the
`:adb-bridge:testDebugUnitTest` job already runs (privilege bridge gate) — the new typed op + probe
land there. **OSV-Scanner dependency-audit** (`dependency-audit.yml`) is unaffected — no new
third-party dep.

On-device verification (post-merge, `VERIFICATION-RUNBOOK.md`): master toggle flips and persists;
per-channel write (if shipped) persists across reboot; a disabled app stays disabled; the deferred
pass applies correctly after a real reinstall+launch. Record the GOS fingerprint with every run.

---

## 9. Open questions / risks / spike dependency

- **THE gating unknown:** does GOS A16 expose **any** shell verb that writes another package's
  per-channel block/importance state? If no → Phase 2 is impossible and the feature is master-only.
  Everything in §6 Phase 2 hangs on the Phase 0 spike (§3).
- **Master toggle = perm or appop?** Whether `cmd appops set POST_NOTIFICATIONS` and
  `pm grant POST_NOTIFICATIONS` are the same lever decides the §2 de-dup boundary against a future
  runtime-permission-parity provider. Resolve in the spike before claiming the master toggle here.
- **Export read privilege:** can the *sender* read all apps' channel state without shell uid, or
  does export also need the bridge? If export is privileged too, the sender flow changes (the
  bridge currently lives on the *receiver*, ADR-003) — a non-trivial architectural ripple.
- **Protocol bump cost:** adding an `ItemKind` is a frozen-enum/wire change (`Messages.kt:14-30`).
  Is the value worth a version bump now, or should it ride the next bump alongside Wi-Fi (#1) /
  allowlist-expansion (#3)? Batch the wire change if timelines allow.
- **Channel id stability:** apps may change channel ids across versions; a re-applied id that the
  new app version no longer uses is a silent no-op. Accepted; document in user-facing copy
  ("we restore what your apps still recognize").
- **GOS volatility:** `cmd notification` is version-sensitive and GOS-hardened
  (`ADR-001-privilege-feasibility.md:74-76` — verdicts are fingerprint-bound). Any verb we depend
  on must be re-verified on GOS version bumps, like every other Tier-1 op.

---

### Decision (ADR-005 to be authored from the spike)
- **Decision:** carry per-app notification *state* parity (master enabled; per-channel state if the
  spike proves a shell verb), as a privileged provider with a **deferred apply pass**; never create
  channels; append a frozen-enum `NOTIFICATIONS` `ItemKind` behind a protocol bump.
- **Drivers:** named Seedvault gap + broad annoyance; portage owns the privilege stack already;
  the inventory + settings provider shapes fit; avoid duplicating runtime-permission parity.
- **Alternatives considered:** full channel recreation (rejected — channel ownership makes it
  impossible); fold entirely into a runtime-permission provider (rejected — that covers only the
  A13+ master toggle, not per-channel or appop); decline (kept as the honest Phase-0 fallback).
- **Why chosen:** state re-application is the maximal honest scope reachable through `AdbBridge`.
- **Consequences:** a live-bridge apply pass; a persisted deferred ledger; a wire/protocol bump.
- **Follow-ups:** Phase-0 spike verdict; de-dup boundary with any permission provider; export-read
  privilege question; batch the protocol bump with #1/#3 if timelines align.
