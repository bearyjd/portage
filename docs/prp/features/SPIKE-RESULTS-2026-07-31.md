# Spike results — 2026-07-31 (read-only device probe, GOS **Android 17**)

**Status:** PARTIAL. The *reachability* half of #119 and #121 is answered on metal. The
*behavioural* half of both is NOT — it requires device-state changes that were deliberately not
made (see §5).

## Device under test

| | |
|---|---|
| Model | Pixel 10 Pro Fold (`rango`) |
| OS | **GrapheneOS, Android 17 (SDK 37)** — `app.grapheneos.*` packages present |
| Build | `google/rango/rango:17/CP2A.260705.006/2026071501:user/release-keys` |
| Security patch | 2026-07-05 |
| Seedvault | `com.stevesoltys.seedvault` **16-5.7**, installed |
| portage | `com.ventouxlabs.portage.recv` installed; `app-send` NOT installed |
| Wireless debugging | `adb_wifi_enabled = 0` (off) |

> ⚠️ **This is Android 17 / SDK 37, not Android 16 / SDK 36.** Every "Established fact" in
> `CLAUDE.md` and every ADR-003 §7 gate is written against **GOS A16**. Results below are valid for
> **A17** and must not be silently filed as A16 evidence. See §4 — this also undercuts the stated
> rationale for the current `compileSdk = 36` pin.

---

## 1. #119 — Seedvault restore trigger: is `bmgr restore` reachable via the bridge?

**Reachability: GO.** GrapheneOS has **not** stripped `BACKUP` from its Shell package on A17.

`dumpsys package com.android.shell`:

```
android.permission.BACKUP: granted=true
android.permission.MANAGE_ROLE_HOLDERS: granted=true
android.permission.BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS: granted=true
```

This was the single assumption the gap audit flagged as unverified and as the thing that would
"kill S1.1 cheaply" if false. It is true. `com.android.shell` is the bridge's own identity (uid
2000), so the capability is reachable from `AdbBridge` by construction.

`bmgr list transports` — Seedvault **is** the selected transport (`*`):

```
    com.android.localtransport/.LocalTransport
  * com.stevesoltys.seedvault.transport.ConfigurableBackupTransport
```

**NOT established (blocks the rest of #119):**

- `bmgr enabled` reports **"Backup Manager currently disabled"**, while
  `settings get secure backup_enabled` reports `1`. These disagree; the authoritative signal for
  whether a restore can run is the former. Either way, backup is not currently operating.
- **No completed backup set exists to restore from**, so `bmgr restore` was not invoked at all.
- Therefore: whether `bmgr restore <token> <pkg>` actually reaches Seedvault's transport, whether it
  silently no-ops, and its output grammar (which ADR-008 §9 flags as spike-derived and untrusted)
  all remain **OPEN**.

**To finish #119** the owner must enable Backup Manager and complete a real Seedvault backup of
real data on this device. That is a decision about the owner's own data and was not taken
unilaterally.

## 2. #121 — Default-app role restore: is `cmd role` reachable?

**Reachability: GO.** `android.permission.MANAGE_ROLE_HOLDERS: granted=true` on the Shell package
(same dump as above).

Current holders read cleanly via `cmd role get-role-holders` (read-only):

| Role | Holder |
|---|---|
| BROWSER | `com.android.chrome` |
| DIALER | `com.android.dialer` |
| SMS | `com.android.messaging` |
| HOME | `com.android.launcher3` |

**NOT established:** whether `cmd role add-role-holder` actually *flips* a default and whether the
change **survives reboot** — the two questions #121 exists to answer. Both require writing to the
device's role state (changing the user's real default browser) and rebooting it. Not done without
an explicit go-ahead.

## 3. What this means for the epic

- The cheap kill-shot for **S1.1** (#116) did not land — the permission is there. The story stays
  alive, and ADR-008's boundary applies.
- **S1.2** (#117) likewise clears its permission precondition.
- Neither story is *proven*; both now hinge on behaviour, not reachability.

## 4. Finding: the A16 pin no longer matches the hardware

Not a spike question, but discovered by the same probe and material to open work:

- `gradle/libs.versions.toml` pins `compileSdk = 36` / `targetSdk = 36`, documented as "Android 16 =
  verified target device (ADR-001)".
- PR **#113** holds `lifecycle` at 2.10.0 specifically because 2.11.0's AAR metadata requires
  compileSdk 37+, justified as "this repo pins compileSdk=36 to the verified GOS target device".
- **The verified target device now runs SDK 37.** The stated reason for the pin no longer describes
  reality.

This does not make the pin wrong — minSdk/targetSdk policy is a deliberate call, and bumping
`targetSdk` has behavioural consequences that need their own review. But the *justification*
recorded in #113 and in `CLAUDE.md` is now stale, and the whole ADR-003 §7 gate set is written
against an OS version the test hardware has moved past. Worth an explicit decision rather than
drift.

## 5. Discipline note — what was deliberately NOT done

All probes above are read-only. The following were available and were **not** run, because each
changes the owner's device state or data:

- `cmd role add-role-holder …` (would change the real default browser)
- enabling Backup Manager / running a Seedvault backup (touches real user data)
- `pm grant` / any bridge bootstrap (Wireless Debugging is off; enabling it is a user action)
- `scripts/device-contract.sh` (destructive-but-self-cleaning; takes the SMS role)

## 6. Reproduce

```sh
adb shell dumpsys package com.android.shell | grep -E 'BACKUP|MANAGE_ROLE_HOLDERS'
adb shell bmgr enabled
adb shell bmgr list transports
adb shell cmd role get-role-holders android.app.role.BROWSER
adb shell settings get global adb_wifi_enabled
```
