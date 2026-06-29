# E2E-VERIFICATION-RUNBOOK.md — `portage` v2

A hands-on, **two-device end-to-end** verification checklist for the shipped portage apps.

> **Companion docs.** `VERIFICATION-RUNBOOK.md` is the *pre-build* privilege **feasibility**
> gate (ADR-001 §3, the V1–V8 probes that decided the grant architecture). **This** file is the
> *post-build* gate: it verifies the two shipped apps actually move each category between two real
> phones. Different purpose, different lifecycle — keep both.

Everything here is **on-device** work CI cannot cover: CI gates the JVM unit tests +
`assembleDebug` + the no-escalation assert (see `CLAUDE.md`), but the real GOS behaviours —
privilege bootstrap, settings writes, wallpaper `setStream`, the SMS role dance, the relay
handoff — only prove out on hardware.

Work top to bottom. Each step has a **Do**, an **Expect** (the precise observable that proves
it worked), and **Notes** (documented caveats, so a surprise isn't mistaken for a bug). Tick the
sign-off table at the end.

> Scope note: portage moves **settings / inventory / parity**, never app *data* (Seedvault owns
> that). The app-backup relay (§C9) is a **courier** for a user-exported, app-encrypted blob —
> portage never opens it. User-file transfer carries only files explicitly selected through
> Android's file picker; it is not a shared-storage crawler. Keep those boundaries in mind while
> verifying.
>
> For the end-user migration sequence and the full Portage / Seedvault / app-native / manual
> capability matrix, see [`../MIGRATION-GUIDE.md`](../MIGRATION-GUIDE.md). In particular, do not
> restore calendar, call-history, or SMS data through two tools and assume cross-tool deduplication.

---

## 0. Devices and prerequisites

| Role | Device | OS |
|------|--------|----|
| **Receiver** (importer, `portage-recv`) | GrapheneOS device — dev unit: Pixel 10 Pro Fold "rango" | GOS Android 16 (SDK 36) |
| **Sender** (exporter, `portage-send`) | Any Android **12+** (minSdk 31) — e.g. Pixel 9 Pro XL "comet" | Android 12+ |

- The **receiver** is the only side that runs the privilege wizard / ADB bridge. The **sender
  has no bridge** and is CI-asserted escalation-free — there's nothing to set up on the sender
  beyond granting it the read permissions for what you want to export.
- Dev convenience (maintainer driving rango over USB): `adb` = `/home/user/bin/adb`,
  serial `57211FDCG0023C`. USB only drives the test; portage itself uses **Wi-Fi**.
- Both phones on the **same Wi-Fi / LAN**. Both apps need the **GOS Network permission**
  (`INTERNET`) — if denied, sockets fail **silently** (see §G4); grant it first.
- Install the latest debug APKs of both apps. A **receiver REINSTALL wipes the
  `WRITE_SECURE_SETTINGS` grant and the pairing key** (§A) — re-run the wizard after any recv
  reinstall before testing Tier-1 items.
- `FLAG_SECURE` is held for the whole sender session — screenshots / screen-record of the sender
  will be blank. That is expected, not a capture bug.

**Pre-flight checklist**

- [ ] Both phones on the same LAN; Network permission granted to both apps.
- [ ] Sender: grant the runtime permissions for the categories under test (Contacts, Calendar,
      Call log, SMS, Bluetooth-connect). A denied permission makes that item **self-omit** (absent
      from the manifest) — correct behaviour, not a failure.
- [ ] Receiver: wizard run to **"Advanced transfer ready"** if testing any Tier-1 item
      (Settings.Secure/Global, `device_name`). Tier-0 items work without it.
- [ ] On a freshly rebooted receiver: re-enable Wireless Debugging (§A reboot path) before Tier-1.

---

## A. Privilege wizard bootstrap (receiver only)

Reference: ADR-003 §7–8, ADR-001 §1–2, `PrivilegeWizard.kt`, `WizardScreen.kt`.

**A1 — Reach the pairing step**
- **Do:** Open `portage-recv` → start Advanced Transfer Setup. Follow: enable Developer options
  (About phone → tap Build number ×7), then Developer options → Wireless debugging → ON.
- **Expect:** Wizard advances Idle → Checking → EnableDevOptions → EnableWirelessDebug →
  EnterPairingCode as each prerequisite is met (it re-checks on return from Settings).
- **Notes:** The wizard **gates `connect()` behind the Wireless-Debugging toggle on purpose** —
  with the toggle off there's no endpoint and libadb's mDNS wait can't be interrupted, so a
  premature connect would hang on "Checking" (found on rango, GOS A16). Don't skip the toggle.

**A2 — Pair**
- **Do:** Put portage and Settings in **split screen**. In Settings tap "Pair device with pairing
  code" and keep that dialog visible. Enter the 6-digit code and the **port shown in that dialog**
  into portage; tap Pair.
- **Expect:** Pairing → Probing → **Ready**, with the capability summary showing **"AUTOMATIC"**
  for *Secure settings* and *App permission parity* → headline **"Advanced transfer ready."**
- **Notes:**
  - The pairing **port regenerates every time the dialog opens** (observed 42507 → 34295 → 40431
    on rango). If you reopen/close it, read the *new* port. A stale port → "the pairing dialog
    closed before pairing finished" (ENDPOINT_DOWN) → re-enter a fresh code **and** port.
  - mDNS may auto-fill the port; it never blocks — manual entry is the reliable path and Pair
    enables on a valid code+port regardless of the search.
  - Security invariant to confirm by behaviour: after the probe the bridge **disconnects
    immediately** — shell uid is never held open in the background. No persistent "debugging
    connected" state should linger after "Ready".
  - **Field behaviour (TextFieldValue migration):** type the code and port quickly — *especially
    while the "LOOKING FOR THE PAIRING SERVICE…" search is running* — and confirm the caret does
    not jump to the end and no keystrokes are dropped; editing a digit mid-field keeps the caret in
    place. (Compose text-field behaviour is not exercised by the JVM CI gate, so verify it here.)

**A3 — Grant persists across reboot (no re-pair)**
- **Do:** Reboot the receiver. Re-open portage; when prompted, re-enable Wireless Debugging.
- **Expect:** The wizard reconnects with the **persisted pairing key** (no 6-digit re-pair) →
  probes → Ready. A Tier-1 write (e.g. `device_name`, §C5) still succeeds after reboot.
- **Notes:** Only the Wireless-Debugging **toggle** resets on reboot; the `pm grant
  WRITE_SECURE_SETTINGS` and the pairing key persist. GOS auto-reboot (≈18 h idle-locked) means a
  device prepared overnight needs the toggle flipped again in the morning.

**A4 — Reinstall wipes the grant (negative check)**
- **Do:** Reinstall `portage-recv`. Try a Tier-1 item without re-running the wizard.
- **Expect:** Tier-1 keys **self-skip** with a "needs the secure-settings grant" hint; Tier-0 still
  works. Re-running the full wizard restores Tier-1.

---

## B. Establish the transfer link (handshake sanity)

Reference: PROTOCOL.md §1–5, ADR-002, `NoiseSecureChannel.kt`, `PskRegistry.kt`.

**B1 — Pair the two phones for a transfer**
- **Do:** Sender: "Transfer to new phone" → grant export permissions → it packs and shows a
  **QR**. Receiver: scan the QR.
- **Expect:** Sender QR screen flips to **Linked** the instant the handshake completes (before any
  data); receiver shows the transfer screen with items beginning to flow. End state: sender
  **Done (sent N, failed M)**.
- **Notes:** `NoisePSK_XX` over LAN, PSK carried in the QR, **single-use** (a second scan of the
  same QR is rejected), 120 s QR TTL, 10 s handshake deadline.

**B2 — Fail-closed (do once)**
- **Do:** Let the QR expire (>120 s) or scan a stale QR, then try to connect.
- **Expect:** Handshake fails → connection closes → sender shows a failure state. **No payload
  bytes flow on a failed handshake.** A network adversary without the QR cannot complete it.

---

## C. Per-feature verification

Run a transfer (§B) with the relevant items selected, then check each on the receiver.

**C1 — Contacts / Calendar / Call log**
- **Do:** Export with Contacts, Calendar, Call log selected (sender must have the read perms).
- **Expect:** Contacts appear in Contacts; events in Calendar; calls in Phone → recents. The Done
  screen reports applied/skipped counts per item.
- **Notes:** A denied read permission on the sender → that item is **absent** from the manifest (no
  error). Malformed records are counted as skipped, not fatal.

**C2 — SMS (transient default-SMS role)**
- **Do:** Export with SMS selected. On the receiver, when prompted, **grant portage the
  default-SMS role**.
- **Expect:** Messages appear in the messaging app. portage **acquires the SMS role → writes →
  relinquishes**, then surfaces a banner / system dialog to **return the role to your real texting
  app**.
- **Notes:**
  - This provider carries **SMS text rows**. MMS attachments/content and RCS state are outside the
    current payload and must not be counted as restored.
  - `SmsApplyProvider` independently hard-gates on `isSelfDefault()` — it writes **nothing** unless
    portage actually holds the role. Decline the role and SMS self-skips; the rest continues.
  - Process-death safety net: if portage is killed while holding the role, on next
    launch/`onResume` it reconciles against the real `isSelfDefault()` and shows the **"restore my
    texting app"** banner (chosen over a notification because POST_NOTIFICATIONS is denied-by-default
    on GOS). Verify by force-stopping mid-flow and relaunching.
  - A never-answered role dialog times out at 120 s rather than hanging.

