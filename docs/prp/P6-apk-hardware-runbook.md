# P6 — APK transfer: on-device hardware runbook & silent-install design

**Status:** OPEN — the hardware-verification phase for the APK-transfer keystone (ADR-006).
Prereq: ADR-006 Phases 1–4 landed (codec, sender UI, receiver gate, apply provider + Tier-0
install) — all JVM-verified, both APKs assemble. This doc is the runbook for the single-GOS-device
session and the design for the **deferred silent (stdin-streaming) install** that lands here.

The user has ONE GrapheneOS device, so the **cross-device pairing leg (ADR-003 §7 / AC-X) and
split target-compatibility (AC-15)** stay OPEN regardless of what this session proves — they need
two devices of different ABI/density class. Record `ro.build.fingerprint` with every run
(ADR-001 §2.6: a verdict is only valid for the fingerprint it was measured on).

---

## Part 1 — Tier-0 path verification (functional today; just unproven on metal)

> **VERIFIED 2026-06-21.** Cross-device (Pixel 9 Pro Fold `comet` → Pixel 10 Pro Fold `rango`,
> receiver fp `google/rango/rango:16/BP4A.260205.001/2026061601`, Android 16, patch 2026-06-01).
> Single-APK (Molly, not on Play Store) AND multi-split (Termux, `base + arm64_v8a + en + es +
> xxhdpi` all staged) both installed via the carried Tier-0 chain — `CONFIRM_INSTALL` launched from
> the receiver's uid, session applied, `installerPackageName=cc.grepon.portage.recv` (NOT vending),
> apps launch (byte integrity). AC-18 already-installed quiet-skip confirmed (AntennaPod, equal
> versionCode). Evidence recorded in ADR-006 §Follow-ups. Edge cases (reset hygiene, commit-retry)
> and the silent stdin path (Part 2) + 2-device legs remain.

The Tier-0 `PackageInstaller` confirm path runs entirely as the receiver app (no shell uid), so it
should work on-device now. Verify it end-to-end.

### Setup
1. `./gradlew :app-send:assembleDebug :app-recv:assembleDebug` (JDK 17 + Android SDK 36).
2. Install `portage-send` on the OLD phone, `portage-recv` on the NEW phone (or both on the one
   device for a self-transfer smoke).
3. Record the GOS build: `adb shell getprop ro.build.fingerprint` (and `ro.build.version.security_patch`).

### AC-14 — Tier-0 install (no bridge needed)
1. Sender: open portage-send, grant the read permissions, expand **Apps to carry**, select one
   small **single-APK** app and one **multi-split** app (e.g. a store app with config splits).
   Confirm the running total + count reflect the selection.
2. Show the QR; scan on the receiver; on the checklist enable the **App List → APK** items; "Bring it over".
3. Watch the transfer; on the Done screen the **INSTALL · N APPS** rows appear.
4. Tap each INSTALL row → the system "Install this app?" dialog fires → confirm.
5. **Verify per app:** `adb shell pm path <pkg>` lists `base.apk` **and every** `split_config.*.apk`
   (this is the multi-split staging VERIFY_FIRST item in CLAUDE.md — a base-only result is a failure).
   Launch the app; confirm it runs.
6. **Capture:** fingerprint, screenshots of the Done rows + a confirm dialog, the `pm path` output.

### Edge cases to exercise
- **Already-installed:** carry an app the target already has at an equal/higher versionCode → it must
  appear as a *quiet skipped* note, NOT an install attempt (AC-18).
- **Reset hygiene:** start an APK transfer, reach the Done screen, tap **Done** WITHOUT installing →
  reopen portage-recv → confirm no orphaned `PackageInstaller` sessions linger
  (`adb shell pm get-install-state` / `dumpsys package installs`); the launch sweep + reset abandon
  should have cleared them.
- **commit feedback:** if a sealed session is reaped before you tap, the retry snackbar should show
  ("Couldn't start install — please retry"), not a silent no-op.
- **Incompatible (OPEN, needs a 2nd device of a different ABI):** carry an app whose source splits
  don't match the target ABI → it must route to the "incompatible — get it from the store" row, never
  a broken install (AC-15). Cannot be proven on one device (source == target).

