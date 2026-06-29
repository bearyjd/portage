# portage Runbook

Operational runbook for **building, installing, and troubleshooting** portage. There is no
server to deploy — "deployment" is building the two debug APKs and `adb install`-ing them onto
GrapheneOS devices. `<!-- AUTO-GENERATED -->` sections derive from `.github/workflows/` and the
build config.

For on-device *verification* procedures (two-phone acceptance, privilege bootstrap, APK
hardware) see the detailed runbooks under [`docs/prp/`](prp/) — linked at the bottom.
For an actual old-Android → new-GrapheneOS migration, start with
[`MIGRATION-GUIDE.md`](MIGRATION-GUIDE.md); it assigns each category to Portage, Seedvault,
an app-native export, file copy, or manual setup.

## Build & install ("deploy")

```sh
# 1. Build both debug APKs with the committed, checksum-pinned wrapper.
./gradlew assembleDebug --no-daemon
#    Builds all debug variants (degoogle + play) for both apps.
#    Degoogle debug APKs (full Tier-1, use these for development/testing):
#    → app-send/build/outputs/apk/degoogle/debug/app-send-degoogle-debug.apk
#    → app-recv/build/outputs/apk/degoogle/debug/app-recv-degoogle-debug.apk

# 2. Install onto devices (use -s <serial> when more than one is attached).
adb -s <OLD_PHONE> install -r app-send/build/outputs/apk/degoogle/debug/app-send-degoogle-debug.apk
adb -s <NEW_PHONE> install -r app-recv/build/outputs/apk/degoogle/debug/app-recv-degoogle-debug.apk
```
Both devices must be on the **same LAN/Wi-Fi** (the transfer is peer TCP, not USB). The QR
hand-off (sender shows, receiver scans with its camera) is a manual step.

## Health checks (CI gate)

<!-- AUTO-GENERATED: health checks (source: .github/workflows/build.yml, dependency-audit.yml) -->
| Check | Green means |
|-------|-------------|
| `jvm-tests` (`:settings-catalog:test`) | Settings SAFE-allowlist invariant holds |
| `android-build` unit tests | core-model/transport/adb-bridge/wizard/providers/app-* logic passes (degoogle + play variants for app modules) |
| `android-build` assemble | all debug and minified release variants build (degoogle + play for both apps) |
| no-escalation assert | debug and release `app-send` / Play receiver artifacts stay bridge-free; degoogle receiver retains it |
| raw-shell assert | no `AdbBridge.shell(` outside `:adb-bridge` |
| `osv-scan` | no known advisory in the shipped dependency graph |
<!-- END AUTO-GENERATED -->

Run the same gate locally before pushing (full command in [`CONTRIBUTING.md`](CONTRIBUTING.md)).

## Common issues & fixes

| Symptom | Cause | Fix |
|---------|-------|-----|
| `UnsupportedClassVersionError` (class v65) | a JDK-21 `java` compiled some module | set `JAVA_HOME` to a JDK 17; `./gradlew :core-model:clean :settings-catalog:clean` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on install | existing install signed with a different debug keystore | `adb uninstall <pkg>` then install fresh |
| Gradle daemon hangs/flakes | env-specific daemon issue | run with `--no-daemon` |
| `gradlew: not found` | incomplete source checkout | restore the committed wrapper files or clone the repository again |
| Tier-1 apply "hangs on APPLYING" with Wireless Debugging off | libadb `connect()` mDNS path ignores interruption | already gated: `connect()` checks `adb_wifi_enabled` → falls back to Tier-0 (PR #70). If reproduced, confirm WD state |
| Foldable screencap is black | device auto-locked / wrong display | wake first; `screencap -d <physical-display-id>` (outer display) |
| APK install rejected `Missing split for <pkg>` | a required density/abi split absent | reconcile keeps a fallback density split (PR #73); a missing *required ABI* is an honest per-app skip → "install from store" |

## Tier-1 privilege bootstrap (operator steps)

On the receiver, Tier-1 (allow-listed `Settings.Secure/Global`, silent batched APK install)
needs a one-time in-app wizard: enable Developer options → enable Wireless debugging → in
split-screen (so the pairing dialog stays visible) enter the 6-digit code + port into the
portage-recv wizard. portage owns the whole stack — no PC, no companion app. The wizard
disconnects right after the capability probe (never holds shell uid). Details:
[`docs/prp/ADR-003-self-contained-privilege.md`](prp/ADR-003-self-contained-privilege.md).

## Rollback & escalation

- **Rollback:** revert the offending squash-merge on `main` (`git revert <sha>`), or re-merge a
  fixed branch. No live service / migration state to unwind.
- **Escalation:** there is no production service or on-call. Security-relevant regressions are
  caught by the CI gates above; open-question / follow-up tracking lives in `CLAUDE.md` and the
  relevant ADR. A security issue → STOP, run `security-reviewer`, fix before continuing
  (`CLAUDE.md` security protocol).

## On-device verification runbooks (detailed)

- [`MIGRATION-GUIDE.md`](MIGRATION-GUIDE.md) — user-facing two-phone workflow and capability matrix
- [`TRANSFER-RUNBOOK.md`](prp/TRANSFER-RUNBOOK.md) — two-phone Tier-0 transfer acceptance (DoD gate)
- [`E2E-VERIFICATION-RUNBOOK.md`](prp/E2E-VERIFICATION-RUNBOOK.md) — full-feature two-phone E2E
- [`VERIFICATION-RUNBOOK.md`](prp/VERIFICATION-RUNBOOK.md) — pre-build Tier-1 feasibility probes
- [`P6-apk-hardware-runbook.md`](prp/P6-apk-hardware-runbook.md) — APK install (Tier-0/silent/AC-15) hardware verification
