# portage — agent working notes

Device-to-device parity transfer for GrapheneOS (settings, contacts, app set) over LAN.
No cloud. Two apps: `portage-send` (exporter), `portage-recv` (importer). AGPL-3.0,
Grepon Labs LLC / Entrevoix. Full design substrate lives in `docs/prp/`.

## Working cadence (follow this — it's the footing)

Every substantive change goes through a gate. Do NOT author and self-approve in one pass.

```
branch-per-feature  →  author  →  independent review  →  fix findings  →  merge to main
```

- **Branch** off `main` per feature/fix (`feat/…`, `fix/…`). Never commit feature/code work straight to `main`.
- **Independent review before merge**, in a separate lane from the author:
  - `code-reviewer` for any non-trivial change.
  - `security-reviewer` (additionally) for anything touching **crypto, the privilege
    boundary, permissions, or the wire protocol**. This is mandatory, not optional.
  - When the reviewer validates tests, the bar is "does the test pass for the *right*
    reason" — a green test that doesn't exercise the control is worse than none.
- **Open a PR**; CI must be green; address review findings (or track them explicitly in an
  ADR) before merge. Squash-merge, delete the branch.
- **Exception — direct-to-`main` is fine for** docs/config only: this file, `README.md`,
  `docs/prp/**`, `.github/**` typo fixes. Substantive doc changes that encode decisions
  (new ADRs, protocol changes) still get a glance.

CI gates on every push/PR: `:settings-catalog:test` (safety-critical allowlist invariant),
`:core-model:test` + `:core-transport:testDebugUnitTest` (Noise loopback + adversarial),
`:adb-bridge:testDebugUnitTest` + `:wizard:testDebugUnitTest` (privilege bridge + bootstrap
state machine), `:app-recv:testDegoogleDebugUnitTest` + `:app-send:testDegoogleDebugUnitTest`
+ `:app-recv:testPlayDebugUnitTest` + `:app-send:testPlayDebugUnitTest` (app logic, both
flavors), `assembleDebug` + `assembleRelease` (all variants: degoogle + play for both apps;
release exercises R8/resource-shrinking), the app-send no-escalation assert, and the play-recv
no-bridge assert (no `WRITE_SECURE_SETTINGS` / adbbridge / conscrypt / spake2 in the play flavor
APK) — both asserts run across the debug AND release variants. The tag-triggered `release.yml`
re-asserts the same boundary on the signed APKs before publishing.
See `.github/workflows/build.yml`.

## Established facts (don't re-litigate; verified on real hardware / in CI)

- **Privilege model = grant architecture** (ADR-001, verified on Pixel 9 Pro XL / GOS
  Android 16): the ONE-SHOT `pm grant WRITE_SECURE_SETTINGS` persists across reboot, then
  settings writes use the normal `Settings.*` API with no live bridge. `Settings.System`
  needs no privilege at all (Tier 0). Owner-profile only.
