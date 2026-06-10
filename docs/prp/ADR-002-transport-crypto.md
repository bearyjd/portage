# ADR-002 — Transport crypto library / pattern (core-transport)

Status: PROPOSED — needs JD's decision (security-critical fork). Drafted 2026-06-10.

## Context

`PROTOCOL.md §2` specifies `Noise_XXpsk3_25519_ChaChaPoly_SHA256`: the QR-carried 32-byte
PSK is the mutual authenticator, mixed at position 3. The spike's job was to find a vetted
library that implements this on JVM/Android.

## Spike findings (2026-06-10)

| Library | PSK support | Patterns | Maven | Maintained / audited | Verdict |
|---|---|---|---|---|---|
| **noise-java** (rweather, `com.southernstorm.noise`) | **Legacy only** — accepts prefix `NoisePSK_` (single PSK mixed at start ≈ `psk0`); **no `pskN` placement** | All 12 fundamental (XX, NN, IK, …) + fallback | JitPack (`rweather/noise-java`); MC fork `com.github.auties00` | Mature, widely referenced, MIT; old; not formally audited | Viable via `NoisePSK_XX` |
| **noise-kotlin** (sander, `nl.sanderdijkhuis:noise-kotlin`) | **None** — `Token` enum is `E,S,EE,ES,SE,SS`, no PSK token | Only `XN`, `NK` | Maven Central | Modern Kotlin; **explicitly "not independently audited"**; ~limited | **Ruled out** (no PSK) |

**Conclusion: modern `XXpsk3` is not available off-the-shelf.** `PROTOCOL.md §2` must be
amended to the achievable construction. There is no audited, maintained library that does
`pskN` placement on the JVM today.

## The property we actually need (from THREAT_MODEL.md)

Independent of pattern niceties: a channel where **completing the handshake requires the
QR PSK** (defeats same-LAN MITM #2), plus **per-session ephemerals** (forward secrecy),
plus **AEAD transport**. Any of the options below delivers all three. The `psk0` vs `psk3`
placement difference does *not* change whether an attacker without the PSK can complete the
handshake — it only changes when the PSK enters the chaining key. So legacy `NoisePSK_XX`
is **security-sufficient for our model**, even though it isn't the spec's modern form.

## Options

### A — noise-java, `NoisePSK_XX_25519_ChaChaPoly_SHA256` (RECOMMENDED for v1)
- Use a vetted, widely-used Noise lib (PROTOCOL.md's own preference: "use a vetted Noise
  implementation"). Vendor the source + pin a commit (don't ride JitPack-off-master).
- Legacy PSK (≈psk0): PSK mixed at start; XX still exchanges encrypted statics → gives the
  "remembered device" resume story too.
- **Cost:** legacy naming, an old dependency, mandatory security-reviewer pass on the glue.

### B — hand-rolled minimal `NNpsk0`-equivalent on audited primitives
- ~100 lines on libsodium (`lazysodium-android`) or Google Tink: both sides X25519
  ephemeral → `k = HKDF(psk ‖ dh ‖ transcript)` → ChaCha20-Poly1305 transport.
- **Pro:** fully reviewable, no unmaintained dep, modern audited primitives.
- **Con:** hand-rolled handshake — PROTOCOL.md explicitly warns against this; highest
  review burden; loses XX's encrypted-static resume feature (PSK-only).

### C — noise-java but contribute/patch a `pskN` mode, or adopt noise-kotlin + add PSK
- Closest to the original spec, but means maintaining a crypto fork. Highest effort;
  not recommended for v1.

## Recommendation

**Option A.** It satisfies the threat model with a real Noise library, keeps the resume
story, and matches PROTOCOL.md's "vetted implementation" guidance. Amend `PROTOCOL.md §2`:
`XXpsk3` → `NoisePSK_XX_25519_ChaChaPoly_SHA256`, with a note that legacy psk0-placement is
intentional and sufficient (this ADR). Keep everything behind the `SecureChannel` interface
so swapping to B or a future `pskN` lib stays local. Mandate a security review of the glue.

## Confidence + open questions

- Finding that no audited JVM lib does `pskN`: **high** (read the source of both libs).
- That `NoisePSK_XX` satisfies our threat model: **high** — PSK-gated completion + ephemeral
  FS hold regardless of placement.
- Open: confirm noise-java's `NoisePSK_` mode interops cleanly initiator↔responder with a
  loopback test (the hello-world this ADR gates). Open: vendor-and-pin vs JitPack vs the
  auties00 Maven Central fork — settle once Option A is chosen.
