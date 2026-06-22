# ADR-001 — Privilege Feasibility: Shizuku as the Tier 1 Bridge

Status: **ACCEPTED — grant architecture** (verified 2026-06-10 on Pixel 9 Pro XL,
GrapheneOS Android 16, fingerprint `google/comet/comet:16/BP4A.260205.002/2026060600`).
V2/V4/V5/V6/V7-INTERNET/V7-nav/V8 all passed; see `VERIFICATION-RUNBOOK.md` for the filled
results table. Re-validate on GOS version bumps.
Decision owner: JD. Drafted by planning agent, 2026-06-10.

## 1. Decision (and one architectural correction to the PRP)

Shizuku is confirmed as the Tier 1 privilege bridge, **but not as a live dependency for
settings writes**. The PRP frames Tier 1 as "settings sync via Shizuku" with the reboot
caveat hanging over every operation. That framing misses a property of the permission
itself:

> `WRITE_SECURE_SETTINGS` has protection level `signature|privileged|development`.
> The `development` flag means the **shell uid (2000) can `pm grant` it to any app that
> declares it in its manifest**, and the grant **persists across reboots** and across
> Shizuku being dead. It is lost only on app uninstall/reinstall.

So the correct Tier 1 architecture is two-phase:

- **Phase A (one-shot, Shizuku live):** `portage-recv` declares `WRITE_SECURE_SETTINGS`
  in its manifest. At Tier 1 unlock, it uses Shizuku to run
  `pm grant com.ventouxlabs.portage.recv android.permission.WRITE_SECURE_SETTINGS` on itself.
- **Phase B (forever after, Shizuku NOT required):** the app writes `Settings.Secure`
  and `Settings.Global` directly through the normal `Settings.*.putString/putInt` API.

Shizuku must be *live* only for operations that genuinely require shell uid at call time:

| Operation | Needs live Shizuku? |
|---|---|
| `Settings.Secure` / `Global` writes | No, after one-shot grant |
| `Settings.System` writes | **No Shizuku at all** — `WRITE_SETTINGS` is a user-grantable special app access ("Modify system settings"), i.e. **Tier 0** |
| Batched APK install (`pm install-create/-write/-commit`) | Yes |
| Runtime-permission parity (`pm grant/revoke <pkg> <perm>`) | Yes |
| Nav-mode switch (`cmd overlay enable-exclusive …`) | Yes |
| SMS role restore (`cmd role add-role-holder android.app.role.SMS …`) | Yes |

Consequence: the "restart Shizuku after every reboot" caveat shrinks from "Tier 1 is
fragile" to "the *install/role* sub-steps need Shizuku alive during the transfer session
itself" — which is exactly when the user is actively driving both phones anyway. Also
note: Shizuku ≥ 13.6.0 (July 2025) can auto-start without root on Android 13+ when on a
trusted Wi-Fi network, which further softens the caveat; do not depend on it, but
mention it in the in-app setup guide.

A second correction: because `Settings.System` is reachable at Tier 0, a meaningful slice
of the "settings sync" feature (screen timeout, rotation, font scale, haptics, sounds)
does **not** belong behind the Shizuku gate. The PRP's tier table should be amended; the
settings catalog (see `settings_allowlist.md`) carries a per-key "reach" column.

## 2. Known GrapheneOS-specific gotchas

1. **Wireless debugging resets on reboot.** GOS (like AOSP) turns the Wireless Debugging
   toggle off across reboots, so post-reboot recovery is: enable toggle → start Shizuku.
   No re-*pairing* is needed (pairing keys persist); it's two taps, not the full flow.
   Surface this exactly in the in-app guide.
2. **GOS auto-reboot.** GOS reboots the device after a configurable idle-locked window
   (default 18 h). A phone prepared "the night before" will have a dead Shizuku in the
   morning. `portage` must *detect* Shizuku liveness at the moment of use and guide, never
   assume a previously-seen binder is still valid.
3. **USB-C port policy.** GOS defaults new installs to "Charging-only when locked".
   Irrelevant for wireless-debugging-started Shizuku; relevant only if the user tries
   USB ADB. Prefer documenting the wireless path exclusively.
4. **Per-profile semantics.** `settings` operates per-user (`--user N`); Shizuku's
   manager app runs in the owner profile and binding from secondary profiles is not a
   supported v1 target. **Scope v1 to the owner profile** and state it in the README.
5. **GOS per-app toggles are NOT settings keys.** Network/Sensors toggles are implemented
   as the `INTERNET` / `OTHER_SENSORS` permissions in GOS's extended permission model;
   exploit-protection toggles (hardened_malloc opt-out, MTE, DCL, WebView JIT) live in
   GOS-private package state with no stable shell interface. The former *may* be
   reachable via `pm grant/revoke` (verify, V7 below); the latter must be dropped from
   scope, not "best-effort".
6. **GOS updates are frequent.** Record the GOS build fingerprint
   (`getprop ro.build.fingerprint`) alongside every verification run in this ADR; a
   verdict is only valid for the fingerprint it was measured on.

## 3. Verification procedure (run on a real device BEFORE any UI work)

