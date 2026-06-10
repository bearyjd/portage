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

## Results template

| Step | Command verdict | stdout / error signature | Pass? |
|---|---|---|---|
| fingerprint | | `ro.build.fingerprint = …` | n/a |
| V1 liveness | | | |
| V2 baseline | | | |
| V3 via Shizuku | | | |
| V4 self-grant | | | |
| V5 reboot persist | | | |
| V6 silent install | | | |
| V7 INTERNET revoke | | | |
| V7 OTHER_SENSORS | | | |
| V7 nav overlay | | | |
| V7 SMS role | | | |
| V8 secondary profile | | | |

## Verdict (fill in, then update ADR-001 Status)

- [ ] V2 failed → **Tier 1 settings NO-GO**, Tier 0 only.
- [ ] V2+V3 pass, V4/V5 pass → **grant architecture** (Shizuku one-shot for settings).
- [ ] V2+V3 pass, V4 or V5 fail → **live-shell architecture** (Shizuku in hot path).
- [ ] V6 prompts → batched install degrades to per-app confirm.
- [ ] V7 results: INTERNET ___ · SENSORS ___ · nav ___ · SMS-role ___

Paste the filled table into `ADR-001-privilege-feasibility.md` and flip its Status from
PROPOSED to ACCEPTED/REJECTED for the measured fingerprint.