**C3 — App inventory + reinstall**
- **Do:** Export App inventory. On the receiver, work the reinstall checklist.
- **Expect:** Receiver lists apps **not already installed**; each row opens its store (Play /
  F-Droid / `market://`) on tap. Already-installed apps are reported as such.
- **Notes:** Inventory entries are **data, not code** — portage never silently installs (Tier 0 is
  one user-confirmed `PackageInstaller` flow per app; the silent batch path is Tier 1 and degrades
  to per-app confirm if unavailable). Packages with malformed names are dropped.

**C4 — Settings parity (SAFE allowlist)**
- **Do:** On the sender, change a few SAFE settings, then export Settings. Spot-check on the
  receiver after apply.
  - **Tier-0 (no grant)** examples: `font_scale`, `screen_off_timeout`, `haptic_feedback_enabled`,
    `time_12_24` (Settings.System — needs the user "Modify system settings" special access).
  - **Tier-1 (needs WSS grant)** examples: `ui_night_mode` (dark mode), `window_animation_scale`.
- **Expect:** Receiver matches the sender for the keys you changed (dark mode flips, font scale
  changes, animation speed changes). Done screen reports applied/skipped.
- **Notes:**
  - The receiver applies a key **only if it's in the compiled allowlist**, SAFE-classified, and the
    value passes the per-key validator (range/enum/pattern). Namespace is **not** taken from the
    wire — only the key name, looked up locally.
  - **Negative check — DEVICE_SPECIFIC keys must NOT move:** confirm `screen_brightness`,
    `ringtone`, `adb_enabled`, and `enabled_accessibility_services` are **unchanged** on the
    receiver. These are deliberately not transferable.