---

## Part 2 — Silent stdin-streaming install (the deferred build — do this WITH the device)

> **VERIFIED 2026-06-21.** Receiver = Pixel 10 Pro Fold `rango`, fp
> `google/rango/rango:16/BP4A.260205.001/2026061601`. ntfy (multi-split) installed **silently via
> the `exec:` bridge** — no per-app confirm dialog, no tap, `installerPackageName=null` (shell-uid
> `pm`, not Tier-0/store), app launches (byte integrity). Stale-positive → Tier-0 fallback found a
> hang bug (libadb `connectTls`→NsdManager mDNS ignores thread interruption) → fixed in `c9ef2a2`
> (PR #70): `LocalAdbBridge.connect()` gates on `adb_wifi_enabled` before connecting + a 90s
> `AdbApkInstaller` backstop → WD-off now degrades to the Tier-0 `CONFIRM_INSTALL` dialog in ~1s, no
> freeze. ADR-003 §7 (cross-device pair→connect→probe) also closed. Evidence in ADR-006 §Follow-ups.

### Why it was deferred (the constraint)
`pm install-write` runs as **shell uid 2000**. The receiver stages split APKs in app-private storage,
which shell uid **cannot read** (SELinux + 0700 dirs, not just file mode). A shared on-disk path is not
viable: the app can't write shell-owned `/data/local/tmp`, and external storage exposes the APK bytes
to every app (forbidden). The correct design pipes the bytes over the adb stream's **stdin** — no
shared file ever exists.

### The design

**a) `AdbDeviceGate` — a binary-safe stdin-streaming exec.**
Today `gate.exec(command): String` is command-in/output-out (`LocalAdbBridge.shellLocked`, the wrapped
`{ cmd; } 2>&1; echo SENTINEL$?` sentinel pattern over the libadb **`shell:`** service). Add a streaming
variant:
```
execWithStdin(command: String, input: InputStream, size: Long): ShellResult
```
that opens the adb stream, sends the command, writes `size` bytes from `input` to the stream's output,
half-closes stdin, then reads the result.

> **KEY RISK — use the `exec:` service, not `shell:`.** Binary stdin over the legacy `shell:` service can
> be corrupted by pty/line-ending translation, and `shell:` has no exit code (the sentinel hack works for
> text but is fragile with a binary write phase). The adb **`exec:`** service is the binary-safe,
> no-pty channel intended for `pm install-write … -` streaming. Investigate libadb-android's `AdbStream`
> write API + whether it exposes `exec:`; if only `shell:` is available, the sentinel/exit-code handling
> for the streamed write must be re-validated on-device before trusting it. This is the single biggest
> unknown of the silent path and the reason it is hardware-gated.

**b) `LocalAdbBridge.installApk` — switch to stdin streaming.**
Rework `StagedApk(name, path)` → a stream source `StagedApk(name, size, open: () -> InputStream)`
(the Phase-4 `ApkInstallFile` already has this byte-stream shape — the silent seam was built for it).
Per file: `pm install-write -S <size> <session> <name> -` fed from `open()`. Keep `install-create`
once and `install-commit` once; keep `install-abandon` on any failure; keep the **exit-code verdict**
(never a "Success" string); keep the **AC-6b name guard** (already at the boundary) and `ShellArgs`.

**c) The `ApkSilentInstaller` app-recv adapter (replaces `deferredSilentInstaller`).**
Wrap `AdbBridge.installApk` with the staged split streams; connect the bridge → install →
**disconnect in `finally`** (AC-11, mirroring `PrivilegeWizard.kt:218-221`); never hold shell uid open.
Map `InstallResult`: `Installed` → OK; `Failed` → surface; `BridgeUnavailable` → the Phase-4 capability
branch already falls through to Tier-0 (stale-positive tolerance).

### On-device verification (the new work this session)
1. Bootstrap the wizard (Wireless Debugging pair → connect → probe); confirm `SILENT_INSTALL` is
   reported present (and re-run if the probe was inconclusive — see the project skill on probe transport
   failures). Record the fingerprint.
