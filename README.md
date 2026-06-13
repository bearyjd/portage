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
./gradlew assembleDebug                  # build both APKs (needs the Android SDK)
```

Requirements: JDK 17, Android SDK with the `compileSdk` from `gradle/libs.versions.toml`.
Versions in the catalog are early-2026 baselines — verify/bump AGP, `compileSdk`, and the
Compose BOM against the **current** GrapheneOS Android version at build time.

## Status

**Tier 0 transfer is implemented end-to-end and on `main`.** Both apps build (`assembleDebug`
produces both debug APKs in CI), with unit + integration tests green on every push. What
works, phone-to-phone over the Noise/TCP channel:

- **`portage-send`** — permissions → pack → pairing QR (the trust anchor, `FLAG_SECURE`) →
  accept one receiver → stream the selected items with per-chunk AEAD + per-item SHA-256.
- **`portage-recv`** — scan QR → handshake → checklist built from the live manifest (absent
  kinds shown disabled) → stage, verify, apply each item → done summary with real counts.
- **All six Tier-0 providers**: contacts (vCard 3.0), calendar (ICS), call log, SMS export
  (apply is role-gated and **inert until the SMS-role mini-project lands**), app inventory
  (assisted-reinstall deep links), and the SAFE `Settings.System` allowlist slice.

Landed across PRs #5 (providers + receiver apply wiring), #6 (`portage-send`), #7 (receiver
live channel) — each through TDD plus independent code-review and security-review gates.

**Not yet done:** the two-phone on-device validation walk-through (the one DoD step that
needs real hardware), and on-device verification of the self-contained Tier-1 privilege
bridge (`:adb-bridge` pairing → connect → self-grant on a real GOS device — see ADR-003's
verify-first list). Live security follow-ups (noise-java verbatim-diff review, CI
dependency audit, libadb-android dependency review) are tracked in `CLAUDE.md`.

The design artifacts live in [`docs/prp/`](docs/prp/):

- [`portage-prp-prompt.md`](docs/prp/portage-prp-prompt.md) — execution brief
- [`ADR-001-privilege-feasibility.md`](docs/prp/ADR-001-privilege-feasibility.md) — Tier 1 go-no-go + verification procedure (grant architecture; originally verified via Shizuku)
- [`ADR-003-self-contained-privilege.md`](docs/prp/ADR-003-self-contained-privilege.md) — self-contained ADB bridge replacing Shizuku
- [`VERIFICATION-RUNBOOK.md`](docs/prp/VERIFICATION-RUNBOOK.md) — **pre-build** Tier-1 privilege *feasibility* probes (V1–V8 + results template)
- [`TRANSFER-RUNBOOK.md`](docs/prp/TRANSFER-RUNBOOK.md) — two-phone Tier-0 transfer acceptance test (the DoD gate)
- [`E2E-VERIFICATION-RUNBOOK.md`](docs/prp/E2E-VERIFICATION-RUNBOOK.md) — **post-build** two-phone end-to-end verification across the full current feature set (wizard, settings, wallpaper, sound, bluetooth, relay); superset of the Tier-0 transfer test
- [`PROTOCOL.md`](docs/prp/PROTOCOL.md) — pairing + transfer wire format (QR anchor, Noise XXpsk3)
- [`THREAT_MODEL.md`](docs/prp/THREAT_MODEL.md) — semi-trusted-LAN adversary, attack→defense table
- [`settings_allowlist.md`](docs/prp/settings_allowlist.md) — SAFE / RISKY / DEVICE_SPECIFIC key classification
- [`DEVILS_ADVOCATE.md`](docs/prp/DEVILS_ADVOCATE.md) — adversarial review of the plan

> Note: this project was briefly drafted under the working name `malle`; it has been
> renamed to `portage` (app IDs `cc.grepon.portage.*`) throughout the design docs.

## License

[AGPL-3.0](LICENSE). © Grepon Labs LLC. Brand: Entrevoix.
