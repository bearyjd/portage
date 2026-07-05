# Northstar gap audit — how close can portage get, and where's the ceiling

_Analysis, 2026-07-04. Baseline: `main` @ `bb767f3`. Read-only audit; no code changed._
_Grounded by three passes: current-coverage inventory, settings-allowlist breadth, and platform-feasibility research (sources inline in §5)._

## The northstar

Move a user from an old phone to a new one with **all apps, all data, all settings** and **as few
clicks/thoughts as possible**, in three descending-priority cases:

1. **GOS → GOS** (best case — the design target)
2. **old Android → new GOS**
3. **old Android → new Android**

No cloud; LAN only. Scope discipline stands: **Seedvault owns app _data_; portage owns
settings/inventory/parity.** portage never carries a `seedvault.blob`.

## Verdict up front

- **GOS → GOS is already near its _parity_ ceiling** for the domains portage owns, and there is a
  clear, mostly-cheap set of levers that closes most of the remaining perceptible distance.
- **The biggest single closer is new:** portage's shell-uid bridge can very likely **trigger a
  Seedvault restore** (`bmgr restore`) in the same session — the same privilege tier as the SMS-role
  restore it already ships. That attacks the "all _data_" pillar **without portage ever touching a
  backup blob** (courier-not-absorber, exactly the line PRP-06 already draws). Unverified on metal →
  spike-gated, security-reviewer-gated.
- **The remaining distance to "everything" is structural, not effort** — accounts, Wi-Fi passwords,
  Bluetooth link keys, launcher layout, DND rules. No amount of engineering (or Fable creativity)
  moves those; they're platform/security ceilings.
- **Fable is the last mile, not the gap.** Its real contribution is making the unavoidably-multi-tool
  reality _feel_ like one guided flow, and the honest "here's what came over / what didn't and why"
  screen — polish on top of capability, not capability.

---

## 1. What moves today (GOS → GOS baseline)

14 item kinds ship and are wired end-to-end: contacts (vCard 3.0, photos), calendar (ICS), call log,
SMS, MMS, app **inventory** (reinstall checklist), **APK** (base+splits, silent via bridge or Tier-0
tap), **settings** (allowlisted SAFE only), wallpaper (home/lock), sound selection + custom sound
files, bluetooth **roster** (re-pair checklist, no keys), app-backup **relay** (opaque courier), and
user files (SAF-picked). Runtime-permission parity re-grants `{INTERNET, OTHER_SENSORS}` on the silent
APK path; everything else is opt-in.

Provably **not** moved today: app data (by design), Wi-Fi networks, BT link keys, launcher layout,
default-app roles (except transient SMS), most accessibility/keyboard/DND settings, live wallpapers,
oversized MMS parts (>8 MiB). On the **Play/lite** flavor: no SMS/MMS/call-log restore and no
carried-APK install (privilege surface stripped) — degoogle only.

_Hygiene aside: two stale doc comments would mislead a future gap scan — `ApkExportProvider.kt:24-26`
("no producer") and `AppBackupRelayProviders.kt:277-279` ("SAF not implemented"). Both kinds are wired
and shipping via `SenderViewModel.kt:262-263`. Worth a one-line fix._

---

## 2. The levers — ranked by value × feasibility ÷ effort

Privilege tiers: **T0** = no privilege · **BRIDGE** = shell-uid via the existing ADB bridge (same tier
as SMS role / `pm grant`) · **CEILING** = signature/system/root, out of reach.

### A. Ship-now, cheap, GOS→GOS parity breadth (no protocol/privilege change)

| Lever | What it closes | Tier | Effort | Notes |
|---|---|---|---|---|
| **Settings allowlist catch-up** — ✅ SHIPPED | 8 keys already classified **SAFE** in the source-of-truth doc but never compiled: accessibility magnification / captioning / large-pointer, keyboard text behavior (auto-replace/caps/punctuate), auto-time / auto-time-zone | T0 + BRIDGE (existing) | **S** | Pure catalog rows + validators, guardrail-test-bounded. No protocol change. Landed in `SettingsAllowlist.kt`. |
| **Per-contact custom ringtone** — ✅ SHIPPED (built-in only) | `ContactsContract…CUSTOM_RINGTONE` rides the existing contacts provider | T0 | **S** | Turned out NOT to be a plain vCard field: a raw `content://` URI never crosses devices (same rule as the excluded `ringtone` settings key). Carries the built-in's TITLE only, re-resolved via the existing `SoundStore.resolveBuiltin` machinery (PRP-04) — mirrors `SoundSelectionApplyProvider`. A user-uploaded custom ringtone FILE is explicitly not carried (would need the heavier per-contact `SOUND_FILE`-style file-transfer machinery — treated as a separate, bigger follow-up, not folded in here). Unverified on hardware: the post-insert `Contacts.CUSTOM_RINGTONE` update assumes raw-contact aggregation is synchronous for a fresh local (no-account) contact. |

### B. New capability, real northstar movement, spike-gated

