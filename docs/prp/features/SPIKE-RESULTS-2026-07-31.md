# Spike results — 2026-07-31 (GOS **Android 17**, Pixel 10 Pro Fold)

**#119 (Seedvault restore trigger): GO — verified end-to-end on hardware.**
**#121 (default-app role restore): GO on the flip; reboot-survival still OPEN.**

Answers the two spikes the northstar gap audit ranked highest (`docs/prp/features/northstar-gap-audit.md`
§2B/§4). Governed by ADR-008 for #119.

## Device under test

| | |
|---|---|
| Model | Pixel 10 Pro Fold (`rango`) |
| OS | **GrapheneOS, Android 17 (SDK 37)** — `app.grapheneos.*` packages present |
| Build | `google/rango/rango:17/CP2A.260705.006/2026071501:user/release-keys` |
| Security patch | 2026-07-05 |
| Seedvault | `com.stevesoltys.seedvault` **16-5.7**, installed, IS the selected transport |
| portage | `com.ventouxlabs.portage.recv` installed; `app-send` NOT installed |

> ⚠️ **Android 17 / SDK 37, not Android 16 / SDK 36.** Every "Established fact" in `CLAUDE.md` and
> every ADR-003 §7 gate is written against GOS **A16**. These results are **A17** evidence and must
> not be filed as A16. See #140.

---

## 1. #119 — Seedvault restore trigger: **GO**

### 1.1 Permission reachability

`dumpsys package com.android.shell` — GrapheneOS has **not** stripped `BACKUP`:

```
android.permission.BACKUP: granted=true
android.permission.MANAGE_ROLE_HOLDERS: granted=true
android.permission.BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS: granted=true
```

This was the single assumption the audit flagged as the cheap kill-shot for S1.1 (#116). It holds.
`com.android.shell` (uid 2000) is the bridge's own identity, so the capability is reachable from
`AdbBridge` by construction.

### 1.2 Backup accepted by Seedvault's transport

Starting state: Backup Manager **disabled**, `Ancestral: 0`, `Current: 0`, `Last backup pass: 0`.
After `bmgr enable true`, backing up portage's own package (chosen as the safest possible target —
our app, minimal data):

```
$ bmgr backupnow com.ventouxlabs.portage.recv
Running incremental backup for 1 requested packages.
Package @pm@ with result: Success
Package com.ventouxlabs.portage.recv with progress: 2048/1536
…
Package com.ventouxlabs.portage.recv with result: Success
Backup finished with result: Success
```

### 1.3 The restore — the actual question

```
$ bmgr list sets
  19e136a376b : Google Pixel 9 Pro Fold - Owner
  19fb65efd0c : Google Pixel 10 Pro Fold - Owner

$ bmgr restore 19fb65efd0c com.ventouxlabs.portage.recv
Scheduling restore: Google Pixel 10 Pro Fold - Owner
restoreStarting: 1 packages
onUpdate: 1 = com.ventouxlabs.portage.recv
restoreFinished: 0
done
```

**It reaches Seedvault's transport, actually runs, and reports a per-package verdict. It does not
silently no-op.** `restoreFinished: 0` is `TRANSPORT_OK`.

### 1.4 Findings that feed ADR-008 directly

1. **The `<token> <package>` form is mandatory.** The bare package form is rejected outright:
   > `The syntax 'restore <package>' is no longer supported, please use 'restore <token> <package>'.`

   This *validates* ADR-008 §1's choice of the package-scoped two-argument verb, and independently
   forecloses ADR-008 §3.6's prohibited whole-set form being reached by accident.

2. **Output grammar is now known** — ADR-008 §9 listed this as an open, spike-derived item. The
   parseable shape is:
   ```
   restoreStarting: <n> packages
   onUpdate: <index> = <package>
   restoreFinished: <code>      # 0 = TRANSPORT_OK
   done
   ```
   Per ADR-008 §6 this remains **untrusted text**: bound it, strip control characters, and treat any
   unparseable form as failure rather than success.

