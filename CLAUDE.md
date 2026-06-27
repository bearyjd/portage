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
  `shell()` call sites outside `:adb-bridge`/`:wizard` are review blockers. The in-app
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
