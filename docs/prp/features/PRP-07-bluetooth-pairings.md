# PRP-07 — Bluetooth pairing list + assisted re-pair

Status: **DRAFT — feasibility-spike-FIRST.** Sourced from backlog #7
(`docs/prp/feature-research-2026-06.md:21`). Phase 0 (the spike) is a hard gate on
Phases 1–2; do not start Phase 1 until the spike verdict is recorded as an ADR.
Decision owner: JD. Drafted by planning agent, 2026-06-12.

> **Read this first.** The honest framing below is the whole point of this PRP. If you skim
> one box, skim §1.

---

## 1. Summary & user value

**Lead with the limitation.** Bluetooth link keys (the per-bond secrets that let two devices
reconnect without re-pairing) live in `/data/misc/bluedroid/bt_config.conf` (privileged) and
are **cryptographically bound to the controller they were generated against** — they are
derived during SSP/LE pairing against the local adapter's identity (BD_ADDR / IRK). Copying
those bytes to a *new* phone, with a different controller identity, does **not** generally let
the new phone reconnect silently: the remote device will reject or renegotiate, and on BLE the
IRK/identity resolving will not line up. So the obvious dream — "transfer pairings so they just
work" — is **mostly infeasible**, and re-pairing each device by hand is usually unavoidable.
This matches the source signal: CalyxOS lists Bluetooth among Seedvault's exclusions, and
first-hand migration reports say "all bluetooth devices needed to be paired again"
(`docs/prp/feature-research-2026-06.md:21,48-51`).

**So what *is* the value?** Today the migration pain is not just the re-pairing — it is the
*blindness*: standing in front of a new phone, the user can't remember what they were even
paired to (the car, two headphones, a watch, a keyboard, a tracker…), so devices get silently
forgotten until they're needed. portage's deliverable is to **kill the blindness**:

1. On the old phone, read the **list** of previously-paired devices (name, MAC address, device
   class/type) from `bt_config.conf` via the shell-uid bridge.
2. Carry that list over LAN as a normal portage item.
3. On the new phone, **present it as a guided re-pair checklist** and *assist* each entry —
   start discovery, surface the device, and deep-link into the system Bluetooth pairing flow —
   turning "what was I even paired to?" into a tick-box list the user works through.

The value proposition the UI must commit to is **"here's everything you had paired — let's
re-pair them one by one,"** NOT "your pairings moved." Overpromising seamless transfer is a
spec failure (see §9). This mirrors the App-inventory feature exactly: portage reads a list,
ships it, and produces a *checklist of user-driven actions* rather than performing a privileged
mutation (`providers/.../inventory/InventoryProviders.kt:44-83,103-140`).

---

## 2. Scope & non-goals

**In scope (the achievable deliverable):**

- Privileged **read** of the paired-device *roster* from `bt_config.conf`: per-device
  display name, MAC (BD_ADDR), and the CoD/device-type so the checklist can show an icon
  and a sensible label.
- A new `core-model` item kind carrying that roster as a list (§5).
- A receiver-side **assisted re-pair** UX: a checklist that, per row, starts Bluetooth
  discovery and hands the user into the platform pairing UI (`ACTION_BLUETOOTH_SETTINGS` /
  the system pair flow). One tap → the OS does the actual bond. No silent bonding.

**Non-goals (explicit, honest):**

