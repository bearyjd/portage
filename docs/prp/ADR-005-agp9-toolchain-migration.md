# ADR-005 — AGP 9.2.1 + Gradle 9.5.1 toolchain migration

**Status:** Accepted (2026-06-13). Reverses the HOLD that closed Dependabot #26/#27 and draft
PR #43. Executed as a deliberate, CI-gated migration (there is no local build — CI is the only
compiler; see the no-local-build constraint).

## Context

AGP 8.9.1 / Gradle 8.13 / Kotlin 2.4.0 / compileSdk 36 were deliberately pinned (dependabot.yml,
PR #16) because the AGP-9 bump is a coupled toolchain migration, not a version bump. A spike
(PR #43) confirmed the blockers:

1. **CI pins Gradle.** `build.yml` (×2) and `dependency-audit.yml` run bare `gradle` via
   `setup-gradle@v6` with `gradle-version: '8.13'` (the wrapper jar is intentionally uncommitted),
   so the wrapper bump alone is inert in CI. The wrapper AND all three workflow pins must move.
2. AGP 9.2.1 requires **Gradle ≥ 9.4.1**.
3. **AGP 9.x ships built-in Kotlin.** Applying the standalone `org.jetbrains.kotlin.android`
   plugin alongside it is a fatal `AgpWithBuiltInKotlinAppliedCheck` conflict in every Android
   module. The official fix is to **remove** `kotlin.android` (no replacement plugin id; the
   `android.builtInKotlin=false` opt-out is removed in AGP 10, so it is not used here).

(Researched 2026-06-13 from developer.android.com `/build/migrate-to-built-in-kotlin` +
`/build/releases/agp-9-0-0-release-notes`; docs.gradle.org `upgrading_major_version_9`.)

## Decision

Migrate to **AGP 9.2.1 + Gradle 9.5.1**, keeping **Kotlin 2.4.0**, **compileSdk 36**, and the
existing module layout. Concretely:

- **Gradle 9.5.1**: `gradle-wrapper.properties` + all three CI `gradle-version` pins.
- **AGP 9.2.1**: version catalog.
- **Remove `org.jetbrains.kotlin.android`** from all six Android modules (app-recv, app-send,
  adb-bridge, wizard, providers, core-transport) and from the root `apply false` block, and drop
  its catalog alias. Pure-JVM modules (core-model, settings-catalog) are unaffected.
- **Keep Kotlin 2.4.0** via a root `buildscript` classpath pin
  (`org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0`), so AGP's built-in Kotlin uses 2.4.0 in
  *every* module — including adb-bridge/wizard, which apply no Kotlin plugin alias — matching the
  still-applied Compose and serialization Kotlin plugins (both at catalog `kotlin` = 2.4.0).
- `buildFeatures { compose = true }`, `kotlin { jvmToolchain(17) }`, namespaces, and
  `compileOptions` (Java 17) are unchanged. No `android.kotlinOptions {}` block exists to migrate.

## Verification (CI-only, stepwise within one branch)

The Gradle/AGP/built-in-Kotlin changes are mutually required, so they land on one branch,
iterated against CI until green: `jvm-tests`, `android-build` (incl. the no-escalation
merged-manifest assert), and `osv-scan`. Then code-review + **security-review** (the change
touches the privilege-module build configs, the no-escalation gate, and the OSV/dependency-audit
gate).

## Risks & contingencies

- **compileSdk 36 → 37 (contingent):** AGP 9.2 allows compileSdk 36 (37 is its max, not a floor).
  But Compose ≥ 1.12.0 requires compileSdk 37, and the Compose BOM is `2026.05.01`. If CI shows the
  BOM forces 37, bump compileSdk to 37 (targetSdk stays 36 — a normal compile-against-latest setup,
  low GOS-target risk) and lift the `androidx.core` ≥ 1.19.0 ignore in dependabot.yml. This is the
  one product-adjacent decision; flag it rather than assume it.
- **No-escalation gate glob:** the assert greps `app-send/build/intermediates -path
  '*merged_manifest*/AndroidManifest.xml'`; AGP 9 may move that intermediates path. If it errors
  "merged sender manifest not found," widen the glob to the new path.
- **gradle.properties:** AGP 9 flips some `android.*` defaults; add whatever CI demands.
- **KGP override fallback:** if the buildscript classpath pin interacts badly with the plugin-DSL
  Kotlin plugins, fall back to aligning catalog `kotlin` to AGP's bundled 2.3.10 (no override).
- **OSV init script:** `lockAllConfigurations`, `resolutionResult.root`, and
  `notCompatibleWithConfigurationCache` survive Gradle 9 (researched); osv-scan CI confirms.

## Consequences

- Modern toolchain; built-in Kotlin reduces plugin wiring. The deliberate-pin rationale in
  dependabot.yml is resolved (update its comment once landed).
- Kotlin stays 2.4.0; behavior unchanged beyond the build mechanism.
- If compileSdk moves to 37, that is recorded as part of this migration, not a separate GOS bump.