- **Privilege delivery = self-contained ADB bridge** (ADR-003; replaced Shizuku entirely).
  `:adb-bridge` pairs with this device's own Wireless Debugging service (libadb-android
  3.1.1, Apache-2.0 elected, via JitPack), self-connects to adbd over localhost TLS, and
  runs shell-uid ops. **`AdbBridge` is the only allowed entry point to privileged
  operations. No module other than `:adb-bridge` may speak the ADB wire protocol.** Raw
  `shell()` may be called ONLY from within `:adb-bridge` itself, enforced by CI (see "Known
  CI-gate rule" below), not just review — `:wizard` is a privileged consumer of `AdbBridge`
  but MUST go through its typed operations (`pair`/`connect`/`probeCapabilities`/
  `disconnect`) only, never raw `shell()`. The in-app
  `PrivilegeWizard` (`:wizard` + `WizardScreen` in app-recv) owns the bootstrap flow and
  MUST disconnect right after the capability probe — never hold shell uid open.
  LADB (tytydraco/LADB) is acknowledged prior art for the *architecture* (it bundles the
  adb binary; no code was derived from it — see ADR-003 §3 for the license facts).
  On-device verification: the pair→connect→probe→self-grant chain SUCCEEDED in a dev run
  (GOS A16, 2026-06-12; ADR-003 §7.1–7.4 / §8). Remaining release gates: full reboot-recovery
  walk (§7.5), silent-install session verdict on GOS (§7.6, tied to #86), 16 KB native-lib
  alignment (§7.7), and the formal E2E runbook §F sign-off.
- **Transport crypto = `NoisePSK_XX`** via vendored noise-java (ADR-002). No audited JVM
  lib does modern `pskN`; legacy PSK placement is security-sufficient (PSK-gated mutual
  auth + ephemeral FS). Crypto stays behind `SecureChannel` so it's swappable.
- **Settings safety boundary:** the receiver applies a key only if it's in the compiled
  allowlist (`settings-catalog`), SAFE-by-default, every value validated. The guardrail
  test enforces "no non-DEVICE_SPECIFIC key is unvalidated" — keep it green.
- **Scope discipline:** Seedvault owns app *data*; portage owns settings/inventory/parity.
  Never add an item kind or feature that implies app-data transfer (no `seedvault.blob`).

## Open security follow-ups (build WITH the TCP listener, not after)

CLOSED (verified by review + tests in PRs #5-#7): PSK single-use consumption, 10s
handshake timeout, `u16` wire-read frame cap, `payload.wipe()` in both accept and
connect paths, receiver item-stream limits (per-item 64 MiB cap, size/kind/hash
agreement with the manifest, item-count cap).

CLOSED (ADR-004; independently re-verified 2026-06-15): the verbatim-diff review of the
vendored noise-java tree (FAITHFUL-AND-SAFE — 29/29 `.java` byte-exact vs upstream
`49377b6`, LICENSE byte-identical) and the source review of the pinned libadb-android
3.1.1 + spake2-android dependency (CLEAR-FOR-RELEASE against pinned `c849886e` / `7615ddd6`
/ `0d15933e` — loopback-only, no egress, no backdoor, no dynamic load; one accepted LOW
residual: the SPAKE2 ephemeral scalar uses unseeded libc `rand()` at `spake2.c:939`, benign
for portage's loopback + PAKE + one-shot model). Both ADR-003 §5 supply-chain blockers
cleared; the portage-side ADB identity key-gen uses a real CSPRNG (`AdbKeyStore` default
`SecureRandom()`, RSA-2048). CLOSED since:
the CI dependency-audit build gate (OSV-Scanner) — `dependency-audit.yml` resolves the
real shipped transitive graph (CI-only init-script locking → `gradle.lockfile` per APK
module → OSV-Scanner) and FAILS the build on a known advisory; weekly schedule catches
CVEs newly disclosed against unchanged deps. Accepted/triaged advisories live in
`osv-scanner.toml` with justification. (`.github/dependabot.yml` remains the
update-PR/alert layer, not a gate.) ALSO CLOSED: port-probe TOCTOU — the sender
probe-and-releases, then `acceptAsSender` rebinds with `SO_REUSEADDR` so the race is
benign (`SenderViewModel` ~L186).

## Post-Tier-0 follow-ups

- **SMS restore LANDED (PR #21), Tier 0.** portage takes the default-SMS role
  TRANSIENTLY (acquire → write → relinquish in a `finally`), and `SmsApplyProvider`
  independently hard-gates on `isSelfDefault()` so it writes nothing outside the role.
  Config-change-hang fixed via one process-scoped coordinator (`SmsRoleCoordinatorHolder`);
  a 120s `InteractiveGrant` timeout means a never-answered dialog can't hang. Process-death
  safety net = persistent `SmsRoleLedger` + launch/`onResume` reconcile keyed on real
  `isSelfDefault()` → an app-wide in-app "restore my texting app" banner (chosen over a
  notification: POST_NOTIFICATIONS is denied-by-default on GOS). STILL HARDWARE-VERIFY:
  role grant→write→relinquish on a Pixel/GOS device; whether `READ_SMS` is actually needed
  for role eligibility (drop it if not — recv only writes).
- **On-device VERIFY_FIRST**: WRITE_CALL_LOG-only inserts succeed on GOS; null-account
  local contacts visible in default Contacts view; camera releases promptly post-scan;
  a multi-split app (e.g. Signal) on the sender stages ALL splits (base + every
  `splitSourceDirs` entry) — confirm `splitSourceDirs` is populated from
  `getInstalledApplications(0)` on GOS A16 (ADR-006 Phase 1b open item); the Tier-0 APK
  install tap→commit→STATUS_PENDING_USER_ACTION→`ApkInstallResultReceiver`→system-confirm
  dialog→install chain fires end-to-end on a Pixel/GOS device (the broadcast→confirm hop is
  instrumented/hardware-only; only the receiver's status-routing helper is JVM-tested).
- The QR-encoded PSK String is a non-zeroizable accepted residual (THREAT_MODEL §1
  boundary), documented in `SenderViewModel`.

## Build

JDK 17, Android SDK (compileSdk from `gradle/libs.versions.toml`, currently 36 = GOS
Android 16). The Gradle wrapper is committed and its 9.5.1 distribution checksum is
pinned; always build with `./gradlew`. minSdk 31 (Pixel 6+).

Verified module-scoped commands (do NOT run full `./gradlew build`/`assemble` unless the task
needs it — prefer the smallest scoped command):
- Single-module unit tests: `./gradlew :<module>:test` (pure-JVM modules: `core-model`,
  `settings-catalog`) or `./gradlew :<module>:testDebugUnitTest` (Android-library/app modules:
  `core-transport`, `adb-bridge`, `wizard`, `providers`).
- App modules are flavor-scoped: `./gradlew :app-send:testDegoogleDebugUnitTest
  :app-send:testPlayDebugUnitTest` / same for `app-recv`. There is no flavor-agnostic
  `testDebugUnitTest` target on `app-send`/`app-recv`.
- Full CI-equivalent unit-test sweep (mirrors `.github/workflows/build.yml`):
  `./gradlew :settings-catalog:test :core-model:test :core-transport:testDebugUnitTest
  :adb-bridge:testDebugUnitTest :wizard:testDebugUnitTest :providers:testDebugUnitTest
  :app-recv:testDegoogleDebugUnitTest :app-recv:testPlayDebugUnitTest
  :app-send:testDegoogleDebugUnitTest :app-send:testPlayDebugUnitTest`.
- Device-only instrumentation test (`app-recv`'s `ProviderDeviceContractTest`, real
  `ContentResolver` writes): `scripts/device-contract.sh` — requires an attached/authorized `adb`
  device, installs the debug APK + test APK, temporarily takes the SMS role, and restores it via a
  trap on exit. Never run outside this script (it's destructive-but-self-cleaning, not idempotent
  standalone).
- No Robolectric is configured anywhere in the repo — `ContentResolver`-touching code is either
  unit-tested behind a hand-written `Store` seam (see "Provider authoring" below) with no real
  Android framework involved, or left to `ProviderDeviceContractTest` (hardware-only). There is no
  JVM-only partial-confidence path for real `ContentResolver` behavior.

## Provider authoring (undocumented-until-now convention — 12 existing providers follow this)

Every data domain (contacts, calendar, call log, SMS, MMS, settings, wallpaper, sound, bluetooth,
app-backup-relay, user files, app inventory/APK) follows the same unnamed three-part shape. When
adding a new domain, replicate it:

1. **`XyzStore` interface** — the only seam allowed to touch Android APIs directly
   (`ContentResolver`, `PackageManager`, etc.). Never call Android APIs from the export/apply
   provider itself.
2. **`AndroidXyzStore`** — the real implementation of that interface, isolated so tests can swap
   in an in-memory fake (see `MemoryContactsStore` in `LoopbackTransferSmokeTest.kt` for the
   pattern) instead of touching real content providers.
3. **`XyzExportProvider` / `XyzApplyProvider`** — pure Kotlin, operate only against the `Store`
   interface. Two conventions are copy-pasted across every existing provider and MUST be
   replicated (there is no shared base class enforcing them — `ExportProvider`/`ApplyProvider`
   are bare interfaces):
   - `available()` is always `runCatching { store.count() > 0 }.getOrDefault(false)` —
     permission-denied and genuinely-empty collapse to the same "unavailable" result by design.
   - `apply()` returns `ItemStatus.WRITE_ERROR` if and only if the input was non-empty but **zero**
     records made it into the store (permission denial mid-apply, corrupt payload, etc.); partial
     success is still `ItemStatus.OK`. See `ContactsProviders.kt` / `CallLogProviders.kt` for the
     reference shape.

**Wiring a new `ItemKind` end-to-end is a manual 4-point checklist — nothing fails at compile time
if you miss one, it silently degrades to `ItemStatus.UNKNOWN_KIND` at transfer time:**
1. Add the enum entry in `core-model/src/main/kotlin/com/ventouxlabs/portage/model/Manifest.kt`
   (`ItemKind`), append-only, with a `wire` string and `Tier`.
2. Register the export instance in the `listOf(...)` in
   `app-send/src/main/kotlin/com/ventouxlabs/portage/send/MainActivity.kt`.
3. Register the apply instance in the `ApplyProviderRegistry(listOf(...))` in
   `app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/MainActivity.kt`.
4. Add any new `uses-permission` to `app-send/src/main/AndroidManifest.xml`; if the domain is
   settings-shaped, add the corresponding entries to `settings-catalog`'s `SettingsAllowlist`
   (cross-referenced against `docs/prp/settings_allowlist.md`).

Flavor gating (degoogle vs play) for privilege-dependent features is expressed by Gradle
source-set, not code branches: a `providers/`-level seam interface (e.g. `ApkSilentInstaller`,
`TierOneGrant`, `RuntimePermissionGranter`) gets a real implementation under
`app-recv/src/degoogle/...` and a no-op/unavailable stub under `app-recv/src/play/...`. Never add
a Tier-1/privileged code path directly under `src/main` — it will leak into the play flavor and
break the CI no-bridge assert.

See `.agent_native/agent_roadmap.md` for the prioritized backlog of gaps in this area (a
completeness test for the 4-point checklist above, a canonical multi-kind end-to-end test harness,
and a recorded-session replay fixture format for reproducing bug reports without hardware).

## Known CI-gate rule

`.github/workflows/build.yml`'s "raw `AdbBridge.shell()` stays inside the privilege modules" grep
step scans `app-recv app-send providers core-model core-transport settings-catalog wizard` —
`:wizard` IS included in the scanned (forbidden) set, deliberately: raw `.shell()` is
`:adb-bridge`-only everywhere, including `:wizard`, which must use only `AdbBridge`'s typed ops
(`pair`/`connect`/`probeCapabilities`/`disconnect`). This file and `AdbBridge.kt`'s comments agree
with the CI grep's scope — a raw `.shell()` call added anywhere outside `:adb-bridge` (`:wizard`
included) fails CI, not just review. (Formerly a doc/CI mismatch — resolved per
`.agent_native/agent_roadmap.md` item #2; keep all three in sync if this rule ever changes.)