3. **Cross-device restore sets are visible.** A set from a *Pixel 9 Pro Fold* is listed alongside
   this device's own. That is precisely portage's use case (old phone → new phone), and it means
   token selection is a real UX decision, not an implementation detail — the user may have several
   sets and portage must not guess. ADR-008 does not currently say how the token is chosen; **this
   is a gap the implementation issue (#120) must close.**

4. **Restore ran with Backup Manager toggled on for the test.** Whether a restore can be triggered
   while the framework's backup scheduling is disabled was not isolated. #120 should not assume it
   can.

### 1.5 Still open for #119/#120

- Restoring a **third-party** app's data (only portage's own package was exercised).
- Restoring into an app installed **in the same session** (the sequencing question ADR-008 §9 raises,
  entangled with #86).
- Behaviour when Seedvault is installed but *not* the selected transport, and when no set exists —
  the honest-failure paths ADR-008 §5 requires. Not exercised because this device had both.

## 2. #121 — Default-app role restore: **GO on the flip**

`android.permission.MANAGE_ROLE_HOLDERS: granted=true` (same dump as §1.1).

Reversible flip, executed and reverted:

```
$ cmd role get-role-holders android.app.role.BROWSER   -> com.android.chrome
$ cmd role add-role-holder android.app.role.BROWSER app.vanadium.browser
$ cmd role get-role-holders android.app.role.BROWSER   -> app.vanadium.browser
$ cmd role add-role-holder android.app.role.BROWSER com.android.chrome
$ cmd role get-role-holders android.app.role.BROWSER   -> com.android.chrome
```

**The flip works in both directions, exit 0, with NO user-confirm dialog.** That silence is exactly
why the audit and #122 require this to be opt-in in portage's own UI — the platform will not ask on
portage's behalf.

Read-only holders at time of test: BROWSER `com.android.chrome` · DIALER `com.android.dialer` ·
SMS `com.android.messaging` · HOME `com.android.launcher3`.

**Still OPEN:** **reboot survival** — requires rebooting the owner's daily-driver device, not done
unprompted. Also untested: role *qualification* failure (a target app that does not declare the
role's components) and roles beyond BROWSER.

## 3. What this means for the epic

- **S1.1 (#116) is de-risked.** The mechanism is proven; what remains is scope, consent, and honest
  failure — which is what ADR-008 (#118) already specifies. #120 can proceed once ADR-008 is signed
  off, with the token-selection gap in §1.4.3 added to its scope.
- **S1.2 (#117) is de-risked** except for persistence.
- The bare-package rejection and the known output grammar both *tighten* ADR-008 rather than
  contradict it.

## 4. Device state — changed and restored

| Change | Status |
|---|---|
| Backup Manager enabled for the test | **restored to disabled** (original state) |
| BROWSER role flipped to Vanadium | **restored to `com.android.chrome`** |
| `adb_wifi_enabled` 0 → 1 (Wireless Debugging) | **left ON** — approved, and it is the bridge precondition. Turn off when done. |
| A Seedvault backup of `com.ventouxlabs.portage.recv` in set `19fb65efd0c` | **left in place** (harmless; portage's own data) |

Not run: `pm grant` / bridge bootstrap (needs an interactive pairing code from the Wireless
Debugging UI), `scripts/device-contract.sh` (destructive-but-self-cleaning), any reboot.

## 5. Reproduce

```sh
adb shell dumpsys package com.android.shell | grep -E 'BACKUP|MANAGE_ROLE_HOLDERS'
adb shell bmgr enable true
adb shell bmgr backupnow com.ventouxlabs.portage.recv
adb shell bmgr list sets
adb shell bmgr restore <token> com.ventouxlabs.portage.recv
adb shell bmgr enable false           # restore original state

adb shell cmd role get-role-holders android.app.role.BROWSER
adb shell cmd role add-role-holder android.app.role.BROWSER <pkg>
```

---

## 7. #123 — PRP-01 Wi-Fi feasibility (RESOLVED: NO-GO, confirming the existing decline)

PRP-01 was already **DECLINED 2026-06-13** ("not feasible on unrooted GOS; do not re-litigate")
against A16. #123 asked for the three `open-questions.md` boxes to be formally resolved with
evidence. They now are, on **A17** — the status is unchanged.

### 7.1 Read side — the NO-GO trigger fires

```
$ adb shell id
uid=2000(shell) gid=2000(shell) …

$ ls -ld /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml
ls: …: Permission denied
$ ls -ld /data/misc/wifi/WifiConfigStore.xml
ls: …: Permission denied
$ ls -ld /data/misc/apexdata/com.android.wifi/
ls: …: Permission denied            <- even the directory
$ head -c 120 /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml
head: …: Permission denied
```

Passphrases remain root-only. GOS is unrooted.

### 7.2 Sender read — closed on all three paths (measured, not assumed)

| Path | Status on A17 |
|---|---|
| Read `WifiConfigStore.xml` | Permission denied at shell uid (§7.1) |
| `cmd wifi list-networks` | Works at shell uid — but `app-send` must never hold shell uid (no-escalation CI assert) |
| App API privileged enumeration | Gated by `NETWORK_SETTINGS`, **`protectionLevel: signature`** — unreachable for a non-platform-signed app |

`ACCESS_WIFI_STATE` is `protectionLevel: normal`, but grants Wi-Fi *state*, not saved-network
enumeration. What `cmd wifi list-networks` returns is SSID + **security type** only — there is no
passphrase column and no get/export verb:

```
Network Id      SSID                         Security type
0            <redacted>                       wpa2-psk
1            <redacted>                       open
```

### 7.3 Restore side — path (b) works, and is receiver-only

```
$ cmd wifi add-network PORTAGE_SPIKE_DELETEME open
$ cmd wifi list-networks
4            PORTAGE_SPIKE_DELETEME           open
$ cmd wifi forget-network 4
Forget successful
```

Appears as **saved** immediately, **no per-network prompt**. Requires shell uid, so it is a
receiver-side path only. Path (a) `WifiNetworkSuggestion` was not tested — it needs app code and is
moot while the read side is NO-GO. Saved-network count before/after: 8 → 8; the throwaway entry was
removed and the device left as found.

### 7.4 Verdict

**NO-GO for credential parity**, confirming the 2026-06-13 decline on a newer OS. The receiver
*could* re-add networks it learned some other way, but with the sender unable to enumerate them,
that reduces to the user typing SSIDs by hand — no better than doing it in Settings.

Already-answered, recorded so it is not re-raised: under ADR-007 unification the sender binary
carries the bridge, making `cmd wifi list-networks` reachable at the source. That yields SSIDs and
security types, **never passphrases** — the exact trade PRP-01's decline already weighed and
rejected.