| Lever | What it closes | Tier | Effort | Notes |
|---|---|---|---|---|
| **★ Seedvault restore trigger** | The **"all data"** pillar — kick off Seedvault's app-data restore in the same session | BRIDGE | **M** (spike first) | `bmgr restore <pkg>`/`<token>` via the bridge. `com.android.shell` (uid 2000, the bridge's identity) holds `android.permission.BACKUP` — same reachability class as the verified SMS-role op. portage never reads/produces the blob (scope-safe). **Gated on Seedvault being the active transport with a backup; unverified on GOS A16.** Biggest product-story upgrade: "portage hands off the baton" vs "portage can't touch data." |
| **Generic default-app roles** | Restore prior defaults (browser, dialer, home-app **selection**) for reinstalled, qualifying apps | BRIDGE | **S–M** | Generalizes the shipped SMS mechanism: `cmd role add-role-holder <ROLE> <pkg>`. `MANAGE_ROLE_HOLDERS` also sits in the Shell manifest. **No user-confirm dialog** on the shell path → a consent-UX decision (surface as opt-in like SMS). Qualification still applies (target must declare the role's components). |
| **Wi-Fi networks — partial** | Re-add **open** networks + list secured ones for one-tap manual reconnect | T0 (restore) | **M** | Restore side: `ACTION_WIFI_ADD_NETWORKS` (batch, one consent, shows as "saved"). **But the read side is the wall** — passphrases are root-only on GOS (your own 2026-06-12 spike), and saved-SSID enumeration is restricted for a normal app. So this is convenience, not credential parity. Rank below A/B; spike the source-side read before committing. |

### C. Structural ceiling — do NOT chase (say so honestly in the UX)

App **data** carried by portage (forbidden + infeasible — Seedvault's job; the trigger in §B is the
only lever) · **accounts / logins / sessions / passkeys** (security, impossible) · **Wi-Fi
passphrases** (root-only) · **Bluetooth link keys** (non-transferable; re-pair unavoidable) · **launcher
layout** (signature perm scoped to the launcher's own cert) · **DND rules/schedules & per-app
notification channels** (live in NotificationManager; no shell verb on GOS A16 — already declined) ·
**alarms, cross-browser bookmarks** (no portable API).

---

## 3. Per-priority reality

**Priority 1 — GOS → GOS.** With levers A + B done, coverage becomes: broader settings + all PII
providers + app set & perms + sounds/wallpaper/BT-roster + **Seedvault app-data handoff** + default-app
roles. That is close to "all apps + all settings + (data via handoff)." Irreducible residual = the §C
ceiling: re-login to accounts, re-enter Wi-Fi passwords, re-pair Bluetooth, redo home-screen layout,
re-set DND rules. Those become the honest "few unavoidable taps."

**Priority 2 — old Android → new GOS.** The levers degrade: Seedvault usually isn't the active
transport on stock Android (so the `bmgr` trigger has nothing to restore _from_ unless the user set
Seedvault up on the old phone — stock users won't have); app **availability mismatch** (Play/GMS-bound
apps may have no working GOS build); OEM **settings-schema divergence** (only AOSP-common keys map). Nets
out at: PII + GOS-installable APKs + AOSP-common settings. Materially less than priority 1 — set
expectations accordingly.

**Priority 3 — old Android → new Android.** Least differentiated; Smart Switch / Google restore have
privileged first-party access portage structurally can't match. portage's edge (GOS-centric, no-cloud,
private) evaporates here. **Recommend not investing** — chasing it dilutes the northstar rather than
advancing it.

---

## 4. Recommended path

1. **Two on-device spikes** (GOS A16, same discipline as the Wi-Fi/BT/notification spikes), because they
   convert the two highest-value uncertainties into go/no-go:
   - `bmgr restore <pkg>` (and `<token>`) reaches Seedvault's transport, doesn't silently no-op, and GOS
     hasn't stripped `BACKUP` from its Shell package. **`security-reviewer` scope-discipline pass +
     an ADR before any code** — "trigger a restore" sits close to the Seedvault line; the
     courier-not-absorber framing must be explicit from day one.
   - `cmd role add-role-holder android.app.role.BROWSER <pkg>` flips a third-party default and survives
     reboot → decides whether to add a generic `AdbBridge.setRoleHolder(role, pkg)` beside the SMS one.
2. **In parallel, ship lever A** (settings allowlist catch-up + contact ringtone) — no spike needed,
   pure parity breadth, self-contained.
3. **Then Fable on the last mile:** the guided one-flow (portage parity → Seedvault handoff → the
   short honest list of what needs a manual tap and why), and the microcopy for it.

## 5. Sources (feasibility pass)

Seedvault manifest & privapp allowlist (`BACKUP` = `signature|privileged`; `RESTORE_BACKUP`/`OPEN_SETTINGS`
intents = `system|signature`) · AOSP Shell `AndroidManifest.xml` (`com.android.shell` holds `BACKUP`,
`WRITE_SECURE_SETTINGS`, `MANAGE_ROLE_HOLDERS`) · `bmgr` docs (developer.android.com/tools/bmgr) ·
RoleManager / `cmd role` (PermissionController `Role.md`) · `ACTION_WIFI_ADD_NETWORKS`
(developer.android.com/guide/topics/connectivity/wifi-save-network-passpoint-config) · Launcher3
`LauncherProvider` custom signature perms · portage's own `SPIKE-RESULTS-2026-06-12.md` (Wi-Fi
passphrase root-only). Two items unverified in this pass and flagged: GOS's Launcher3 fork manifest
(no reason to expect divergence) and whether the existing contacts provider already round-trips
`CUSTOM_RINGTONE`.
