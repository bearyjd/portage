# ADR-004 — Vendored & pinned dependency source review (pre-release supply-chain gate)

Status: **IN PROGRESS** — noise-java CLOSED; libadb-android + spake2-android OPEN.
Context: ADR-003 §5 and ADR-002 follow-up #6 require a dedicated source review of the
vendored/pinned third-party trust roots before release. This ADR records those reviews.

## 1. Vendored `noise-java` — CLOSED ✓ (verbatim-diff, 2026-06-12)

**Verdict: FAITHFUL-AND-SAFE.** The vendored tree at
`core-transport/src/main/java/com/southernstorm/noise/` is a byte-for-byte copy of upstream
`rweather/noise-java` at commit **`49377b6dfc6a1e75740bce2318118291a57c0d6e`** (the commit
ADR-002 pins; current upstream `master` HEAD; MIT).

Evidence:
- `diff -ru` upstream↔vendored → **zero content deltas**; the only difference is an *added*
  `com/southernstorm/noise/LICENSE.txt`, itself byte-identical (SHA-256) to upstream's MIT
  `LICENSE.txt`.
- Per-file SHA-256 sweep: **28/28 `.java` files byte-exact** (rules out whitespace/line-ending
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

## 2. `libadb-android` 3.1.1 + `spake2-android` — OPEN

Pending dedicated source review (ADR-003 §5). To be appended here with the same rigor: pin the
exact published source, audit the ADB pairing/connect/TLS paths and the SPAKE2 native glue for
anything that exceeds "pair with this device's own adbd over localhost," and record a verdict.