- **NOT link-key / bond-secret transfer.** portage never copies `LinkKey`, `LE_KEY_*`, IRK,
  or any pairing secret onto the new device. Even if the spike found a niche where reuse
  worked, shipping bond secrets over the wire is out of scope for v1 (privacy + the "we don't
  move secrets" posture; see §7). The roster is names + addresses + class ONLY.
- **NOT silent re-pairing.** The OS owns bonding; portage assists, it does not bond. (Same
  hard constraint as APK install, `InventoryProviders.kt:46-48`.)
- **NOT a new privilege capability.** This rides the existing one-shot ADB bridge for a
  *read* only. No new escalation, no live bridge held open beyond the read.
- **NOT BLE GATT state, audio codec prefs, or per-profile (A2DP/HFP) config.** Just the roster.
- **No app-data.** Trivially true here — there is no Bluetooth app-data; this is OS state.

---

## 3. Feasibility & privilege

**Where the data is.** `/data/misc/bluedroid/bt_config.conf` is an INI-style file owned by the
`bluetooth` system uid, world-unreadable. It is reachable by **shell uid (2000)** on a device
where Bluetooth has been used — which is exactly the privilege the ADB bridge already grants
(`adb-bridge/.../AdbBridge.kt:32-76`, ADR-003). Each `[<MAC>]` section carries `Name`,
`DevClass`/`DevType`, and the secret key fields (`LinkKey`, `LE_KEY_*`) that we deliberately
**do not** read.

**Privilege tier: 1 (privileged read).** This is a *read*, strictly weaker than the Tier-1
*writes* already shipped (settings, install). Per ADR-001's reach table, reading privileged
state needs shell uid at call time; there is no persistent-grant analog to
`WRITE_SECURE_SETTINGS` for "read an arbitrary system file," so unlike settings (ADR-001 §1
grant architecture), this read needs the **bridge live at read time** — same hot-path shape as
`installApk`/permission-parity (`AdbBridge.kt:67-76`). On the receiver side, the *assist* is
**Tier 0** — `BluetoothAdapter.startDiscovery()` + an intent into system settings needs only
the normal `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` runtime permissions, no bridge.

**The REQUIRED spike (Phase 0 — gates everything).** Before any wire/UX work, confirm on real
GOS A16 hardware:

1. **Read path.** Does `shell("cat /data/misc/bluedroid/bt_config.conf")` (run *inside*
   `:adb-bridge` — see the call-site rule, §7) actually return the file as shell uid on GOS, or
   has GOS tightened SELinux so even shell can't read `bluedroid`? If the latter, the feature is
   **NO-GO** on the privileged-read leg and degrades to "user manually lists their devices" —
   probably not worth shipping. Record the exact denial (`avc: denied` line) if it fails.
2. **Key-reuse question (the one everyone asks).** Take a real `LinkKey`/`LE_KEY_*` from one
   GOS phone, inject it into a *second* GOS phone's `bt_config.conf` (shell uid, Bluetooth
   stopped), restart Bluetooth, and test whether ANY previously-bonded peer reconnects without
   a fresh pair. **Expected: NO** (controller-bound identity; see §1). Test at least one BR/EDR
   peer (headphones) and one BLE peer (a watch/tracker). The verdict here decides whether the
   feature is "list + assist only" (expected) or has any seamless slice at all.
