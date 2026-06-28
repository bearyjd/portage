# Contributing to portage

portage is a Kotlin multi-module Android project (two APKs: `portage-send`, `portage-recv`).
AGPL-3.0, Grepon Labs LLC. This guide is generated from the build config + CI; the
`<!-- AUTO-GENERATED -->` sections are derived from `gradle/libs.versions.toml`,
`.github/workflows/`, and `CLAUDE.md` — regenerate them rather than hand-editing.

See also: [`README.md`](../README.md) (overview), [`docs/CODEMAPS/`](CODEMAPS/) (architecture),
[`docs/RUNBOOK.md`](RUNBOOK.md) (build/install/troubleshoot), [`docs/prp/`](prp/) (design substrate).

## Prerequisites

<!-- AUTO-GENERATED: toolchain (source: gradle/libs.versions.toml, .github/workflows/build.yml) -->
| Tool | Version | Notes |
|------|---------|-------|
| JDK | **17** (Temurin) | every module pins `jvmToolchain(17)`; set `JAVA_HOME` if your default `java` is newer (a JDK-21 compile → `UnsupportedClassVersionError`) |
| Android SDK | compileSdk/targetSdk **36** (GOS Android 16) | minSdk 31 (Pixel 6); set `ANDROID_HOME` / `local.properties` `sdk.dir` |
| Gradle | **9.5.1** | pinned by the committed wrapper and distribution checksum |
| AGP / Kotlin | 9.2.1 / 2.4.0 (K2) | built-in Kotlin; Compose via the kotlin-compose plugin |
<!-- END AUTO-GENERATED -->

## Setup

The wrapper files are committed and the Gradle distribution checksum is pinned. Point the
SDK via `ANDROID_HOME` or a `local.properties` `sdk.dir=` line (git-ignored), then use
`./gradlew` for every build.

## Commands

<!-- AUTO-GENERATED: command reference (source: README.md, .github/workflows/build.yml) -->
| Command | Purpose |
|---------|---------|
| `./gradlew :settings-catalog:test` | Pure-JVM safety-critical settings-allowlist guardrails (fast lane, no SDK) |
| `./gradlew :core-model:test` | Wire-protocol model unit tests |
| `./gradlew :core-transport:testDebugUnitTest` | Noise PSK_XX loopback + adversarial transport tests |
| `./gradlew :adb-bridge:testDebugUnitTest` | Privilege-bridge unit tests |
| `./gradlew :wizard:testDebugUnitTest` | Bootstrap state-machine tests |
| `./gradlew :providers:testDebugUnitTest` | Export/apply providers (incl. APK reconcile) |
| `./gradlew :app-recv:testDegoogleDebugUnitTest` / `:app-send:testDegoogleDebugUnitTest` | App logic (ViewModels) — degoogle flavor (full Tier-1); CI also runs the play flavor variants |
| `./gradlew assembleDebug` | Build both debug APKs (needs the Android SDK) |
| `./gradlew assembleRelease` | Build all minified release variants (unsigned unless signing is configured) |

**Full local gate** (mirrors CI; run with `--no-daemon` — the gradle daemon is flaky in some envs):
```sh
./gradlew :settings-catalog:test :core-model:test :core-transport:testDebugUnitTest \
  :adb-bridge:testDebugUnitTest :wizard:testDebugUnitTest :providers:testDebugUnitTest \
  :app-recv:testDegoogleDebugUnitTest :app-recv:testPlayDebugUnitTest \
  :app-send:testDegoogleDebugUnitTest :app-send:testPlayDebugUnitTest \
  assembleDebug assembleRelease --no-daemon
```
<!-- END AUTO-GENERATED -->

## Testing

- Frameworks: JUnit 4 + Truth (`com.google.truth`); coroutines-test for async.
- Pure-JVM modules (`core-model`, `settings-catalog`) use `test`; Android modules use
  `testDebugUnitTest` (library modules) or `testDegoogleDebugUnitTest` / `testPlayDebugUnitTest` (app modules).
- Safety-critical invariants are guardrail tests — a green test must pass for the *right*
  reason (e.g. the settings-allowlist "no non-DEVICE_SPECIFIC key is unvalidated" test, the
  `SPLIT_NAME`/`adb_wifi_enabled` lockstep pins). Don't weaken them to get green.

## Working cadence (required — from `CLAUDE.md`)

```
branch-per-feature → author → independent review → fix findings → merge to main
```
- Branch off `main` (`feat/…`, `fix/…`). **Never** commit feature/code work straight to `main`.
- **Independent review before merge**, in a separate lane from the author:
  - `code-reviewer` for any non-trivial change.
  - `security-reviewer` **additionally and mandatorily** for anything touching **crypto, the
    privilege boundary, permissions, or the wire protocol**.
- Open a PR, CI must be green, address findings (or track in an ADR), then **squash-merge** and
  delete the branch.
- **Direct-to-`main` OK only for** docs/config: `README.md`, `docs/**`, `.github/**` typo fixes.
- Commits: Conventional Commits (`feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`).

## Architectural invariants — do not break

- `AdbBridge` (`:adb-bridge`) is the ONLY privileged entry point. No raw `AdbBridge.shell()` or
  libadb outside `:adb-bridge`/`:wizard` — **CI fails** on a raw `.shell(` call elsewhere.
- `app-send` must carry **no** escalation surface (no `WRITE_SECURE_SETTINGS`, no `:adb-bridge`
  link/native libs) — **CI asserts** this on the merged manifest + APK.
- `:providers` stays Android-/`:adb-bridge`-free (privilege injected as a seam).
- Settings writes go through the compiled `settings-catalog` SAFE allowlist, every value validated.
- Scope: Seedvault owns app *data*; portage owns settings/inventory/parity (no app-data item kinds).

## CI gates

<!-- AUTO-GENERATED: CI (source: .github/workflows/build.yml, dependency-audit.yml) -->
| Workflow / job | Asserts |
|----------------|---------|
| `build.yml` → **jvm-tests** | `:settings-catalog:test` (safety-critical allowlist, runs in seconds) |
| `build.yml` → **android-build** | library/app unit tests; debug builds; minified release builds; debug + release **no-escalation** assertions (sender and Play receiver remain bridge-free, degoogle receiver retains the bridge); **raw-shell** assert (`.shell(` only in `:adb-bridge`); uploads debug APKs |
| `dependency-audit.yml` → **osv-scan** | OSV-Scanner over the real shipped transitive graph; fails on a known advisory; weekly schedule. Triaged items in `osv-scanner.toml` |
<!-- END AUTO-GENERATED -->

## PR checklist

- [ ] Branched off `main`; not committing to `main` directly (code).
- [ ] Full local gate green (`assembleDebug` + all module tests; use `testDegoogleDebugUnitTest` for app modules).
- [ ] `code-reviewer` ran; `security-reviewer` ran if crypto/privilege/permissions/wire touched.
- [ ] Findings addressed or tracked in an ADR.
- [ ] No new raw `.shell(` outside `:adb-bridge`; no escalation surface added to `app-send`.
- [ ] Conventional-commit messages; wrapper files not staged.
- [ ] Docs updated if behavior/protocol/decision changed (ADR for decisions).
