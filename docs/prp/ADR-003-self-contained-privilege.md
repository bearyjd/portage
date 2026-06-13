# ADR-003 — Self-Contained Privilege: portage Owns the ADB Bridge

Status: **ACCEPTED — pending on-device verification** (see §7). Supersedes the Shizuku
delivery mechanism of ADR-001; the ADR-001 **grant architecture itself is unchanged and
remains verified** (V2–V8, Pixel 9 Pro XL, GOS Android 16, 2026-06-10).
Decision owner: JD. Drafted 2026-06-12 on `refactor/self-contained-privilege`.

## 1. Decision

Replace the Shizuku dependency entirely. portage now owns the full privileged stack inside
one module, `:adb-bridge`: RSA key generation, Android 11+ Wireless Debugging **pairing**
(SPAKE2 over TLS), **self-connection** to this device's own adbd over localhost (TLS 1.3),
and shell-uid command execution. From the user's perspective: enable Developer options,
enable Wireless debugging, type a 6-digit code into the in-app wizard. No second app to
install, no explaining what Shizuku is.

The boundary rule, enforced at review: **`AdbBridge` is the only entry point to privileged
operations. No module other than `:adb-bridge` may speak the ADB wire protocol or import
the ADB library.** Raw `AdbBridge.shell()` call sites outside `:adb-bridge`/`:wizard` are
review blockers — use the typed operations.

## 2. Rationale

- **No third-party install.** The single biggest UX cliff in the Tier-1 flow was "first go
  install and start this other app, and restart it after every reboot."
- **Mutual exclusion.** LADB-class tools and Shizuku are known to conflict at the
  ADB-server level on the same device — only one client family wins. Delegating to
  Shizuku therefore couples portage's privilege path to whatever else the user runs;
  owning the stack (as one more direct adbd client, not a second adb *server*) removes
  that coupling.
- **Cleaner OS-integration story** (§6): one interface to reimplement natively.

## 3. Prior art and the evidence that reshaped the plan

The refactor brief proposed `com.tananaev:adblib` plus "LADB's pairing implementation."
Both legs were checked against primary sources and **neither survives**:

- **`com.tananaev:adblib` is unusable here.** Latest release 1.3 (Jan 2021). Its protocol
  layer implements only `CNXN/AUTH/OPEN/OKAY/CLSE/WRTE` — **no `A_STLS`, no TLS, no
  pairing**. Android 11+ Wireless Debugging requires TLS 1.3 on the connect port (AOSP
  `adb_wifi.md`: adbd opens with `A_STLS`; the legacy plaintext `A_AUTH` flow exists only
  on old `adb tcpip` mode). A client without STLS support can never talk to the Wireless
  Debugging port.
- **LADB (tytydraco/LADB) does not implement the protocol at all.** It bundles the AOSP
  `adb` binary as a per-ABI `libadb.so` and shells out to `adb pair localhost:<port>` /
  `adb connect localhost:<port>` via `ProcessBuilder`; the pairing port and code are
  user-typed, the connect port is found via NsdManager on `_adb-tls-connect._tcp`. There
  is no Java SPAKE2 in LADB to study or derive. Its current license is a **custom
  BSD-3-Clause-style license with a no-Play-Store clause (not GPLv3)**, so we derive **no
  code** from it. LADB is acknowledged as *architectural* prior art: it proves the
  self-connect approach works in production, including on Android 16.

