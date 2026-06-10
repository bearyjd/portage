# Verification runbook — run BEFORE building portage UI

Executable companion to `ADR-001-privilege-feasibility.md` §3. This is the gate: its
results decide the `privileged` module's architecture and which features are in scope.
Run it on a **real, current-release GrapheneOS Pixel**. Nothing here is destructive, but
each step has a cleanup line where state changes.

## Setup (once)

1. Settings → About phone → tap build number 7× → Developer options on.
2. Developer options → **Wireless debugging** on. Pair a terminal or PC.
3. Install **Shizuku ≥ 13.6**, start it via Wireless debugging, confirm it shows
   *running, shell (uid 2000)*.
4. Pick your shell:
   - **`rish`** inside Termux (Shizuku's bundled shell), or
   - **PC `adb shell`** for the V2 baseline (you need this for V2 regardless).
5. Record the build: `getprop ro.build.fingerprint` — a verdict is only valid for this
   fingerprint.

> Fill in the results table at the bottom as you go. Capture exact stdout/stderr.

## V1 — Shizuku liveness
Shizuku app reports running with shell uid 2000. **Fail → fix environment, stop.**

## V2 — Baseline shell settings write (PC adb; isolates Shizuku out)
```sh
adb shell settings put secure ui_night_mode 2
adb shell settings get  secure ui_night_mode        # expect: 2
adb shell settings put global animator_duration_scale 0.5
adb shell settings get  global animator_duration_scale  # expect: 0.5
adb shell settings delete global animator_duration_scale # cleanup
```
- **Pass:** writes succeed, `get` echoes the value.
- **Fail signature:** `SecurityException: … requires WRITE_SECURE_SETTINGS`
  → **Tier 1 settings NO-GO.** Ship Tier 0 only. Stop here.

## V3 — Same writes through Shizuku
```sh
rish -c 'settings put secure ui_night_mode 1'
rish -c 'settings get  secure ui_night_mode'         # expect: 1
```
- **Pass:** identical to V2.
- **Fail (with V2 passing):** Shizuku integration bug, not a platform verdict.

## V4 — The self-grant (decides grant vs. live-shell architecture)
Use any throwaway app that declares `WRITE_SECURE_SETTINGS` in its manifest (the
`privileged` module already does — you can sideload a debug `app-recv` once it builds,
or a 30-line scratch app). Then:
```sh
rish -c 'pm grant <scratch.pkg> android.permission.WRITE_SECURE_SETTINGS'
# In the app process: Settings.Secure.putInt(resolver, "ui_night_mode", 2)
```
- **Pass:** grant is silent; the in-process write succeeds.
- **Fail signature:** `SecurityException: … not a changeable permission type`
  → GOS stripped the `development` flag → **live-shell architecture** (settings writes
  stay routed through Shizuku `exec`).

## V5 — Reboot persistence (confirms the grant architecture)
Reboot. **Do NOT restart Shizuku.** Re-run the in-process write from V4.
- **Pass:** write still succeeds → grant architecture confirmed (Shizuku one-shot only).
- **Fail:** → live-shell architecture.

## V6 — Silent install
```sh
# stage a small test apk at /data/local/tmp/test.apk first
rish -c 'pm install-create -i <scratch.pkg> --user 0'   # → Session ID N
rish -c 'pm install-write <N> base /data/local/tmp/test.apk'
rish -c 'pm install-commit <N>'                          # expect: Success, no prompt
```
- **Pass:** `Success`, no on-screen confirmation → batched reinstall available.
- **Prompts/fails:** batched install degrades to per-app `PackageInstaller` confirm.
  **Not a Tier 1 kill** — feature downgrade only.

## V7 — GOS specials (each gates exactly one optional feature)
```sh
rish -c 'pm revoke <victim.pkg> android.permission.INTERNET'        # Network toggle parity?
rish -c 'pm grant  <victim.pkg> android.permission.OTHER_SENSORS'   # Sensors toggle parity?
rish -c 'cmd overlay enable-exclusive --category android.theme.customization.navigation com.android.internal.systemui.navbar.gestural'  # nav mode switch?
rish -c 'cmd role add-role-holder android.app.role.SMS <pkg>'       # silent SMS role move?
# Re-grant INTERNET / restore SMS role afterward as cleanup.
```
Record each independently; failures just drop the matching optional feature.

## V8 — Profile scope
Repeat V4 with `--user <secondary>`. Confirms v1 is owner-profile-only (expected).

---

## Results (measured 2026-06-10)

Device: **Pixel 9 Pro XL ("comet")**, fingerprint
`google/comet/comet:16/BP4A.260205.002/2026060600`, **GrapheneOS Android 16 (SDK 36)**.
Method: driven over USB `adb` (shell uid 2000 — the exact privilege Shizuku brokers), so
V2–V8 were exercised at the real privilege level without Shizuku installed.

| Step | Verdict | Evidence |
|---|---|---|
| fingerprint | n/a | `google/comet/comet:16/BP4A.260205.002/2026060600`, Android 16 / SDK 36 |
| V1 liveness | N/A | Shizuku not installed; privilege exercised directly via adb shell uid 2000 |
| V2 baseline | ✅ PASS | `settings put secure ui_night_mode 2` → get `2`; `global animator_duration_scale 0.5` → get `0.5`; no SecurityException; restored |
| V3 via Shizuku | DEFERRED | Equivalent to V2 (identical shell uid). Confirm once Shizuku is installed; expected pass |
| V4 self-grant | ✅ PASS | `pm grant cc.grepon.portage.recv WRITE_SECURE_SETTINGS` silent (exit 0); dump `granted=true` (userId 0). `development` flag intact |
| V5 reboot persist | ✅ PASS | After reboot, no re-grant, no Shizuku → `granted=true` persists → **grant architecture** |
| V6 silent install | ✅ PASS | `pm install-create/-write/-commit` → `Success`, no on-screen prompt → batched reinstall available |
| V7 INTERNET revoke | ✅ PASS | `pm revoke` → `granted=false`; `pm grant` restore → `granted=true`. GOS Network-toggle parity reachable via `pm` |
| V7 OTHER_SENSORS | ⚠️ TENTATIVE | `pm grant` exit 0 but app doesn't declare it — re-test with a manifest-declared sensor app before trusting |
| V7 nav overlay | ✅ PASS | `cmd overlay enable-exclusive … threebutton` switched; restored to `gestural`. Needs LIVE shell at call time |
| V7 SMS role | ⚠️ GATED | `cmd role add-role-holder SMS <our app>` failed (RuntimeException) — app is not SMS-role-eligible. Mechanism OK; app must declare SMS components (DEVILS_ADVOCATE Q4) |
| V8 secondary profile | ✅ CONFIRMED | Grant `granted=true` only for userId 0; `false` for profiles 10/11 → owner-profile-only scoping correct |

## Verdict

- [x] **V2 pass + V4/V5 pass → grant architecture.** Shizuku is a one-shot at Tier 1
  unlock; settings writes leave the bridge afterward and survive reboot.
- [x] V6 silent → batched reinstall in scope (no per-app-confirm degradation).
- V7 results: INTERNET **reachable** · SENSORS **tentative** · nav **reachable (live shell)** ·
  SMS-role **needs SMS-eligible app**.
- Valid only for fingerprint `…2026060600` (Android 16). Re-run on GOS version bumps.
