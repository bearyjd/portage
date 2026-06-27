# Transfer validation runbook — two-phone Tier-0 acceptance

The Definition-of-Done gate for the Tier-0 transfer (PRs #5–#7). Everything below CI is
green, but CI never runs the Android ContentResolver providers, the Compose UI, or the
camera — those only exist on hardware. Run this on **two real, current-release GrapheneOS
Pixels** (minSdk 31 = Pixel 6+) on the **same Wi-Fi**. Capture exact results in the table.

> A verdict is only valid for the build fingerprint you record. Re-run on GOS bumps.

## Setup

- **Network:** both phones on the same Wi-Fi, **not** a guest / AP-isolated network —
  client isolation blocks the LAN socket and the handshake will simply time out (that is
  the *correct* fail-closed behavior, but it is not a transfer).
- **Get the APKs** (no local Android SDK needed — pull the CI artifact):
  ```sh
  RID=$(gh run list --branch main --workflow build.yml --status success --limit 1 \
        --json databaseId --jq '.[0].databaseId')
  gh run download "$RID" -n portage-debug-apks -D /tmp/portage-apks
  # old phone (exporter):
  adb -s <OLD> install -r /tmp/portage-apks/app-send/build/outputs/apk/degoogle/debug/app-send-degoogle-debug.apk
  # new phone (importer):
  adb -s <NEW> install -r /tmp/portage-apks/app-recv/build/outputs/apk/degoogle/debug/app-recv-degoogle-debug.apk
  ```
- Record each device: `adb -s <id> shell getprop ro.build.fingerprint`.

## Happy path (mirrors the brief's Definition of Done)

**T1 — send shows the QR.** Open `portage·send` → grant the read permissions when asked
(Contacts, Calendar, Call log, SMS) → **Start transfer**. A QR appears. The screen is
`FLAG_SECURE` for the whole session, so screenshots/recents/cast are blank — expected.

**T2 — recv scans, checklist from the live manifest.** Open `portage·recv` → **Scan** →
grant Camera → point at the old phone's QR. Within ~2 s: handshake completes and the
checklist appears, **built from the sender's manifest** — Contacts / Calendar / Call
history pre-checked, SMS shown *unchecked* with the one-time-handoff note, and any kind
the old phone had nothing of shown greyed under **"Not on the old phone"**.

**T3 — Contacts cross the wire.** Leave the defaults, tap **Bring it over** → per-item
progress → done. On the **new** phone open Contacts: the old phone's contacts are present.
Spot-check one with multiple numbers and one with punctuation/non-ASCII in the name.

**T4 — App inventory → assisted reinstall.** Re-run (or include App list) → the new phone
presents a reinstall checklist firing **one install intent per app** (F-Droid / Aurora /
Play per source). Confirm there is **no silent install** — every app is one user tap.

**T5 — Settings (Settings.System slice).** For settings to *apply*, grant `portage·recv`
**Modify system settings** (Settings → Apps → portage·recv → Modify system settings).
Re-run with Settings checked → font scale, screen-off timeout, auto-rotate, and 12/24h
format on the new phone match the old. Out-of-allowlist / device-specific keys are silently
skipped — that is the safety boundary working, not a failure.

**T6 — done summary.** The final screen shows real moved / skipped counts (not the item
count) on both phones.

## Robustness (the devils-advocate answers, on hardware)

- **E1 — permission denial degrades.** On send, deny one read permission → that domain
  drops out of the checklist on the new phone. No crash.
- **E2 — connection drop mid-transfer.** Start a transfer, then turn off the old phone's
  Wi-Fi mid-stream → recv shows an **error state**, never an indefinite spinner.
- **E3 — bad / expired QR.** Wait >120 s, then scan the stale QR → user-visible
  "invalid/expired" message, not a crash. Also paste a garbage `portage1:…` string into
  recv's paste-fallback → same visible error.
- **E4 — multiple interfaces / VPN.** With a VPN active on the old phone, confirm the QR
  still pairs — the address hints must use the **Wi-Fi LAN IP**, not the VPN tun or
  loopback. (A successful connect is the proof it chose right.)
- **E5 — SMS is gated, not broken.** Select SMS → it reports **SKIPPED** (the default-SMS
  role components aren't declared yet). Expected; see "Known-gated."

## Carried VERIFY_FIRST items (fold into the steps above)

- **Call log (T3 sibling):** after Call history transfers, open the new phone's dialer →
  confirm the imported calls show. Validates `WRITE_CALL_LOG`-only insert succeeds on GOS.
- **Contact visibility (T3):** confirm imported contacts appear in the **default** Contacts
  view (they are null-account device-local contacts).
- **Camera release (T2 teardown):** after a scan, navigate away from recv → confirm the
  camera-in-use indicator clears promptly.

## Known-gated — do NOT file as bugs

- **SMS restore** — apply is inert by design until the default-SMS-role mini-project lands
  (its own PR + security review).
- **Tier 1 settings** (`Settings.Secure` / `Settings.Global` via Shizuku) — not
  implemented; only the `Settings.System` slice applies. `privileged` is still a stub.

## Results (fingerprint: __________________________)

| Step | Verdict | Evidence / notes |
|---|---|---|
| install (both) | | |
| T1 send QR | | |
| T2 recv checklist from live manifest | | |
| T3 Contacts arrive (+ visible in default view) | | |
| T3 Call log arrives | | |
| T4 App inventory → install intents | | |
| T5 Settings.System applied | | |
| T6 done counts correct | | |
| E1 permission denial degrades | | |
| E2 drop mid-transfer → error | | |
| E3 bad/expired QR → visible error | | |
| E4 LAN IP not VPN/loopback | | |
| E5 SMS gated → SKIPPED | | |
| camera releases post-scan | | |

## Pre-run smoke (already done 2026-06-11, fingerprint `…2026060600`, Android 16/SDK36)

Driven over `adb` on one Pixel (Fold, "comet"): both APKs **install** clean with `-g`;
both apps **launch** to a Resumed `MainActivity` with **zero FATAL/crash** in logcat;
**`FLAG_SECURE` confirmed** on send (screencap excluded + uiautomator content blanked).
The interactive transfer (T1–T6) still requires two phones with the screens unlocked —
the secure keyguard and FLAG_SECURE both (correctly) block driving it headless.