**Chosen implementation: [libadb-android](https://github.com/MuntashirAkon/libadb-android)
3.1.1** (Muntashir Al-Islam), the only maintained pure-JVM/Android library covering BOTH
the pairing protocol (SPAKE2 + HKDF + AES-128-GCM PeerInfo exchange, constants mirrored
from AOSP `pairing_auth.cpp`) and the TLS connect path (`A_STLS`). Distribution: JitPack
(`com.github.MuntashirAkon:libadb-android`), with `spake2-android` (JNI port of
BoringSSL's SPAKE2) and `bcprov` as transitive dependencies.

A third viable architecture — bundling the `adb` binary LADB-style — was rejected for now:
multi-MB per-ABI binaries in the repo, CLI output parsing as a protocol, and a much larger
supply-chain surface than one auditable Java library.

## 4. Licensing

- **libadb-android is dual-licensed `GPL-3.0-or-later OR Apache-2.0`. portage elects
  Apache-2.0** (one-way compatible into AGPL-3.0; simpler obligations). Recorded in
  `NOTICE`. Two files in the library carry additional BSD-3-Clause heritage notices
  (Cameron Gutman / Sam Palmer) — preserved via the library's own artifact.
- **spake2-java/spake2-android is LGPL-3.0** — fine as a dynamically-consumed dependency
  of an AGPL-3.0 app.
- **Conscrypt, BouncyCastle**: Apache-2.0 / MIT-style respectively.
- **LADB**: attribution as prior art only (NOTICE); no code derived. For the record, had
  derivation been needed: GPLv3 → AGPLv3 combination is permitted by GPLv3 §13/AGPLv3 §13
  (each part keeps its own license; Affero source obligations attach to the AGPL parts).

## 5. Security posture

- **Identity.** `AdbKeyStore` generates RSA-2048 + a long-validity self-signed cert
  (BouncyCastle `bcpkix`), persisted ONLY in the app's private `filesDir` (`adb.key`
  PKCS#8 / `adb.crt` DER). Both apps set `android:allowBackup="false"`, so the key is
  never captured by Android backup. The private key never leaves `:adb-bridge`, is never
  logged, and is not in any crash-report path. Trust anchoring is adbd-side: pairing
  writes our PUBLIC key into `/data/misc/adb/adb_keys`; the cert is only the TLS vehicle.
- **Pairing code.** Passed through to the SPAKE2 exchange; never logged, never persisted.
  As a Java String it is a non-zeroizable accepted residual — the same THREAT_MODEL §1
  boundary as the QR-encoded PSK.
- **Connection lifetime.** The wizard tears the connection down immediately after the
  capability probe (`PrivilegeWizard.finishProbe`, in a `finally`); shell uid is
  re-established only at transfer time. `disconnect()` deliberately bypasses the op lock
  so it aborts an in-flight command instead of queueing behind it.
- **Command construction.** All typed operations build argv through `ShellArgs` (quote or
  reject; control characters rejected outright). No caller string-interpolates values
  into a command line.
- **`shell()` is public again — deliberately.** ADR-001-era review removed `exec` from
  `PrivilegedOps` because the Shizuku UserService was a second process whose binder had to
  be allowlisted. That boundary no longer exists: portage IS the privileged client, and the
  wizard's probe legitimately needs general commands. The compensating control is the
  call-site rule in §1 plus `ShellArgs` for anything value-bearing.
- **Conscrypt is declared explicitly** (`org.conscrypt:conscrypt-android`) so the pairing
  EKM export (RFC 5705) uses the bundled public-API Conscrypt. Without it, libadb-android
  reflects the HIDDEN platform class `com.android.org.conscrypt.Conscrypt` — a non-SDK
  surface we refuse to depend on.
- **Open follow-up (tracked in CLAUDE.md):** libadb-android has **never had a security
  audit** (its own README says so). Before release: a dedicated review of the library
  source at the pinned tag (same treatment as the vendored noise-java tree), plus the CI
  dependency-audit gate (OSV-Scanner) that is already an open follow-up.
- **Accepted-residual hardening ideas from the 2026-06-12 review** (tracked, not blocking):
  a per-invocation nonce on the shell exit sentinel (output of trusted, fixed commands can
  theoretically forge `__PORTAGE_EXIT__`); an explicit truncation marker when the 4 MiB
  exec output cap is hit.

## 6. OS-integration path (the GrapheneOS contribution surface)

`AdbBridge` is the interface a privileged system-app build would implement natively:

- Skip the Wireless Debugging wizard entirely.
- Implement `selfGrant`/`installApk`/probes against platform APIs with real permissions.
- Drop the ADB key pair, SPAKE2, TLS, and mDNS — you don't bootstrap privilege when you
  ARE the platform.

The swap is 100% localized to `:adb-bridge` (`LocalAdbBridge` → a `SystemAdbBridge`); the
wizard collapses to its `Checking → Ready` path; nothing else in the codebase changes.
This is the property GrapheneOS reviewers should evaluate.

## 7. HARDWARE VERIFY-FIRST (before any release; record GOS fingerprint per run)

1. mDNS discovery of `_adb-tls-pairing._tcp` fires while the pairing dialog is open
   (known-flaky NsdManager — the wizard's manual-port fallback is the mitigation).
2. `pair()` succeeds with the dialog's code; wrong code maps to `WrongCode` (SSL abort).
3. `connectTls` self-connect succeeds at API 36 with bundled Conscrypt; `shell("id")`
   returns uid 2000.
4. Self-grant V4/V5 re-run THROUGH the bridge (grant persists across reboot with Wireless
   Debugging left OFF).
5. Reboot recovery walk: reboot → toggle Wireless debugging on → wizard reconnects with
   NO re-pair (key persisted).
6. Probe commands return the expected shapes on GOS (`pm install-create` silent-session
   verdict = ADR-001 V6; `cmd overlay list android`; `dumpsys role`).
7. 16 KB page-size alignment of `spake2-android`/`conscrypt` native libs on GOS Android 16
   (UNVERIFIED upstream at 2.2.1 — check `zipalign`/lint in CI or on device).

## 8. Known risks

- **Google tightening Wireless Debugging.** Same risk class Shizuku carries; no evidence
  of restriction through Android 16 (LADB and Shizuku both still ship on this mechanism),
  and Google is currently *loosening* it (auto-enable on trusted Wi-Fi in Canary). GOS is
  unlikely to close a legitimate developer tool; its auto-reboot (default 18 h) is an
  extra connection-reset vector the wizard's reconnect path already handles.
- **The toggle resets on reboot / network change** (stock behavior, ADR-001 §2 #1). The
  wizard's recheck + silent-reconnect path is the designed recovery; the exact GOS warning
  copy ships in the Ready step (`WizardCopy.GOS_REBOOT_WARNING`).
- **`connect()` can hang past its own timeout when no endpoint exists.** Found on-device
  (GOS A16, 2026-06-12): with Wireless debugging OFF there is no `_adb-tls-connect`
  service, and libadb's `connectTls` blocks on an NsdManager wait that ignores thread
  interruption, so the coroutine `withTimeout` cannot cancel it — the wizard hung on
  "Checking" indefinitely. Fix: `PrivilegeWizard.route()` now detects the toggle BEFORE
  attempting a silent reconnect and only calls `connect()` when Wireless debugging is on
  (post-reboot recovery still reconnects via `recheck()`). The deeper bridge-level
  robustness (a `connect()` that cannot hang regardless of caller) remains a tracked
  follow-up — hard to fully solve since a stuck uninterruptible native wait can't be killed.
- **mDNS flakiness** (libadb issues #5/#7/#15): timeouts everywhere, manual port entry,
  and "reopen the pairing dialog" copy.
- **Long command lines** can hit a known `BufferOverflowException` in the library's OPEN
  packet (#25): portage commands are short by construction; `LocalAdbBridge` caps output,
  not input — keep new ops small or stage via files.

## 9. Deviations from the refactor brief (all evidence-driven, see §3)

| Brief said | Shipped | Why |
|---|---|---|
| Use `com.tananaev:adblib:1.3` | libadb-android 3.1.1 | adblib has no TLS/pairing; cannot reach a Wireless Debugging port |
| Derive pairing from LADB (GPLv3) | No LADB code at all | LADB shells out to the adb binary; no pairing code exists to derive; license is not GPLv3 |
| Wizard in both apps | Receiver only | The sender never performs a privileged op; CI hard-gates app-send as escalation-free |
| Providers call `AdbBridge` | Narrow `TierOneGrant` seam | Keeps the ADB stack (and its native libs) out of `portage-send` entirely; same lazy-grant semantics, same tests |
| `writeSecureSetting` etc. as the data path | Typed ops exist, but settings parity still writes via `Settings.*` post-grant | ADR-001 grant architecture is strictly better and stays verified |
| Per-item "Setup required" checklist gating | Deferred (follow-up) | Needs capability state plumbed through the checklist model; graceful degrade already exists at apply time |
