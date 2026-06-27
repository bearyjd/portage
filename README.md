<p align="center">
  <img src="docs/brand/banner.png" alt="portage — carry your phone over" width="880">
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: AGPL-3.0-or-later" src="https://img.shields.io/badge/license-AGPL--3.0-0E3A45"></a>
  <img alt="GrapheneOS / Android 12+" src="https://img.shields.io/badge/GrapheneOS-Android%2012%2B-5FD0C6">
  <img alt="Kotlin 2.4 (JDK 17)" src="https://img.shields.io/badge/Kotlin-2.4%20%C2%B7%20JDK%2017-E9A23B">
  <img alt="LAN only — no cloud" src="https://img.shields.io/badge/transport-LAN%20only%20%C2%B7%20no%20cloud-0E3A45">
</p>

# portage

**Device-to-device parity transfer for GrapheneOS.** Make a new phone feel like the old
one — settings, contacts, calendar, call log, SMS, and your app set — over your LAN.
No cloud, no account, no relay.

> *portage* (Fr.) — carrying your belongings over. Also what you do when you *port* to a
> new device.

Two artifacts:

- **`portage-send`** — the old phone (exporter)
- **`portage-recv`** — the new phone (importer)

## Division of labor with Seedvault

**Seedvault moves your app data; `portage` moves the settings-and-parity layer Seedvault
misses, directly phone-to-phone.** They are complementary, not competitors:

| | Seedvault | portage |
|---|---|---|
| App internal data, databases, login sessions | ✅ (privileged system app) | ❌ (deferred to Seedvault) |
| Contacts / calendar / call log / SMS | partial | ✅ |
| Curated, allow-listed system settings | partial | ✅ |
| App **inventory** + assisted reinstall | ❌ | ✅ |
| Direct phone-to-phone over LAN | ❌ (needs a backup target) | ✅ |

## Capability tiers

- **Tier 0 — no special privilege (always works):** contacts (vCard), calendar (ICS),
  call log, SMS/MMS (via temporary default-SMS-app handoff), app inventory + assisted
  reinstall, and the `Settings.System` slice of settings sync (font scale, screen
  timeout, auto-rotate, haptics, time format) via user-granted "Modify system settings."
- **Tier 1 — one-time Wireless Debugging setup (graceful-degrade):** allow-listed
  `Settings.Secure` / `Settings.Global` sync, batched app reinstall, and opt-in
  runtime-permission parity. portage owns the whole privilege stack itself — enable
  Developer options, enable Wireless debugging, then (in split screen, so the pairing dialog
  stays visible) type the 6-digit code and port into the in-app wizard; no companion app, no
  PC. See
  `docs/prp/ADR-003-self-contained-privilege.md` (architecture) and
  `docs/prp/ADR-001-privilege-feasibility.md` (the underlying grant model + on-device
  verification plan).

## Building

This is a Gradle + Kotlin multi-module Android project (module layout in the brief, §4).

**One-time wrapper bootstrap.** The `gradle-wrapper.jar` is intentionally not committed;
generate the wrapper once on a machine with Gradle 9.5.1+ installed:

```sh
gradle wrapper --gradle-version 9.5.1   # writes gradlew, gradlew.bat, and the wrapper jar
```

Then the usual:

```sh
./gradlew :settings-catalog:test        # pure-JVM safety-critical allowlist guardrails
./gradlew assembleDebug                  # build all debug variants (degoogle + play) for both apps
```

Requirements: JDK 17, Android SDK with the `compileSdk` from `gradle/libs.versions.toml`.
Versions in the catalog are early-2026 baselines — verify/bump AGP, `compileSdk`, and the
Compose BOM against the **current** GrapheneOS Android version at build time.

## Status

**Tier 0 transfer and the APK-transfer keystone are implemented end-to-end and on `main`.**
Both apps build (`assembleDebug` produces both debug APKs in CI), with unit + integration
tests green on every push. What works, phone-to-phone over the Noise/TCP channel:

- **`portage-send`** — permissions → pack → pairing QR (the trust anchor, `FLAG_SECURE`) →
  accept one receiver → stream the selected items with per-chunk AEAD + per-item SHA-256.
- **`portage-recv`** — scan QR → handshake → checklist built from the live manifest (absent
  kinds shown disabled) → stage, verify, apply each item → done summary with real counts.
- **All six Tier-0 providers**: contacts (vCard 3.0), calendar (ICS), call log, SMS
  (role-gated default-SMS handoff), app inventory, and the SAFE `Settings.System` allowlist
  slice.