2. Transfer + select a **multi-split** app. With `SILENT_INSTALL` present, the apply path takes the
   silent seam: the app installs with **no per-app confirm dialog**.
3. **Capture (AC-13):** the literal `pm install-commit` **exit code** (the authoritative verdict),
   `pm path <pkg>` showing base + all splits, and the fingerprint. Record into ADR-006 §Follow-ups.
4. **Stale-positive → Tier-0:** with `SILENT_INSTALL` probed present, force the bridge down at apply
   time (e.g. toggle Wireless Debugging off after the probe) → the install must fall back to the Tier-0
   confirm path (`BridgeUnavailable` → fallback), not hard-fail.
5. **Byte integrity:** confirm the silently-installed app runs and is not corrupt — this is the proof
   that the `exec:`-vs-`shell:` binary-stdin question was answered correctly.

### Still OPEN after this session (state honestly)
- ~~**Cross-device** old→new pairing + install (ADR-003 §7)~~ — **CLOSED 2026-06-21** (comet → rango:
  pair→connect→probe→`SILENT_INSTALL` on metal; wizard disconnects after the probe — no held shell uid).
- **Split target-compatibility — DENSITY leg: BUG FOUND→FIXED 2026-06-21.** A native density/ABI delta is
  structurally unavailable (husky 360 / rango 390 both bucket to xxhdpi; all GOS Pixels are arm64-only), so the
  receiver `densityDpi` was forced via `wm density 280` (→ xhdpi) ← rango `xxhdpi`-only Termux. Reconcile dropped the
  lone density split → `PackageInstaller … destroyed because of [Missing split for com.termux]`, install rejected.
  Control at native xxhdpi installed cleanly (all 5 splits, Termux launches). Fix: `ApkReconcile` keeps a fallback
  density split when none matches the bucket (don't drop to zero — a required-split-type base else `Missing split`).
- **Split target-compatibility — ABI leg: STILL OPEN (structural).** The `Incompatible` (non-arm64 target) branch is
  JVM-unit-tested but unreachable on the arm64-only Pixel fleet; closable end-to-end only on an x86_64 emulator.

---

## Part 3 — Phase 5 (runtime-permission parity) — separate go/no-go

Not part of "keystone landed". After P6, decide go/no-go on Phase 5 (ADR-006 D5): default-safe
`{INTERNET, OTHER_SENSORS-provisional}` only, dangerous perms behind an itemized opt-in, the first
production `grantRuntimePermission()` call site. Re-test `OTHER_SENSORS` against a manifest-declared
sensor app before trusting it in the default set (VERIFICATION-RUNBOOK V7 TENTATIVE).

---

## Quick reference — what each phase proved

| Phase | Landed | Verified |
|-------|--------|----------|
| P1 (codec + export) | ✅ | JVM |
| P1b (sender UI) | ✅ | JVM + assemble |
| P2 (receiver gate) | ✅ | JVM (incl. negative-size HIGH fix) |
| P3 (split install session) | ✅ | JVM (path-based; readability → stdin here) |
| P4 (apply provider + Tier-0) | ✅ | JVM + assemble |
| P6 Tier-0 on-device | ✅ | **hardware-verified 2026-06-21** (rango / Pixel 10 Pro Fold; single-APK + multi-split, carried not store) |
| P6 silent stdin-stream | ✅ | **hardware-verified 2026-06-21** (rango; ntfy multi-split, silent `exec:` + WD-gate fallback) |
| Cross-device pair→install | ✅ | hardware-verified 2026-06-21 (comet → rango; ADR-003 §7 closed) |
| AC-15 density reconcile | ✅ | **hardware bug found→fixed 2026-06-21** (forced `wm density`; drop-to-zero → `Missing split`; keep-fallback fix + regression test) |
| AC-15 ABI reconcile | ⛔ | JVM-tested; structurally unreachable on arm64-only Pixels (x86_64 emulator only) |
| Phase 5 (perm parity) | ⏳ | gated, separate |
