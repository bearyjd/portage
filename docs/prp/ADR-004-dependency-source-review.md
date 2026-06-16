# ADR-004 — Vendored & pinned dependency source review (pre-release supply-chain gate)

Status: **CLOSED** — noise-java CLOSED (§1); libadb-android + spake2-android CLOSED (§2). Both
ADR-003 §5 pre-release supply-chain blockers cleared. One accepted residual (SPAKE2 `rand()`, §2).
Context: ADR-003 §5 and ADR-002 follow-up #6 require a dedicated source review of the
vendored/pinned third-party trust roots before release. This ADR records those reviews.

## 1. Vendored `noise-java` — CLOSED ✓ (verbatim-diff, 2026-06-12; independently re-verified 2026-06-15)

**Verdict: FAITHFUL-AND-SAFE.** The vendored tree at
`core-transport/src/main/java/com/southernstorm/noise/` is a byte-for-byte copy of upstream
`rweather/noise-java` at commit **`49377b6dfc6a1e75740bce2318118291a57c0d6e`** (the commit
ADR-002 pins; current upstream `master` HEAD; MIT).

Evidence:
- `diff -ru` upstream↔vendored → **zero content deltas**; the only difference is an *added*
  `com/southernstorm/noise/LICENSE.txt`, itself byte-identical (SHA-256) to upstream's MIT
  `LICENSE.txt`.
- Per-file SHA-256 sweep: **29/29 `.java` files byte-exact** (rules out whitespace/line-ending
  normalization that `diff` could mask). Packages were *not* renamed (`com.southernstorm.noise.*`
  preserved), so even the usual benign package-path delta is absent.
- No functional change to crypto logic, RNG, key handling, primitives, or wiping. No backdoor,
  hardcoded key, weakened RNG, or disabled validation introduced by vendoring.

Security spot-check on the path portage actually uses (`NoisePSK_XX_25519_ChaChaPoly_SHA256`):
`Noise.java:47` uses the system `SecureRandom` (no fixed seed); PSK is copied→destroyed→mixed
correctly in `HandshakeState`; 52 `Arrays.fill`/`Noise.destroy` wipe sites across the used files.
Byte-identity means these inherit upstream's verified behavior verbatim.

Blast radius: portage exercises ~9 files (Curve25519, ChaChaPoly, SHA-256, Handshake/Symmetric/
CipherState, Noise, Pattern). The rest (Curve448, NewHope, AES-GCM, Blake2, SHA-512) are vendored
but unused — still byte-identical, so dead weight rather than risk.

Caveats: (a) this is a **fidelity** gate, not a cryptographic audit of noise-java itself — upstream
is mature/widely-referenced/MIT but not formally audited, a known accepted property from ADR-002
Option A. (b) Upstream test vectors are not vendored; portage validates the integrated handshake
via `NoiseLoopbackTest`. Porting upstream KATs to assert the *compiled* primitives at build time is
an optional defense-in-depth, not required to close this item.

Reproduction:
```
git clone https://github.com/rweather/noise-java /tmp/noise-java-upstream   # HEAD == 49377b6
diff -ru /tmp/noise-java-upstream/src/main/java/com/southernstorm/noise \
         core-transport/src/main/java/com/southernstorm/noise
# → "Only in …: LICENSE.txt"  (zero content deltas)
```

## 2. `libadb-android` 3.1.1 + `spake2-android` — CLOSED ✓ (CLEAR-FOR-RELEASE, one accepted residual, 2026-06-13)

**Verdict: CLEAR-FOR-RELEASE.** Both libraries do exactly what ADR-003 claims — pair with and
self-connect to THIS device's own adbd over loopback — with no egress, no backdoor, no
exfiltration, no dynamic code loading, and a sound (BoringSSL-derived) crypto core. One genuine
but low-impact crypto-hygiene deviation (the SPAKE2 ephemeral-scalar RNG) is an **accepted residual**
for portage's loopback + PAKE + one-shot model.

### Provenance (record these for future-bump diffing)

| Component | Coordinate | Version | Audited commit | License | Native |
|-----------|-----------|---------|----------------|---------|--------|
| libadb-android | `com.github.MuntashirAkon:libadb-android` (JitPack) | 3.1.1 | `c849886e` | dual GPL-3.0-or-later OR Apache-2.0 (portage elects Apache-2.0); 2 files BSD-3-Clause (C. Gutman) | none — pure Java |
| spake2-android | `com.github.MuntashirAkon.spake2-java:spake2-android` | 2.2.1 (`build.gradle` string reads 2.2.0 — cosmetic) | `7615ddd6` | LGPL-3.0 | JNI `libspake2.so` |
| spake2-c (native src) | submodule `MuntashirAkon/spake2-c` | — | `0d15933e` | LGPL-3.0 over BoringSSL | built from source |
| bcprov (transitive) | `org.bouncycastle:bcprov-jdk15to18` | libadb declares 1.81 → portage forces **1.84** | — | MIT-style | — |