3. **Format stability.** Confirm the `bt_config.conf` section/key names on the current GOS
   build (`getprop ro.build.fingerprint`, recorded per ADR-001 §2 #6 convention). The parser
   must fail-soft on an unrecognized layout (skip the section, never crash).

Why link keys are device-bound, stated plainly for the ADR: SSP/LE bonding derives the stored
secret against the **local adapter identity**; the remote stores *its* view keyed to the old
phone's BD_ADDR/IRK. A new phone has a different controller identity, so the remote's stored
material no longer matches and the link fails authentication → the remote forces a new pair.
This is a property of the Bluetooth security model, not a GOS limitation.

---

## 4. Architecture fit

The feature decomposes cleanly onto the existing seams — it is structurally the
**App-inventory pattern** (`InventoryProviders.kt`) with a privileged source instead of
`PackageManager`:

| Concern | Existing pattern to mirror | New code |
|---|---|---|
| Item kind | `ItemKind` enum, frozen wire string + tier (`core-model/.../Manifest.kt:24-36`) | `BT_PAIRINGS("bt.pairings", Tier.TIER1)` |
| Privileged read | `AdbBridge` typed op (NOT raw `shell()` outside the module) (`AdbBridge.kt:78-113`) | `AdbBridge.readBluetoothConfig(): ReadResult` typed op + parser in `:adb-bridge` |
| Sender provider | `ExportProvider` (`providers/.../Providers.kt:26-39`) | `BtPairingsExportProvider` — reads roster via a `BluetoothRosterSource` seam, emits JSON |
| Wire codec | `JsonLines` / single-doc JSON (`providers/.../wire/JsonLines.kt`, `InventoryProviders.kt:96-100`) | `@Serializable BtPairingRoster` document |
| Receiver provider | `ApplyProvider` → produces a checklist, applies nothing itself (`InventoryProviders.kt:103-140`) | `BtPairingsApplyProvider` → emits `List<RePairAction>` via an `onActions` callback |
| Receiver assist UX | `ChecklistScreen` + `ReceiverChecklist`, the inventory reinstall list, and the SMS guided-handoff (`app-recv/.../checklist/ReceiverChecklist.kt`, `providers/.../sms/SmsProviders.kt:47-58`) | A "Re-pair Bluetooth" section: per-row `startDiscovery()` + launch system pair intent |
| Tier-1 gating | checklist treats Tier-1 as opt-in (`ReceiverChecklist.kt:31-37`) | `BT_PAIRINGS` not pre-checked; shown but opt-in |

**Crucial boundary fit:** the CI gate at `.github/workflows/build.yml:73-81` *fails the build*
on any `.shell(` call outside `:adb-bridge`. So the privileged read MUST be exposed as a **typed
`AdbBridge` operation** (e.g. `readBluetoothConfig()`), parsed inside `:adb-bridge`, returning a
typed roster — providers/app-recv never see a raw shell string. This is the same discipline that
keeps `installApk` typed (`AdbBridge.kt:67-76`). It also keeps `:adb-bridge` out of `portage-send`
(the sender never reads privileged state; the escalation gate `build.yml:47-72` stays green).

The sender's roster read still goes through the bridge, so on the **sender** side this is the
first Tier-1 *read* that links the bridge — confirm that does not regress the send-side
escalation assert (`build.yml:47-72` checks `app-send`, and the sender DOES need the bridge to
read its own `bt_config.conf`). **Open design point (§9):** if we refuse to link `:adb-bridge`
into `portage-send`, the roster read must happen via the `TierOneGrant`-style narrow seam the
settings path uses (`SettingsProviders.kt:76-90`) — but that seam grants a *write* permission,
not a privileged *read*. This tension is the single biggest architecture question and is called
out in §9; the spike must settle whether the sender can read `bt_config.conf` at all without a
bridge.

---

## 5. Data model & wire representation

One roster document, names + addresses + class ONLY — **never** key material:

```kotlin
// core-model (or providers, matching InventoryProviders' AppRecord placement)
@Serializable
data class BtPairedDevice(
    val address: String,   // BD_ADDR "AA:BB:CC:DD:EE:FF" — validated, see below
    val name: String,      // display label from the [MAC] section's Name=
    val devClass: Int?,    // Class of Device, for icon/label; null if absent
    val devType: Int?,     // 1=BR/EDR, 2=BLE, 3=dual — drives the assist hint
)

@Serializable
data class BtPairingRoster(val devices: List<BtPairedDevice>)
```

**Validation (mirrors the App-inventory package-name regex, `InventoryProviders.kt:56-62`):**

- `address` MUST match `^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$`. Non-matching entries are
  **dropped** on both read and apply (a malformed MAC could smuggle text into a UI label or an
  intent extra — same HIGH-severity reasoning as the inventory deep-link review).
- `name` is display-only and never used for any filesystem/intent-target decision (manifest
  display-field discipline, `Manifest.kt:38-51`). Truncate to a sane cap; strip control chars.
- `devClass`/`devType` are opaque integers passed through, never interpreted as anything but
  an icon hint — unknown values fall back to a generic icon.
- The receiver **dedupes by `address`** before building the checklist (Compose `LazyColumn`
  key-collision guard, exactly the inventory `distinctBy` fix, `InventoryProviders.kt:128-131`).

**Privacy note (carries into §7):** this payload is low-sensitivity (no secrets), but a **list
of MAC addresses is mildly privacy-relevant** — BD_ADDRs of a user's headphones/car/wearables
are stable identifiers that fingerprint the person's device environment. It travels only inside
the already-encrypted Noise channel (THREAT_MODEL §2 row 1), and the receiver wipes staging on
completion (THREAT_MODEL §3.4), so exposure is bounded to the same envelope as contacts — but
the THREAT_MODEL asset list should gain "Bluetooth device roster (fingerprintable)" alongside
"app inventory (fingerprintable)" (`THREAT_MODEL.md:14`).

---

## 6. Phased implementation plan (TDD, small mergeable phases)

Branch-per-phase off `main` (`CLAUDE.md` working cadence). Each phase ends green in CI and gets
an independent `code-reviewer` pass; **§7 mandates `security-reviewer` for the privileged read.**

**Phase 0 — Feasibility spike (HARD GATE; no production code).**
- On real GOS A16 hardware, run the three spike checks in §3. Record commands, stdout/stderr,
  exit codes, and `ro.build.fingerprint` per run (ADR-001 §2 #6 convention).
- **Deliverable:** an ADR (`docs/prp/ADR-005-bluetooth-pairings.md`) with the read-path verdict,
  the key-reuse verdict, and the format snapshot. **If the read path is NO-GO, STOP** — record
  the denial and close the feature (degrade to a docs note like eSIM,
  `feature-research-2026-06.md:24-25`). If key-reuse is NO (expected), the rest of this plan
  proceeds as list+assist only.
- Acceptance: ADR merged with verdicts; Phase 1 references it.

**Phase 1 — Read + transfer the device list (no UX).**
- TDD `:adb-bridge`: a `readBluetoothConfig()` typed op + a pure `BtConfigParser` (INI →
  `BtPairingRoster`, key fields IGNORED by construction so they can never be emitted). Unit-test
  the parser against captured `bt_config.conf` fixtures incl. malformed/secret-bearing sections
  → assert NO `LinkKey`/`LE_KEY` ever appears in output. Fakeable over `AdbDeviceGate` like
  `LocalAdbBridgeTest`.
- TDD `:core-model`: add `BT_PAIRINGS("bt.pairings", Tier.TIER1)` (frozen-enum discipline,
  `Manifest.kt:32-36`).
- TDD `:providers`: `BtPairingsExportProvider` (roster → JSON) + `BtPairingsApplyProvider`
  (JSON → `List<RePairAction>` via `onActions`, dedupe + MAC-validate + drop-count detail line),
  mirroring `AppInventory{Export,Apply}Provider` and its test
  (`providers/.../inventory/InventoryProvidersTest.kt`).
- Acceptance: roster round-trips over the loopback transfer smoke test
  (`app-recv/.../transfer/LoopbackTransferSmokeTest.kt`); apply produces actions and writes
  nothing; `:settings-catalog` and the escalation/`.shell()` CI gates stay green.

**Phase 2 — Receiver assisted-re-pair UX.**
- TDD the checklist logic: `BT_PAIRINGS` shown, NOT pre-checked (Tier-1 opt-in,
  `ReceiverChecklist.kt:31-37`); absent-kind handling (`ReceiverChecklist.kt:83-91`).
- Compose "Re-pair Bluetooth" section in `ChecklistScreen`/a new screen: per row, an icon from
  `devClass`, the name, the MAC, a "Re-pair" button that calls `startDiscovery()` and launches
  the system Bluetooth pairing intent; a done/checked state the user toggles.
- Runtime-permission handling for `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` with graceful-deny
  (degrade to "open Bluetooth settings" deep link), matching the provider graceful-degrade
  contract (`Providers.kt:24-25`).
- Acceptance: ViewModel/checklist unit tests; manual on-device walk (VERIFICATION-RUNBOOK
  style) confirming a tap reaches the system pair dialog and the bond completes via the OS.

---

## 7. Security considerations

- **Privileged read, narrowest possible.** The bridge reads exactly one file and the parser
  emits exactly four fields per device. **The `LinkKey`/`LE_KEY_*` fields are never read into a
  variable that can be serialized** — the parser allowlists output fields rather than blocklists
  secrets (fail-closed, like the settings allowlist `SettingsProviders.kt:98-101` and the
  validator's reject-by-default `settings-catalog/.../Validation.kt:18-26`). The parser test
  MUST assert that a secret-bearing fixture yields a roster with no secret substrings.
- **Call-site rule (ADR-003 §1, CI-enforced).** Only `:adb-bridge` may call `.shell(`
  (`build.yml:73-81`). The roster read is a **typed** `AdbBridge` op; providers/app-recv never
  touch raw shell. A reviewer must confirm no `cat`/`grep` string is interpolated with untrusted
  input (it isn't — the path is a constant; use `ShellArgs`, `AdbBridge.kt:108-113`).
- **Don't exfiltrate secrets; don't widen the sender.** Confirm `:adb-bridge` does not leak into
  `portage-send` in a way the escalation gate misses, and that the roster JSON on the wire
  contains no key material (a transport-layer test can assert the serialized bytes match
  `^[^L]*` for `LinkKey`… or better, decode-and-assert).
- **MAC addresses are mildly sensitive** (§5): add "Bluetooth roster (fingerprintable)" to the
  THREAT_MODEL asset list (`THREAT_MODEL.md:14`); it is protected by the same Noise AEAD
  envelope and staging-wipe as other PII, nothing stronger claimed.
- **`security-reviewer` is MANDATORY** for this change — it touches the privilege boundary and a
  new wire item (`CLAUDE.md` working cadence: crypto/privilege/wire ⇒ security-reviewer, not
  optional). The bar: the read is least-privilege, no secret can reach the wire, and the assist
  cannot be steered (no silent bond, intents carry only validated MACs).
- **Receiver sovereignty intact** (THREAT_MODEL §4): a hostile sender's roster can at worst
  populate a re-pair *suggestion* list the user taps through — it cannot make the receiver bond
  anything; the OS pairing UI is the consent ceremony.

---

## 8. Test plan & CI gates

**Unit (pure-JVM, the bulk):**
- `BtConfigParser`: real-format fixtures, malformed sections, secret-bearing sections (assert
  zero secret leakage), unknown layout (fail-soft), empty file. This is the safety-critical test.
- `BtPairingsExportProvider`/`BtPairingsApplyProvider`: round-trip; MAC validation drops bad
  entries with a counted `dropped`; dedupe by address; graceful-empty (mirror
  `InventoryProvidersTest.kt`).
- `ReceiverChecklist`: `BT_PAIRINGS` present-but-unchecked; absent-kind row.
- `LocalAdbBridge`: `readBluetoothConfig()` typed result mapping over a fake `AdbDeviceGate`
  (NotConnected → typed unavailable, never throw), like `LocalAdbBridgeTest`.

**Integration:** roster through `LoopbackTransferSmokeTest`; apply yields actions, writes nothing.

**CI gates (existing, must stay green — `build.yml`):**
- `:settings-catalog:test` (unaffected, fast lane).
- The unit-test job line gains the new provider/model tests (already covered by
  `:providers:testDebugUnitTest` + `:core-model:test`, `build.yml:43-44`).
- **Escalation assert** (`build.yml:47-72`): verify `BT_PAIRINGS` did not pull `:adb-bridge` /
  `WRITE_SECURE_SETTINGS` into `portage-send` unexpectedly — this is the gate to watch given the
  §9 sender-read tension.
- **Raw-`.shell()` gate** (`build.yml:73-81`): the new read MUST be a typed op or this fails.

**On-device (VERIFICATION-RUNBOOK, per fingerprint):** the Phase-0 spike checks; a Phase-2 walk
that a "Re-pair" tap reaches the system dialog and bonds.

---

## 9. Open questions / risks

**The spike dominates. If key-reuse is NO (expected), this feature is list+assist ONLY — say so
plainly in every UI string and the README.** No amount of polish turns the checklist into
"pairings moved."

1. **Read-path SELinux (NO-GO risk).** If GOS denies shell uid read of `bluedroid` (§3 spike
   #1), the privileged read is dead and the feature likely isn't worth shipping vs. "user reads
   their own paired list off the old phone." This is the kill switch. *Mitigation: none in-app —
   it's a platform verdict; record and possibly close like eSIM.*
2. **Sender-side bridge tension (biggest architecture unknown).** Reading `bt_config.conf` on
   the *sender* needs shell uid, but ADR-003's deliberate design keeps `:adb-bridge` OUT of
   `portage-send` (`build.yml:47-72`, ADR-003 §9 "Wizard in both apps → Receiver only"). So
   *which device runs the privileged read?* Options the spike/ADR-005 must choose between:
   (a) the *new* phone reads its OWN old roster — wrong, the roster is on the OLD phone;
   (b) link a **read-only** bridge into the sender and relax the escalation assert to allow a
   privileged *read* but still forbid *writes* — widens the sender's privilege surface, contra
   ADR-003; (c) require the user to run the receiver app on BOTH phones for this one item. There
   is no clean answer yet; **this question gates the whole feature's shape** and must be resolved
   in ADR-005 before Phase 1.
3. **`bt_config.conf` format drift across GOS builds.** Parser must fail-soft and is fingerprint-
   scoped (§3 #3). *Mitigation: allowlist-output parser, skip unknown sections, never crash.*
4. **BLE identity (IRK) nuance.** Even the *list* read is reliable, but the device-type/`DevType`
   field may be absent or differ for BLE-only peers; the assist hint degrades to generic.
5. **Assist UX honesty.** The "Re-pair" button must not imply success it can't guarantee — the
   OS may still fail to bond (device off, out of range). Treat each row as a user-owned task,
   never auto-checked on tap (the inventory list never auto-marks installed either).
6. **Value-vs-effort.** If the realistic deliverable is "a checklist of device names you tap to
   open the pairing screen," weigh it against just documenting "write down your paired devices
   before migrating." Ship only if the privileged read clears the spike AND the guided checklist
   measurably beats a sticky note. Record that judgment in ADR-005.

---

### ADR pointer
Phase 0 produces **ADR-005-bluetooth-pairings** (read-path verdict + key-reuse verdict + format
snapshot + the §9.2 sender-read decision). Phases 1–2 are gated on its GO verdict.
