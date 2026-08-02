<!-- Generated: 2026-06-21 | Catalog: gradle/libs.versions.toml | Token estimate: ~700 -->

# Dependencies — external & supply-chain

Toolchain: AGP 9.2.1 · Kotlin 2.4.10 (K2) · JDK 17 · minSdk 31 (Pixel 6) · compileSdk 37 (GOS A17) · targetSdk 36 (held pending a separate behaviour review).
Every module pins `kotlin { jvmToolchain(17) }`.

## Runtime libraries
| lib | ver | module | role |
|-----|-----|--------|------|
| kotlinx-serialization-cbor | 1.11 | core-* | wire codec |
| kotlinx-coroutines | 1.11 | all | async/structured concurrency |
| (vendored) noise-java | — | core-transport | Noise PSK_XX (ADR-002; 29/29 byte-exact vs upstream) |
| libadb-android | 3.1.1 | adb-bridge | ADB protocol over localhost (JitPack; Apache-2.0 elected; ADR-003) |
| conscrypt-android | 2.5.3 | adb-bridge | pairing EKM export (public API) |
| bouncycastle bcpkix-jdk15to18 | 1.84 | adb-bridge | ADB identity X509v3 cert |
| (transitive) spake2 | — | adb-bridge | pairing PAKE |
| zxing core / android-embedded | 3.5.4 / 4.3.0 | apps | QR gen (send) / scan (recv); no GMS |
| compose-bom | 2026.05.01 | apps | UI (material3, activity-compose, lifecycle) |
| test | — | all | junit 4.13.2 · truth 1.4.5 · coroutines-test |

## External services
NONE. No cloud, no telemetry, no GMS, no analytics. LAN-only: localhost ADB (adb-bridge) + peer TCP.

## Supply-chain gates (ADR-004)
- OSV-Scanner CI gate (`dependency-audit.yml`): resolves the real shipped transitive graph → fails the
  build on a known advisory; weekly schedule catches new CVEs. Triaged advisories in `osv-scanner.toml`.
  `dependabot.yml` = update-PR/alert layer, NOT a gate.
- Vendored noise-java + pinned libadb 3.1.1 / spake2 source-reviewed CLEAR-FOR-RELEASE (loopback-only,
  no egress, no dynamic load). ADB identity key-gen uses a real CSPRNG (`SecureRandom`, RSA-2048).
  One accepted LOW residual: spake2 ephemeral scalar uses unseeded libc `rand()` (benign for the
  loopback + PAKE + one-shot model).