- **APK transfer (ADR-006, Phases 1–4, PRs #66–#69)**: streamed multi-file container codec,
  sender UI with running size total, receiver free-space gate, and the apply provider with
  Tier-0 `PackageInstaller` confirm-install fallback. Split-aware (base + config splits
  streamed and staged). Settings sync (Tier-1) and APK install are the two privileged item
  kinds.

**Hardware-verified on real GOS devices (as of `main` tip):**

- **Tier-0 APK install** — Pixel 9 Pro Fold (`comet`) → Pixel 10 Pro Fold (`rango`),
  GrapheneOS Android 16. Single-APK (Molly) and multi-split (Termux: base + arm64-v8a + en
  + es + xxhdpi) both staged and installed via the carried Tier-0 chain; apps launch.
- **Silent `exec:` install (Tier-1 bridge, PR #70)** — APK bytes stdin-streamed into
  `pm install-write -S`, the commit exit code is the verdict; WD-off → Tier-0 fallback (no hang). Hang caused by calling `connect()`
  when Wireless Debugging is off was found and fixed (PR #70 + gated on `adb_wifi_enabled`).
- **Tier-1 privilege bootstrap (ADR-003 §7, now CLOSED)** — pair → connect → probe →
  `WRITE_SECURE_SETTINGS` grant works on metal; wizard disconnects after the capability
  probe, no held shell uid.
- **AC-15 density-reconcile (PR #73)** — a real "Missing split" install rejection was found
  on a Pixel 8 Pro (`husky`) using a forced-density override and fixed: reconcile now keeps
  a fallback density split instead of dropping to zero when no exact bucket match exists.

**Genuinely still open:**

- **Phase 5 — runtime-permission parity**: separate GO/NO-GO, not built yet (needs the
  first production `grantRuntimePermission()` call site).
- **AC-15 ABI leg**: structurally unreachable on the arm64-only Pixel/GOS fleet; closable
  only on an x86_64 emulator.

Design artifacts live in [`docs/prp/`](docs/prp/) and [`docs/`](docs/):

- [`portage-prp-prompt.md`](docs/prp/portage-prp-prompt.md) — execution brief
- [`ADR-001-privilege-feasibility.md`](docs/prp/ADR-001-privilege-feasibility.md) — Tier 1 go-no-go + verification procedure (grant architecture)
- [`ADR-003-self-contained-privilege.md`](docs/prp/ADR-003-self-contained-privilege.md) — self-contained ADB bridge replacing Shizuku
- [`ADR-006-apk-transfer-and-permission-parity.md`](docs/prp/ADR-006-apk-transfer-and-permission-parity.md) — APK transfer keystone: wire format, reconcile policy, privilege seams, phase plan
- [`P6-apk-hardware-runbook.md`](docs/prp/P6-apk-hardware-runbook.md) — on-device verification runbook + silent-install design + hardware evidence
- [`VERIFICATION-RUNBOOK.md`](docs/prp/VERIFICATION-RUNBOOK.md) — **pre-build** Tier-1 privilege *feasibility* probes (V1–V8 + results template)
- [`TRANSFER-RUNBOOK.md`](docs/prp/TRANSFER-RUNBOOK.md) — two-phone Tier-0 transfer acceptance test
- [`E2E-VERIFICATION-RUNBOOK.md`](docs/prp/E2E-VERIFICATION-RUNBOOK.md) — **post-build** two-phone end-to-end verification (wizard, settings, APK install, relay)
- [`PROTOCOL.md`](docs/prp/PROTOCOL.md) — pairing + transfer wire format (QR anchor, Noise XXpsk3)
- [`THREAT_MODEL.md`](docs/prp/THREAT_MODEL.md) — semi-trusted-LAN adversary, attack→defense table
- [`settings_allowlist.md`](docs/prp/settings_allowlist.md) — SAFE / RISKY / DEVICE_SPECIFIC key classification
- [`DEVILS_ADVOCATE.md`](docs/prp/DEVILS_ADVOCATE.md) — adversarial review of the plan
- [`CODEMAPS/`](docs/CODEMAPS/) — architecture maps (module graph, data-flow, privilege boundary)
- [`CONTRIBUTING.md`](docs/CONTRIBUTING.md) — contribution guide (branching, review gates, CI)
- [`RUNBOOK.md`](docs/RUNBOOK.md) — operational runbook (build, flash, device setup, common failures)

> Note: this project was briefly drafted under the working name `malle`; it has been
> renamed to `portage` (app IDs `com.ventouxlabs.portage.*`) throughout the design docs.

## License

[AGPL-3.0](LICENSE). © Grepon Labs LLC. Brand: Entrevoix.
