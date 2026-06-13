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
state machine), `assembleDebug` (both APKs), and the app-send no-escalation assert.
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
  On-device verification of the full pair→connect→grant chain is STILL OPEN (ADR-003 §7).
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

STILL OPEN: dedicated verbatim-diff review of the vendored noise-java tree before
release; a dedicated source review of the pinned libadb-android 3.1.1 + spake2-android
dependency (never security-audited upstream — ADR-003 §5) before release. CLOSED since:
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
  local contacts visible in default Contacts view; camera releases promptly post-scan.
- The QR-encoded PSK String is a non-zeroizable accepted residual (THREAT_MODEL §1
  boundary), documented in `SenderViewModel`.

## Build

JDK 17, Android SDK (compileSdk from `gradle/libs.versions.toml`, currently 36 = GOS
Android 16). The `gradle-wrapper.jar` is intentionally not committed; bootstrap once:
`gradle wrapper --gradle-version 9.5.1`. minSdk 31 (Pixel 6+).