**C5 — `device_name` (Global, SAFE, Tier-1)**
- **Do:** Set a distinctive device name on the sender; export Settings (wizard grant required).
- **Expect:** Receiver → Settings → About phone → Device name matches; the Bluetooth name follows.
- **Notes:** Needs the WSS grant (Tier-1). Validator bounds it to 1–256 chars, non-blank, no
  control chars / newlines (treated as hostile display input). Without the grant it self-skips.

**C6 — Wallpaper (home + lock)**
- **Do:** Set distinct home and lock wallpapers on the sender (real PNG/JPEG/WebP images); export
  Wallpaper.
- **Expect:** Receiver home **and** lock show the transferred images right after receive. If the
  sender's lock mirrors its home, only one wallpaper item is sent and home covers both.
- **Notes:** Apply gate (runs **before** any bitmap is allocated): magic-byte MIME allowlist
  (PNG/JPEG/WebP — declared format re-verified, not trusted), 32 MiB byte cap, and a bounds-only
  decode with a 64 MP ceiling (decompression-bomb guard). A non-image or a bomb is **SKIPPED**,
  batch continues. Live wallpapers can't be exported (no file bytes) → item absent.

**C7 — Sound selection (ringtone / notification / alarm)**
- **Do:** On the sender, set **built-in** ringtone/notification/alarm sounds (Phase 1 carries
  built-ins only); export Sound selection. Needs "Modify system settings" on the receiver.
- **Expect:** Receiver → Sound settings shows the same titles; a test ring/notification plays the
  expected sound.
- **Notes:** The receiver re-resolves each built-in **by title** to a *local* URI — it never writes
  a sender-supplied URI verbatim. If a title has no match on the receiver (different OEM sound set),
  that role is left as-is (status OK, "no matching built-in"). Custom sound **files** are Phase 2
  (USER_FILE roles are skipped).

**C8 — Bluetooth pairings (list + re-pair checklist)**
- **Do:** With Bluetooth on and `BLUETOOTH_CONNECT` granted on the sender, export Bluetooth pairings.
- **Expect:** Receiver shows the previously-paired device **names + addresses** as a **re-pair
  checklist**. You manually re-pair each in Bluetooth settings and tick it off.
- **Notes:** Read via the **public** `BluetoothAdapter.getBondedDevices()` — **no ADB bridge, no
  escalation**. **No link keys are carried** (controller-bound, non-transferable), so re-pairing is
  unavoidable by design. Phase 1 is display-only (no assisted `createBond`). BT off or permission
  denied → item self-omits. Bad MACs / blank names are dropped.

