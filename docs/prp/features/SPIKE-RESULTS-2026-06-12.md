# On-device spike results (2026-06-12, Pixel 10 Pro Fold `rango`, GOS A16, shell uid 2000)

Ran the feasibility spikes the PRPs gated on, as shell uid 2000 — the exact uid portage's
`:adb-bridge` obtains. These results OVERRIDE the optimistic feasibility assumptions in
PRP-01/05/07; build to these verdicts, not the original PRP guesses.

## PRP-01 Wi-Fi — passphrase transfer INFEASIBLE (major scope cut) ⚠️
- `ls`/`cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml` → **Permission denied** for
  shell uid. The privileged-file-read approach is DEAD (would need root, which portage doesn't have).
- `cmd wifi list-networks` → shows `Network Id | SSID | Security type` only. **No passphrase, ever.**
- `cmd wifi` has `add-network`/`connect-network <ssid> sec [passphrase]` (passphrase as INPUT) but
  **no get/export/secret verb** — you cannot read an existing passphrase out.
- **Verdict:** saved Wi-Fi *passphrases* cannot be exfiltrated on GOS without root. REVISED feature
  scope = transfer the network LIST (SSID + security type + hidden flag) + open/OWE (no-secret)
  networks, and on restore use `cmd wifi add-network` with a USER-RE-ENTERED passphrase (guided
  re-add), not a silent secret transfer. Value drops from "top gap" to "modest convenience."
  Reframe PRP-01 before building; do NOT implement a WifiConfigStore.xml reader.

## PRP-07 Bluetooth — list via dumpsys only; keys + auto-repair out (degrade) ⚠️
- `bt_config.conf` → **Permission denied** for shell uid (file approach dead).
- `dumpsys bluetooth_manager` DOES expose a "Bonded devices:" section (0 on this test unit — nothing
  currently paired), so the paired-device LIST (name/MAC/class) is readable via dumpsys, no file/root.
- `cmd bluetooth` → "Can't find service" on GOS; `cmd bluetooth_manager` only enable/disable. No
  programmatic re-pair trigger. Link keys remain controller-bound/non-transferable.
- **Verdict:** PRP-07 = read the bonded list from `dumpsys bluetooth_manager` + a receiver-side
  manual re-pair checklist. No key transfer, no auto-pair. Low value; the sender-side-privilege
  tension (ADR-003 keeps the bridge out of portage-send) still applies to even the dumpsys read.

## PRP-05 Notifications — per-channel parity INFEASIBLE via shell (decline/reduce) ⚠️
- `cmd notification` SUBCMDs: listener/assistant mgmt, `set_dnd`, `allow_dnd/disallow_dnd PACKAGE`,
  `set_bubbles`/`set_bubbles_channel`, `post`. **No per-channel importance/block verb.**
- `cmd appops ... POST_NOTIFICATIONS` → "Unknown operation string" (op name differs; needs more
  digging, but channel-level state is the blocker regardless).
- **Verdict:** per-app/per-channel notification importance/block is NOT settable via the shell
  surface portage has. Reduce PRP-05 to at most DND-per-app (`allow_dnd`/`disallow_dnd`) +
  bubbles, OR decline. Do NOT promise channel parity.

## Unaffected by spikes (build as written)
- **PRP-02 Wallpaper** — Tier 0 `WallpaperManager`, no shell dependency. ✅ Highest-confidence build.
- **PRP-04 Sound selection** — Tier 0 `Settings.System`/`RingtoneManager`. ✅ (URI-remap risk is app-level, not a shell-feasibility blocker.)
- **PRP-03 Secure-settings expansion** — Tier 1 allowlisted writes via existing grant. ✅ (scope already trimmed to zen_mode + device_name + a settings-key spike.)
- **PRP-06 App-backup relay** — Tier 0 orchestration/file transfer. ✅ (scope-discipline review is the gate, not feasibility.)

## PRP-03 secure-settings — key probe (2026-06-12, rango) ⚠️ scope collapses
Probed real keys via `settings list global/secure/system`:
- `device_name` (GLOBAL, = "Pixel 10 Pro Fold") — the ONLY solid survivor. User-chosen device/BT
  display name; SAFE to transfer with a string validator. Worth one allowlist row.
- `zen_mode` (GLOBAL, =2) — this is the LIVE DND state, not config; transferring "currently
  silenced" is harmful, and the real DND rules are `AutomaticZenRule` objects (not a Settings key).
  DECLINE. `zen_mode_config_etag` is an opaque etag, not portable.
- Emergency owner info / Bluetooth-discoverability timeout — NOT present as writable settings on
  GOS (CalyxOS named them, but they're not plain keys here). OUT.
- Panic config / USB-peripheral hardening — already DEVICE_SPECIFIC-excluded (PRP-03 found this).
- **Verdict:** PRP-03 reduces to a single `device_name` allowlist row — low value. Deprioritized
  below PRP-06; do `device_name` as a quick follow-up (it rides the existing `ItemKind.SETTINGS`,
  no new wire). The guardrail invariant + a string validator are the only controls needed.

## FINAL disposition (2026-06-13, after spikes + user decisions)
- **PRP-02 Wallpaper — SHIPPED** (#34).
- **PRP-04 Sound selection — SHIPPED** (#35, Phase 1).
- **PRP-03 → `device_name` — SHIPPED** (#36); rest of PRP-03 declined per key probe above.
- **PRP-07 Bluetooth — IN PROGRESS** via the PUBLIC `BluetoothAdapter.getBondedDevices()` +
  `BLUETOOTH_CONNECT` runtime perm (NOT the privileged `bt_config.conf`/`dumpsys` path this doc first
  assumed — that's denied to shell anyway). Sender-feasible, no bridge, no escalation. List + re-pair
  checklist; no key transfer (keys are controller-bound).
- **PRP-01 Wi-Fi — DECLINED** (user, 2026-06-13). Definitive: passwords are unreadable at **shell uid**
  (the max portage's bridge reaches) — `WifiConfigStore.xml` denied, `cmd wifi`/`dumpsys wifi` never
  expose the PSK; only **root** can, and GOS is unrooted. Even a sender-side bridge escalation yields
  only an SSID list, not passwords — not worth breaking ADR-003. Seedvault's domain. See PRP-01.
- **PRP-05 Notifications — DECLINED** (user, 2026-06-13). No per-channel shell verb; channels are
  app-owned. See PRP-05.
- **PRP-06 App-backup relay — HELD** for a product/scope decision (relay app-owned encrypted backups?
  + raises the 64 MiB cap). Not yet built.