The native `libspake2.so` is **built from committed source** at JitPack build time (`externalNativeBuild{cmake}`
over `spake2.c`+`sha512.c`+`spake2_jni.cpp`, `-Wl,-z,max-page-size=16384` = the 16 KB alignment §7.7
device-verified), NOT shipped prebuilt. It is **BoringSSL's SPAKE2** (Google Inc. 2020 headers; "fully
compatible with BoringSSL"), the same family AOSP `adbd` uses — a 2-commit fork, not bespoke.

### Blast radius
portage's `:adb-bridge` exercises a tiny slice: `pair(host="127.0.0.1", port, code)`, `connectTls`
(mDNS discovery filtered to the local NetworkInterface set), `openStream("shell:…")`, `disconnect`,
key/cert getters. It does NOT use libadb's higher-risk services (REVERSE/BACKUP/RESTORE/TCP_CONNECT/
FILE/SYNC); `ShellArgs.kt` quote-or-rejects above the raw `shell:` string.

### Findings by area
- **Network egress — CLEAR.** Whole-tree scan found only `127.0.0.1`, `::1`, `10.0.2.2` (emulator
  host-loopback, only when `isEmulator()`), and comment-citation URLs. Only `INTERNET` permission
  (for the loopback socket). No `Runtime.exec`/`ProcessBuilder`/`URLConnection`/OkHttp, no
  `DexClassLoader`/dynamic loading. All sockets loopback or own-device mDNS.
- **ADB TLS/auth — SOUND.** `AdbProtocol.Message.parse()` validates magic + allowlisted command +
  `dataLength` bounds before allocation (no length-driven heap overflow). `adbAuthSign` is the exact
  AOSP `RSA/ECB/NoPadding` + PKCS#1 v1.5 SHA-1 construction. `SslUtils` accept-all TLS is NOT a vuln
  here — ADB's trust root is the SPAKE2 code + adbd-side RSA-pubkey trust; over loopback there is no
  MitM position (same posture as AOSP/LADB/Shizuku). Informational, not a finding.
- **SPAKE2 pairing — SOUND** (glue: HKDF-SHA256 → AES-128-GCM, RFC-5705 EKM channel-binding, all
  matching AOSP `pairing_auth.cpp`; native `SPAKE2_process_msg` rejects `len!=32`, validates the peer
  point on-curve before scalar math, bounds the key copy — no native memory-safety defect reachable
  from peer input). **One BENIGN-CONCERN below.**
- **Backdoors — CLEAR.** No hardcoded keys/tokens, no hidden commands, no telemetry, no surprising
  permissions. Reflection limited to 3 legitimate uses (Conscrypt EKM export, Conscrypt provider
  load, the `PRNGFixes` Android<4.4 fix — off portage's minSdk-31 path).

### Accepted residual (LOW) — SPAKE2 ephemeral scalar uses unseeded libc `rand()`
`spake2-c/spake2.c` derives the ephemeral private scalar with a 64-byte loop of libc `rand()` instead
of BoringSSL's `RAND_bytes` (CSPRNG); `srand()` is called only in the non-shipped `test.c`, so on
Bionic the scalar derives from the default-seed (`1`) deterministic stream. **Accepted for portage**
because: SPAKE2 is a PAKE (an attacker without the 6-digit pairing code gains nothing from a
predictable blinding scalar); pairing is **loopback-only** (no remote/MitM observer position — an
attacker who could observe loopback already has on-device code execution and doesn't need this); and
it is **one-shot** behind a transient Developer-options dialog with a per-open rotating port. No
key-recovery or auth-bypass path is established for portage's deployment. **Disposition:** accepted
residual; **upstream follow-up** to restore `RAND_bytes`/`getrandom(2)` recommended (portage cannot
patch a transitive native source). If defense-in-depth is later wanted, fork `spake2-c`.

### Residual caveats
- The `.so` is built-from-source (good), but `spake2-c` is a floating git submodule resolved at
  JitPack build time; the Maven lockfile pins the AAR coordinate (so the shipped `.so` is fixed for
  the pinned artifact), not the submodule commit independently. The audited commits above make any
  future bump a reviewable diff.
- This review substitutes for an upstream audit over the exact paths portage exercises **plus** the
  full pairing/TLS/SPAKE2 trust root; unused libadb services were enumerated but not line-audited
  (unreachable from `:adb-bridge`).

**ADR-003 §5 is now fully CLOSED** (noise-java §1 + this §2).