**C9 — App-backup relay (Signal / Molly / Aegis)**
- **Do:** On the sender, for an installed relay-capable app, use the **SAF pick** UI (shipped in
  #39/#40) to choose that app's **user-exported backup file**. Transfer. On the receiver, follow
  the restore prompt into the target app and enter **your** passphrase.
- **Expect:**
  - Sender: the pick shows the file name + size; it ships as an **opaque** item.
  - Receiver Done screen: "Relayed an app backup for &lt;app&gt; — open it there to restore." The blob
    lands at `…/Android/data/<recv-pkg>/files/Download/<targetPackage>-<itemId>-relay.bin` (a
    **generated** name, never the original).
  - Opening it in the target app and entering the passphrase restores it.
- **Notes:**
  - portage **never parses, decrypts, or sniffs** the blob — read for length + sha256 only. The
    **passphrase never touches portage**; if the user forgot it, portage cannot help (by design).
  - Redirect defence: the target package is the typed `RelayApp` enum and the advisory
    `targetPackage` is re-validated against it — a hostile sender can't repoint the re-link.
  - A pick whose SAF grant was revoked before ship is flagged **expired** and excluded (never
    silently dropped, never crashes) — verify by revoking access mid-flow.
  - The per-item byte cap is raised for **this kind only**; Tier-0 PII items keep the 64 MiB cap.

---

## D. Relay grant-sweep (orphaned SAF grant hygiene — PR #42, THREAT_MODEL §3.8)

The sender persists a SAF **read** grant on each relay pick so it survives activity recreation, and
releases it on remove / reset / successful ship. A process death mid-flow can orphan a grant;
`MainActivity.sweepOrphanedRelayGrantsOnce()` sweeps orphans on a **cold process start**.

**D1 — Cold-start sweep releases orphans**
- **Do:** On the sender: pick a relay file (grant taken), then **force-stop** `portage-send` before
  shipping. Relaunch it (cold start).
- **Expect:** No stale relay picks linger from the killed session; the orphaned grant is released on
  the cold start. Optionally confirm via `adb shell dumpsys content | grep <send-pkg>` (or the
  per-app SAF grants list) that no persisted URI permission for the abandoned pick remains.

**D2 — Config-change must NOT sweep live picks (regression guard)**
- **Do:** Pick a relay file, then **rotate the device** (or fold/unfold "rango") to force an
  activity recreation **without** killing the process.
- **Expect:** The pick **survives** and still ships correctly — the rotation must **not** revoke its
  grant. (This is the whole point of the once-per-process guard.)

---

## E. Teardown

- [ ] On the receiver, if portage still holds the **SMS role**, return it to your real texting app.
- [ ] Delete any relayed `*-relay.bin` files from the receiver's app Downloads after a successful
      target-app restore (prefer delete-after-import).
- [ ] Optional: turn Wireless Debugging back off on the receiver.
- [ ] The WSS grant can be left in place (persists) or revoked via Settings for a clean §A re-run.

---

## F. Sign-off

| Area | Pass | Tester / date | Notes |
|------|:----:|---------------|-------|
| A. Wizard pair → grant → probe → disconnect |  |  |  |
| A. Reboot reconnect (no re-pair) |  |  |  |
| B. Handshake link + fail-closed |  |  |  |
| C1. Contacts / Calendar / Call log |  |  |  |
| C2. SMS role acquire/write/relinquish + reconcile |  |  |  |
| C3. App inventory + reinstall |  |  |  |
| C4. Settings parity (+ DEVICE_SPECIFIC negative) |  |  |  |
| C5. device_name (Tier-1) |  |  |  |
| C6. Wallpaper home + lock |  |  |  |
| C7. Sound selection (built-in by title) |  |  |  |
| C8. Bluetooth re-pair checklist |  |  |  |
| C9. App-backup relay round-trip |  |  |  |
| D1. Relay grant cold-start sweep |  |  |  |
| D2. Relay pick survives config-change |  |  |  |

---

## G. GOS-specific gotchas (don't mistake these for bugs)

1. **Wireless Debugging resets on reboot.** Two taps to restore; no re-pair (key persists).
2. **GOS auto-reboot (~18 h idle-locked).** A device prepped overnight needs the toggle flipped in
   the morning; the bridge is not live until then.
3. **USB "charging-only when locked."** Irrelevant to portage (it uses Wi-Fi) but it can drop an
   `adb` session you're using to drive the device — `adb reconnect` or kill/start-server recovers.
4. **Network permission denied → silent socket failure** (no dialog). Grant the GOS Network
   permission to both apps first; a "nothing happens" symptom is usually this.
5. **POST_NOTIFICATIONS denied by default** — portage uses an in-app banner (not a notification) for
   the SMS-role reconcile for exactly this reason.
6. **Pairing dialog port changes on every open** — always read the current port; don't reuse one.
7. **`connect()` hang if Wireless Debugging is off** — mitigated by the wizard's toggle-gate; never
   force a connect with the toggle off.
8. **libadb-android / spake2-android were never security-audited upstream** — accepted residuals
   tracked in ADR-003 §5 / ADR-004; a dedicated source review is a pre-release gate, not a runbook
   step.