Prereqs: current-release GOS Pixel; Developer options on; Wireless debugging on;
Shizuku ≥ 13.6 installed, paired, started; `rish` (Shizuku's shell) exported to a
terminal app, or a 30-line scratch app using `Shizuku.newProcess`. A PC with `adb` for
the baseline steps.

Record for every step: command, stdout/stderr, exit code, GOS fingerprint.

**V1 — Shizuku liveness.** Shizuku app shows "running, shell (uid 2000)".
Failure: pairing/start fails ⇒ environment problem, fix before proceeding.

**V2 — Baseline via PC adb (eliminates Shizuku as a variable).**
```
adb shell settings put secure ui_night_mode 2 && adb shell settings get secure ui_night_mode
adb shell settings put global animator_duration_scale 0.5
adb shell settings delete global animator_duration_scale   # cleanup
```
Expected: writes succeed, `get` echoes the value.
Failure signature: `java.lang.SecurityException: Permission denial … requires
android.permission.WRITE_SECURE_SETTINGS` ⇒ GOS has hardened shell settings access
beyond AOSP — **Tier 1 settings NO-GO**, full stop (see §4).

**V3 — Same writes through Shizuku** (via `rish -c 'settings put secure ui_night_mode 1'`
or `Shizuku.newProcess(["settings", …])`). Expected: identical to V2.
Failure with V2 passing ⇒ Shizuku integration bug, not a platform verdict.

**V4 — The self-grant.** Scratch app declares `WRITE_SECURE_SETTINGS` in its manifest.
```
rish -c 'pm grant <scratch.pkg> android.permission.WRITE_SECURE_SETTINGS'
```
then, in-process: `Settings.Secure.putInt(resolver, "ui_night_mode", 2)`.
Expected: silent grant; direct write succeeds.
Failure signature: `SecurityException: … is not a changeable permission type` ⇒ GOS
stripped the `development` flag ⇒ fall back to **live-shell architecture** (every write
goes through `Shizuku.newProcess("settings put …")`). Tier 1 still GO if V3 passed,
but the privileged module keeps Shizuku in the hot path.

**V5 — Reboot persistence.** Reboot. Do NOT restart Shizuku. Scratch app writes a Secure
key. Expected: success ⇒ grant architecture confirmed. Failure ⇒ live-shell architecture.

**V6 — Silent install.** Through `rish`:
```
pm install-create -i <scratch.pkg> --user 0        # → session id N
pm install-write N base.apk /data/local/tmp/test.apk
pm install-commit N
```
Expected: `Success`, **no on-screen confirmation**. If GOS prompts or rejects shell
installs ⇒ batched reinstall degrades to the Tier 0 `PackageInstaller` per-app-confirm
flow. This is a feature downgrade, **not** a Tier 1 kill.

**V7 — GOS specials (each independent, each optional scope).**
```
pm revoke <victim.pkg> android.permission.INTERNET        # Network toggle parity?
pm grant  <victim.pkg> android.permission.OTHER_SENSORS   # Sensors toggle parity?
cmd overlay enable-exclusive --category android.theme.customization.navigation \
    com.android.internal.systemui.navbar.gestural          # nav mode actually switches?
cmd role add-role-holder android.app.role.SMS <pkg>        # silent SMS role move?
```
Record verdicts; each gates exactly one optional feature in the catalog.

**V8 — Profile check.** Repeat V4 with `--user <secondary>`; expected to work from shell
but the *app-side* write only affects the profile the app runs in. Confirms the
owner-profile-only scoping.

## 4. Go / no-go thresholds

| Result | Verdict |
|---|---|
| V2 fails | **Tier 1 settings NO-GO.** Ship Tier 0 only; re-evaluate on next GOS release. (Probability: low — would contradict AOSP shell behavior with no GOS release-note evidence.) |
| V2+V3 pass, V4 or V5 fail | Tier 1 GO, **live-shell architecture** (Shizuku in hot path; reboot caveat applies to all Tier 1 ops). |
| V2–V5 pass | Tier 1 GO, **grant architecture** (preferred; Shizuku one-shot for settings). |
| V6 prompts/fails | Batched install degrades to per-app confirm. Not a kill. |
| V7 items fail | Drop the corresponding optional feature; no other impact. |

## 5. Confidence + open questions

- V2/V3/V4/V5 pass on current GOS: **~85%**. The shell-grant path is plain AOSP, widely
  exercised by apps in this class, and GOS documents no hardening of it. What would
  change this: any GOS release note or forum statement restricting shell `settings`/
  `pm grant`; the verification run itself.
- V6 silent install with no prompt: **~60%**. GOS has its own app-installation hardening
  surface; an interactive confirm here would be in character.
- V7 INTERNET/OTHER_SENSORS parity via `pm grant/revoke`: **~40%**. GOS's toggles are
  *presented* as permissions but the enforcement plumbing is custom; treat as a bonus.
- Open: exact behavior when `WRITE_SECURE_SETTINGS` is granted but a *specific key* is
  additionally protected (a few keys are system-uid-gated regardless). The catalog
  handles this per-key; the audit run (VERIFY_FIRST #2) settles it.
