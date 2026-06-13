# PRP-01 — Wi-Fi saved networks + passphrases

Status: **DECLINED — 2026-06-13 (not feasible on unrooted GOS; do not re-litigate).** On-device
probe (rango, GOS A16, **shell uid 2000 — the MAX privilege portage's bridge can reach, NOT root**):
the saved-network store `/data/misc/apexdata/com.android.wifi/` is Permission-denied even to shell;
`cmd wifi list-networks` shows SSID + security type but **never a passphrase** and has no get/export
verb; `dumpsys wifi` **redacts** PSKs (only flags like `HasEncryptedPreSharedKey: false`, never the
value). Passwords are readable **only by root**, and GOS is unrooted (no `su`). Even escalating the
**sender** to shell-uid — which would break ADR-003's recv-only-bridge invariant and add an exporter
attack surface — yields only a **list of SSIDs, never the passwords**, so the trade isn't worth it.
This is Seedvault's domain (it holds the system backup privilege portage deliberately lacks). See
`SPIKE-RESULTS-2026-06-12.md`. Original draft retained below for the record.

Grounding: this PRP was written against the live tree. It mirrors the call-log provider
(`providers/.../calllog/CallLogProviders.kt`), the Tier-1 settings provider
(`providers/.../settings/SettingsProviders.kt`), the allowlist guardrail
(`settings-catalog/.../SettingsAllowlistTest.kt`), the privilege surface
(`adb-bridge/.../AdbBridge.kt`), and the secret-handling discipline in `THREAT_MODEL.md` §1 +
`PROTOCOL.md`. Every "mirror X" below names a real symbol.

---

## 1. Summary & user value

Transfer the user's **saved Wi-Fi networks** — SSID, passphrase/PSK, security type, and the
hidden-network flag — from the old phone to the new one over the existing Noise LAN channel, so
the new device rejoins known networks without the owner re-typing every passphrase.

**Sourced signal** (`feature-research-2026-06.md` row 1 + Sources block lines 43-55):
- Every migration competitor moves saved Wi-Fi: Google cable, Smart Switch (`ANS10001412`), Swift
  Backup, XDA Migrate.
- Seedvault **skips or flakily handles** Wi-Fi; CalyxOS Seedvault docs list it as an explicit
  *exclusion* (`feature-research-2026-06.md` line 48).
- Top "did my stuff actually move?" question in GrapheneOS migration threads
  (`feature-research-2026-06.md` lines 15, 44 — Discuss d/24034).

On-brand for portage's stated position (`feature-research-2026-06.md` §"Strategic note"): the
*reliable* LAN-parity layer for the categories it owns. Wi-Fi is a portage category (network config,
not app-data) and the highest value × feasibility item it does not yet have.

---

## 2. Scope & non-goals

**In scope (an `ItemKind.WIFI` item, Tier 1):**
- Per-network: SSID, security type (OPEN / WPA2-PSK / WPA3-SAE / WPA-EAP-as-skip), pre-shared
  passphrase where one exists, hidden-SSID flag.
- Sender-side consent at the item-list granularity portage already uses (`THREAT_MODEL.md` §3.5:
  "consent granularity is the item list, not per-field"). Approving "Wi-Fi networks" sends all
  saved networks — same model as "contacts".

**Non-goals (and why each protects the Seedvault boundary, `CLAUDE.md` "Scope discipline";
`PROTOCOL.md` §4 no-`seedvault.blob` note; `DEVILS_ADVOCATE` Q5):**
- **No app-data.** Wi-Fi config is OS network state, not an app's private data blob. There is no
  `seedvault.blob`-shaped payload here and none is introduced — same discipline that froze
  `ItemKind` without `SEEDVAULT_BLOB` (`core-model/.../Manifest.kt` lines 32-36).
- **No enterprise (802.1x/EAP) credentials in v1.** Client certs/keys are non-portable secrets
  bound to the device keystore — drop them (export emits the SSID as an OPEN-skip note, never the
  credential). Mirrors ADR-001 §2.5's "must be dropped from scope, not best-effort" stance.
- **No Passpoint/Hotspot 2.0, no carrier/Wi-Fi-calling, no per-network proxy/IP-static config**
  in v1 — out of the "rejoin my known networks" core.
- **Owner profile only** (user 0), consistent with the whole project (`CLAUDE.md` established
  facts; ADR-001 §2.4, V8).

---

## 3. Feasibility & privilege  ⚠ SPIKE REQUIRED BEFORE CODE

### 3.1 The read side (source of truth)
Saved Wi-Fi lives in the privileged on-device file
`/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml` (and a sibling
`WifiConfigStoreSoftAp.xml`). This path is owned by `system`/`wifi` uid and is **not** readable by
a normal app. The only sanctioned read seam in portage is the ADB bridge running shell-uid
(`adb-bridge/.../AdbBridge.kt` — "the single privilege boundary"; `CLAUDE.md`:
"`AdbBridge` is the only allowed entry point to privileged operations").

Open question the spike must answer (§9-Q1): **does shell uid (2000) even have read access to
`/data/misc/apexdata/com.android.wifi/`?** It may be `0700 system:system`, in which case the
config-store file is unreadable at shell and the read must instead come from a `cmd wifi`/dumpsys
surface or be declared infeasible at Tier 1. Do not assume the file is shell-readable.

### 3.2 The restore side — two candidate paths (spike picks one)
| Path | Mechanism | Pros | Cons / risk |
|---|---|---|---|
| **(a) App-level API** | `WifiManager.addNetwork(config)` (deprecated A29+) **or** `WifiNetworkSuggestion` API | No privilege; survives GOS updates; future GOS system-app build (ADR-003 §OS-integration) keeps working | On A16, third-party `addNetwork` is heavily restricted; suggestions may **prompt per network** and may not auto-connect / may not surface the passphrase as "saved" in Settings → UX gap |
| **(b) Privileged write-back** | `cmd wifi add-network` / write `WifiConfigStore.xml` via shell uid, then `cmd wifi reload-networks` | Networks appear as truly "saved"; closest to competitor behavior | **Version-fragile** XML schema; risk of corrupting the wifi config DB; needs `system`-uid (shell may be insufficient); the exact failure ADR-001 §2.5 warns about for GOS-private state |

**Tier classification.** Read = privileged ⇒ this is **Tier 1**. `ItemKind.WIFI` is tagged
`Tier.TIER1` (mirrors `APK`/`SETTINGS` in `core-model/.../Manifest.kt` lines 30-31), so the
receiver checklist treats it opt-in. Whether restore needs a *live* bridge or a one-shot grant is
exactly the live/grant split ADR-001 §1 draws — the spike resolves which row of that table Wi-Fi
restore sits in.

### 3.3 ADB-bridge needs
No new `AdbBridge` *raw* surface should be added casually — `shell()` call-sites outside
`:adb-bridge`/`:wizard` are review blockers (`AdbBridge.kt` lines 28-31; `CLAUDE.md`). Instead, add
a **typed op** on `AdbBridge` built via `ShellArgs` (mirror `writeSecureSetting`/`setSmsRoleHolder`,
`AdbBridge.kt` lines 85-107), e.g. `suspend fun readWifiConfigStore(): ShellResult` and/or
`suspend fun reloadWifiNetworks(): OpResult`. Add a `PrivilegedCapability.WIFI_CONFIG` to the
`probeCapabilities()` enum (`AdbBridge.kt` lines 122-141) so the checklist/wizard can show Wi-Fi as
available only when the device verifiably allows the read. A new `Reach.T1_SHELL` consumer is the
natural fit — that enum value already exists for "needs a LIVE shell-uid bridge at call time"
(`settings-catalog/.../SettingKey.kt` lines 29-31) and is currently unused on the data path.

### 3.4 GOS constraints (from ADR-001 §2, applied to Wi-Fi)
- Wireless Debugging (hence the bridge) resets on reboot and after GOS auto-reboot (ADR-001 §2.1-2.2)
  — Wi-Fi restore must run *during* the active transfer session while the wizard holds a live
  bridge, then the wizard disconnects (`CLAUDE.md`: "never hold shell uid open").
- Record `ro.build.fingerprint` with the spike result; a Wi-Fi verdict is only valid for the
  fingerprint measured (ADR-001 §2.6).
- GOS-private network state has historically been the fragile case (ADR-001 §2.5) — bias the spike
  toward path (a) unless (a) is demonstrably broken on A16.

### 3.5 Required spike (gate; mirror ADR-001 §3 V-run format, ADR-003 §7)
Run on a real Pixel/GOS A16 device, record command/stdout/exit/fingerprint for each:
1. **W1 — read access:** `shell ls -l /data/misc/apexdata/com.android.wifi/` and attempt to read
   `WifiConfigStore.xml` at shell uid. Verdict: shell-readable Y/N. (N ⇒ find a `cmd wifi`/dumpsys
   read or declare Tier-1 read NO-GO.)
2. **W2 — parse:** confirm the XML schema (SSID, `PreSharedKey`, `SecurityType`/`AllowedKeyMgmt`,
   `HiddenSSID`) on this build.
3. **W3 — restore path (a):** `WifiNetworkSuggestion` add of one PSK network — does it connect
   without a per-network prompt? Does Settings show it as saved?
4. **W4 — restore path (b):** `cmd wifi add-network`/reload at shell uid — does the network appear
   saved and persist a reboot, without corrupting the store?
5. **W5 — secret hygiene:** confirm no passphrase is written to logcat by any probe command.

**Go/No-go (mirror ADR-001 §4):** W1 fail with no read alternative ⇒ Tier-1 Wi-Fi NO-GO this
release. W1 pass + (W3 OR W4) pass ⇒ GO with the passing path; prefer (a). Record the verdict as
**ADR-005-wifi-restore-path** before Phase 1.

---

## 4. Architecture fit

Wi-Fi slots into the exact provider/transport shape the codebase already uses. No new module; new
files inside existing ones.

- **`:core-model`** — add `ItemKind.WIFI("wifi", Tier.TIER1)` to the frozen enum
  (`Manifest.kt` lines 24-36). This is a v1 wire addition; it is *additive* (unknown kinds already
  self-skip via `ApplyProviderRegistry`, `Providers.kt` lines 64-73) so older receivers degrade
  cleanly. Also add `MessageType`? **No** — Wi-Fi rides the existing ITEM_* stream unchanged
  (`Messages.kt`); only the `ItemKind` grows.
- **`:providers`** — new package `providers/wifi/` with:
  - `WifiNetwork` `@Serializable` record (mirror `CallRecord`, `CallLogProviders.kt` lines 26-33;
    `SmsRecord`, `SmsProviders.kt` lines 26-33).
  - `WifiConfigStore` interface — the privileged seam (mirror `CallLogStore`/`SmsStore`): a `readAll()`
    that the app-recv layer backs with the ADB bridge, and a `restore(network)` that backs onto the
    chosen path. Providers stay **privilege-agnostic** exactly like `SettingsExportProvider` takes a
    `SecureGlobalSettingsStore` seam, not an `AdbBridge` (`SettingsProviders.kt` lines 70-90,
    `TierOneGrant` fun-interface pattern).
  - `WifiExportProvider : ExportProvider` and `WifiApplyProvider : ApplyProvider`
    (`Providers.kt` lines 26-57). `available()` follows readability+content and degrades on denial
    (mirror `CallLogExportProvider.available`, lines 52-54). `WifiApplyProvider` is **hard-gated** on
    the restore capability being present, returning `ItemStatus.SKIPPED` otherwise — the same
    self-skip shape as `SmsApplyProvider` gating on `isSelfDefault()` (`SmsProviders.kt` lines 90-96)
    and `SettingsApplyProvider` gating on `canWrite()`.
- **`:app-send`** — `WifiExportProvider` is added to the provider list `ManifestBuilder` iterates
  (`ManifestBuilder.kt` lines 39-80). A faulty/denied provider is already excluded, never fatal.
- **`:app-recv`** — `WifiApplyProvider` is registered in the `applyRegistryFactory`
  (`ReceiverViewModel.kt` lines 57-77), wired with the bridge-backed `WifiConfigStore`. The wizard
  (`:wizard`) must hold the live bridge across the Wi-Fi apply, then disconnect (per `CLAUDE.md`).
- **`:core-transport`** — unchanged. Wi-Fi payload is just bytes in the existing AEAD ITEM stream
  with a SHA-256 at-rest check (`ItemStreamReceiver.kt` lines 154-208).

Pattern parity (what Wi-Fi copies): record+store seam = `CallRecord`/`CallLogStore` →
`WifiNetwork`/`WifiConfigStore`; providers = `CallLog{Export,Apply}Provider` →
`Wifi{Export,Apply}Provider`; privilege-agnostic seam = `TierOneGrant`/`SecureGlobalSettingsStore`
→ bridge-backed `WifiConfigStore`; hard-gated self-skip = `SmsApplyProvider.isSelfDefault()` →
`WifiApplyProvider` restore-capability gate; typed privileged op = `AdbBridge.setSmsRoleHolder` →
`AdbBridge.readWifiConfigStore`/`reloadWifiNetworks`.

---

## 5. Data model & wire representation

**New item kind** (`core-model/.../Manifest.kt`):
```
WIFI("wifi", Tier.TIER1)
```
`ItemMeta` is unchanged — Wi-Fi advertises like any item (`itemId/kind/size/sha256/displayName/
group`, lines 43-51). Suggested `displayName = "Wi-Fi networks"`, `group = "Network"`.

**Payload record** (mirror `CallRecord`, JSON-lines so one bad network is skipped not fatal,
`JsonLines.kt` lines 42-52; `PROTOCOL.md` §5 per-record resilience):
```
@Serializable
data class WifiNetwork(
    val ssid: String,          // raw SSID; never a path/filename (THREAT_MODEL malicious-sender)
    val security: WifiSecurity,// OPEN | WPA2_PSK | WPA3_SAE  (enum; EAP excluded → skipped on export)
    val psk: String? = null,   // SECRET; null for OPEN; wiped after apply (§7)
    val hidden: Boolean = false,
)
```

**Wire serialization.** Same envelope as every other item: JSON-lines payload inside the ITEM_DATA
chunks of the existing CBOR/Noise protocol (`PROTOCOL.md` §3-4). One `WifiNetwork` per line via
`JsonLines.writeTo`/`readFrom` (`JsonLines.kt`). No new `MessageType`, no protocol bump — additive
`ItemKind` only.

**Size / validation rules (receiver-enforced, never trusts the manifest):**
- Per-item byte cap inherited from `ItemStreamReceiver` `DEFAULT_MAX_ITEM_BYTES` (64 MiB,
  `ItemStreamReceiver.kt` lines 217-219) — Wi-Fi lists are tiny, well under it.
- Per-network validation on apply (mirror `Validator.accepts` discipline,
  `SettingKey.kt` lines 41-50; `SettingsApplyProvider` admissibility filter,
  `SettingsProviders.kt` lines 170-176):
  - `ssid`: length 1..32 bytes, reject control chars.
  - `security`: must be a known enum member; unknown ⇒ skip that network.
  - `psk`: WPA2 8..63 ASCII or 64 hex; SAE 1..63; reject anything else. OPEN must have `psk == null`.
  - `hidden`: boolean only.
- A network failing validation is a **per-network skip** counted in the detail string ("applied N,
  skipped M"), never a transport error — exactly `CallLogApplyProvider`/`SettingsApplyProvider`
  return shape (`CallLogProviders.kt` lines 68-75; `SettingsProviders.kt` lines 207-218).

---

## 6. Phased implementation plan (TDD)

Branch-per-phase off `main` (`CLAUDE.md` cadence); each phase is independently mergeable and green.
Tests are written FIRST (RED) and named with backticks but **no** `; . : / [ ] < >` chars
(MEMORY: Kotlin backtick illegal chars).

**Phase 0 — Spike & ADR (no production code).** Run §3.5 W1–W5 on device. Write
`docs/prp/ADR-005-wifi-restore-path.md` recording the chosen read+restore path, fingerprint, and
go/no-go. *Gate:* a recorded GO. Files: ADR only.

**Phase 1 — model + pure record/codec (no Android, no privilege).**
- Tests first: `WifiNetworkTest` (round-trips a `WifiNetwork` through `JsonLines`; a corrupt line is
  skipped+counted, asserting `malformed`), plus an `ItemKind` test that `WIFI.wire == "wifi"` and
  `WIFI.tier == TIER1`.
- Then: add `ItemKind.WIFI`; add `WifiNetwork` + `WifiSecurity` + `WifiValidation` in `:providers`.
- Green-bar: `:core-model:test` + `:providers:test` (the new tests) pass.

**Phase 2 — export/apply providers against fake stores.**
- Tests first: `WifiProvidersTest` mirroring `CallLogProvidersTest` exactly — `available` follows
  readability+content; export+apply round-trips two networks; denied read exports empty; a corrupt
  line is skipped not fatal; apply with the restore capability **absent** returns `SKIPPED` and
  writes nothing (mirror the SMS not-default test). Use a `FakeWifiConfigStore` (mirror
  `FakeCallLogStore`, `CallLogProvidersTest.kt` lines 20-42).
- Then: `WifiExportProvider`/`WifiApplyProvider` with the `WifiConfigStore` seam + the §5 validators.
- Green-bar: `:providers:test`.

**Phase 3 — privileged read/restore seam (the bridge-backed store, app-recv).**
- Tests first: a `LibAdbWifiConfigStoreTest`-style test with a fake `AdbBridge` (mirror
  `LocalAdbBridgeTest`) asserting the store builds the **typed argv** via `ShellArgs` (never raw
  interpolation) and that a `NotConnected`/`TransportFailure` ShellResult degrades to an empty read /
  unavailable restore — no throw. Add an `AdbBridge` typed-op test for `readWifiConfigStore` /
  `reloadWifiNetworks` and `PrivilegedCapability.WIFI_CONFIG` in `probeCapabilities`.
- Then: implement the bridge-backed `WifiConfigStore` in `:app-recv` (and the export-side reader),
  the typed `AdbBridge` ops + capability, and the XML/`cmd wifi` parse from the §3.5 verdict.
- Green-bar: `:adb-bridge:testDebugUnitTest` + `:app-recv` unit tests.

**Phase 4 — wiring + UI gating + done-summary.**
- Tests first: extend `ReceiverViewModelTest` so a manifest containing a `WIFI` item routes to the
  registry and the done-summary reflects applied/skipped; a `LoopbackTransferSmokeTest` case sending
  a `WIFI` item end-to-end through the real channel (mirror existing smoke test). Checklist shows
  Wi-Fi only when `WIFI_CONFIG` capability probed.
- Then: register `WifiApplyProvider` in `ReceiverViewModel`'s `applyRegistryFactory`; add
  `WifiExportProvider` to `:app-send`'s provider list; wizard holds-then-disconnects the bridge
  around the Wi-Fi apply.
- Green-bar: `:app-send:test` + `:app-recv` tests + the app-send no-escalation assert still green.

---

## 7. Security considerations

**Passphrases are secrets — treat exactly like the QR PSK** (`THREAT_MODEL.md` §1, residual:
"QR-encoded PSK String is a non-zeroizable accepted residual"; `CLAUDE.md` PSK handling):
- **Never logged.** No `psk` (and no `WifiConfigStore.xml` contents) in logcat, exception messages,
  or `ShellResult` echoes surfaced to UI — the same rule the bridge applies to the pairing code
  (`AdbBridge.kt` lines 39-41) and `SenderViewModel` applies to the QR PSK.
- **Wiped after use.** Where a passphrase lives in a mutable buffer, zeroize after apply — mirror the
  `payload.wipe()` discipline already in the accept/connect paths (`CLAUDE.md` "Open security
  follow-ups", CLOSED items). The `String` form is the same non-zeroizable accepted residual the
  threat model already documents for the PSK — bound it the same way and document it.
- **Encrypted in transit.** Inherits ChaCha20-Poly1305 AEAD over the Noise channel
  (`THREAT_MODEL.md` §2 row 1, §4). No new transport surface.
- **Validated on apply.** Receiver-side allowlist-style validation (§5) before any restore call —
  the receiver-sovereignty property (`THREAT_MODEL.md` §4: "nothing in the protocol can make the
  receiver write state outside its compiled catalog"). A hostile sender cannot inject a network the
  validators reject, and cannot steer the write target (the restore seam is fixed in app-recv, not
  carried on the wire — same lesson as "namespace is never carried", `SettingsProviders.kt`
  lines 28-32).
- **Staging hygiene.** The staged Wi-Fi payload (plaintext PSKs) is deleted after apply and the
  staging dir swept in `finally` — already guaranteed by `ItemStreamReceiver` (lines 38, 96-98,
  168-185). No change needed; just do not copy the payload out of staging.

**Privilege-boundary review points (mandatory security-reviewer pass — `CLAUDE.md` working
cadence: anything touching the privilege boundary or wire protocol):**
1. The Wi-Fi read/restore goes **only** through `AdbBridge` typed ops; assert no `shell()` call-site
   appears outside `:adb-bridge`/`:wizard` (the established review blocker).
2. The wizard **disconnects the bridge** immediately after the Wi-Fi apply probe/restore — never
   holds shell uid open (`CLAUDE.md`).
3. `WifiConfigStore.xml` parsing treats the file as **untrusted input** (it is privileged but
   version-fragile): bounded parse, no XXE, fail-closed to an empty read on any anomaly.
4. New `ItemKind.WIFI` is additive and older receivers self-skip it (`ApplyProviderRegistry`); no
   protocol version bump, no `seedvault.blob`-shaped expansion.
5. EAP/enterprise credentials are dropped on export, not transmitted.

**THREAT_MODEL alignment:** this feature adds a high-value secret to the payload set
(`THREAT_MODEL.md` §1 assets) but introduces no new adversary surface — same channel, same
receiver-allowlist enforcement (row 10), same staging hygiene (residual §3.4). Update
`THREAT_MODEL.md` §1 assets to name Wi-Fi passphrases explicitly when this lands.

---

## 8. Test plan & CI gates

**Unit (per phase, fakes over mocks — kotlin rules; mirror `CallLogProvidersTest`):**
- `:core-model` — `ItemKind.WIFI` wire/tier.
- `:providers` — `WifiNetworkTest` (codec + corrupt-line resilience), `WifiProvidersTest`
  (available/round-trip/denied/skip-when-uncapable/validation rejects bad PSK & SSID & EAP).
- `:adb-bridge` — typed-op argv built via `ShellArgs`, `WIFI_CONFIG` capability probe, degrade on
  `NotConnected`/`TransportFailure` (mirror `LocalAdbBridgeTest`, `ShellArgsTest`).
- `:app-recv` — bridge-backed `WifiConfigStore` parse + restore-gate; `ReceiverViewModelTest` routes
  a `WIFI` manifest item and reports correct applied/skipped.

**Integration / e2e:** extend `app-recv/.../LoopbackTransferSmokeTest` with a `WIFI` item over the
real Noise loopback channel (export → manifest → select → stream → stage → verify sha256 → apply),
asserting the done-summary counts and that no PSK appears in any captured log.

**Observability:** assert (a test that greps captured logs) that no passphrase or config-store
content is emitted at any level.

**CI gates this must keep green (`CLAUDE.md` "CI gates"; `.github/workflows/build.yml`):**
- `:settings-catalog:test` (untouched, but Wi-Fi must not regress the allowlist invariant if any
  Wi-Fi-adjacent secure key is ever added).
- `:core-model:test`, `:providers:test`, `:adb-bridge:testDebugUnitTest`,
  `:app-recv:testDebugUnitTest`, `:app-send:testDebugUnitTest`.
- `assembleDebug` (both APKs) + the app-send no-escalation assert (the sender must still link **no**
  privilege/`:adb-bridge` stack — Wi-Fi *read* on the sender side must use a non-privileged path or
  the spike must place the sender read behind a seam that does not pull privilege into app-send;
  resolve in §9-Q4).
- `dependency-audit.yml` (OSV) — only if the spike adds a new dep (avoid; prefer platform `cmd wifi`).

---

## 9. Open questions / risks / spikes

- **Q1 (blocking, spike W1):** Is `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml` readable
  at **shell uid (2000)** on GOS A16, or is it `system`-only? If shell-only-no, is there a `cmd wifi`
  / `dumpsys wifi` read that exposes SSID+PSK at shell? If neither ⇒ **Tier-1 Wi-Fi NO-GO** this
  release (document for users, like eSIM). *Matters: the whole feature's read path.*
- **Q2 (blocking, spike W3/W4):** Which restore path works on A16 without per-network prompts and
  shows networks as "saved" — `WifiNetworkSuggestion` (a), `cmd wifi add-network` (b), or neither?
  Prefer (a) for update-resilience. *Matters: restore reliability = the entire user value.*
- **Q3:** WPA3-SAE-only and OWE networks — representable in the v1 `WifiSecurity` enum, or skip with a
  noted reason? Likely defer SAE to validation-confirmed in the spike.
- **Q4 (privilege-hygiene risk):** The **sender** must read saved Wi-Fi too. app-send is required to
  link **no** privilege stack (the no-escalation CI assert). Resolve: does the sender read need shell
  uid (then app-send would have to gain a bridge — unacceptable), or is there a non-privileged sender
  read? If the read is privileged on both ends, the sender may need its own one-shot wizard, which is
  a meaningful scope expansion — flag before Phase 0.
- **Q5 (version-fragility risk):** `WifiConfigStore.xml` schema is AOSP-internal and changes between
  releases (ADR-001 §2.5 pattern). If restore path (b) is chosen, pin behavior to the GOS fingerprint
  and re-verify each GOS bump; bias hard toward path (a).
- **Q6:** Hidden-SSID networks may need explicit `hiddenSSID=true` at restore to ever connect — confirm
  in the spike that the chosen path honors the flag.

Open questions Q1, Q2, Q4 are also recorded in `docs/prp/open-questions.md` for cross-plan tracking.
```
