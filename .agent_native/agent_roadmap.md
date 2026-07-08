# Agent-native roadmap for portage

Audit date: 2026-07-07. Read-only audit; no `./gradlew` invocations were run (per instruction).
Baseline: current `main`. Goal: let an autonomous coding agent pick up a raw bug report or
feature request and reproduce → implement → test → verify it with minimal human input.

Ranked by **Human-Attention-Saved per Unit of Effort** — cheapest changes that remove the most
tribal-knowledge/human-judgment steps come first. Effort: **XS** (<30 min), **S** (<half day),
**M** (half-to-full day). All 5 top items are additive (docs + tests), none touch production
runtime behavior, so none need the security-reviewer gate CLAUDE.md mandates for
crypto/privilege/wire changes.

## Top 5 — immediately actionable

### 1. Provider-registration completeness guard (XS, highest leverage) — DONE (2026-07-07)
**Implemented:** `listOf(...)` in both `MainActivity.kt`s extracted to `internal fun
buildApplyProviders(...)` (`app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/MainActivity.kt`)
and `internal fun buildExportProviders(...)`
(`app-send/src/main/kotlin/com/ventouxlabs/portage/send/MainActivity.kt`), parameterized on
Store-seam interfaces/callbacks (never `Context`/`ContentResolver`) so they run in plain JVM unit
tests. New completeness tests:
`app-recv/src/test/kotlin/com/ventouxlabs/portage/recv/ApplyRegistrationCompletenessTest.kt`
(asserts all 14 `ItemKind`s have a registered apply provider — no apply-only exceptions today) and
`app-send/src/test/kotlin/com/ventouxlabs/portage/send/ExportRegistrationCompletenessTest.kt`
(asserts all `ItemKind`s except the documented apply-only exceptions `APK`/`APP_BACKUP_RELAY`/
`USER_FILE` have a registered export provider). Verified the guard actually catches a missing
registration (not vacuously true): temporarily removed `ContactsApplyProvider` from
`buildApplyProviders`, confirmed `ApplyRegistrationCompletenessTest` fails with a clear diff, then
reverted. `app-send/build.gradle.kts` gained one new `testImplementation(project(":settings-catalog"))`
(test-only, for the `Namespace` type needed by a fake `SecureGlobalSettingsStore`).

Original problem/action writeup kept below for context.
**Problem:** Wiring a new `ItemKind` end-to-end currently requires touching 4 separate files
with no compiler or test catching a missed spot: `core-model/src/main/kotlin/com/ventouxlabs/portage/model/Manifest.kt:24` (enum entry) →
`app-send/src/main/kotlin/com/ventouxlabs/portage/send/MainActivity.kt:146` (export list) →
`app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/MainActivity.kt:148-149` (`ApplyProviderRegistry` list) →
`app-send/src/main/AndroidManifest.xml` (permission). `ApplyProviderRegistry` (`providers/src/main/kotlin/com/ventouxlabs/portage/providers/ApplyProviderRegistry.kt`)
is a runtime map keyed by `ItemKind`, not an exhaustive `when` — a forgotten wiring step degrades
silently to `ItemStatus.UNKNOWN_KIND` at transfer time instead of failing at build/test time.
**Action:** add one test per app module that asserts completeness, e.g. in
`app-recv/src/test/kotlin/.../MainActivityWiringTest.kt`:
```kotlin
val expected = ItemKind.entries.filter { it != ItemKind.USER_FILE /* apply-only exceptions, list them explicitly */ }
val registered = buildApplyProviderRegistry(/* the same factory MainActivity uses */).let { r ->
    ItemKind.entries.filter { r.forKind(it) != null }
}
assertThat(registered).containsExactlyElementsIn(expected)
```
This requires extracting the `listOf(...)` construction in `MainActivity.kt:148-149` into a
small testable factory function first (5-line refactor) — do that as part of the same change.
Mirror on `app-send` for the export-side `listOf(...)` at `MainActivity.kt:146`.
**Acceptance criteria:** adding a new `ItemKind` without registering its provider fails
`:app-recv:testDegoogleDebugUnitTest` / `:app-send:testDegoogleDebugUnitTest` with a clear diff
of the missing kind, not a silent `UNKNOWN_KIND` at runtime.

### 2. Fix the CI `shell()` gate / doc mismatch for `:wizard` (XS) — DONE (2026-07-07)
**Resolved via option (b)** (recommended): kept `build.yml`'s grep scope unchanged (`:wizard`
stays in the scanned/forbidden module list — narrower is safer, `PrivilegeWizard` never needs raw
shell today), and aligned the docs to state the same rule: `CLAUDE.md` (the privilege-delivery
bullet + the renamed "Known CI-gate rule" section, formerly "Known CI-gate caveat") and
`AdbBridge.kt`'s interface kdoc now both say raw `.shell()` is `:adb-bridge`-only, `:wizard` must
use only the typed ops (`pair`/`connect`/`probeCapabilities`/`disconnect`), and CI enforces it (not
just review). Added a one-line-plus comment at `build.yml:163-169` explaining why `:wizard` is
deliberately kept in the scanned list. No CI grep logic changed — the gate is exactly as strict as
before.

**Problem (original):** CLAUDE.md and `AdbBridge.kt:27-30` state raw `.shell()` calls are allowed inside
`:adb-bridge` **and** `:wizard`. The actual CI enforcement,
`.github/workflows/build.yml:163-171` (step "Assert raw AdbBridge.shell() stays inside the
privilege modules"), greps `app-recv app-send providers core-model core-transport
settings-catalog wizard` and excludes only `test`/`androidTest` dirs — it does **not** exclude
`wizard` from the scan. It currently passes only because `PrivilegeWizard.kt` never calls
`.shell()` directly. This is a latent trap: the next agent who reads CLAUDE.md's stated
exception and adds a raw `.shell()` call in `:wizard` will get a confusing CI failure that looks
like a security gate rejecting sanctioned code.
**Action:** pick one and make them agree — either (a) remove `:wizard` from the grep's scanned
module list (`build.yml:166`) to match the documented exception, or (b) tighten CLAUDE.md/
`AdbBridge.kt` comments to say wizard must go through `AdbBridge`'s typed ops only, never raw
`shell()`, and note the CI gate enforces that (recommended — narrower is safer, `PrivilegeWizard`
never needs raw shell today).
**Acceptance criteria:** CLAUDE.md, `AdbBridge.kt` comments, and `build.yml`'s grep scope all
state the same rule; add a one-line comment at `build.yml:164` explaining why `wizard` is (or
isn't) in scope so a future edit doesn't quietly regress it.

### 3. Generalize `LoopbackTransferSmokeTest` into a table-driven, all-kinds harness (S)
**Problem:** `app-recv/src/test/kotlin/com/ventouxlabs/portage/recv/transfer/LoopbackTransferSmokeTest.kt`
is the *only* test that exercises the full real path (export → Noise handshake over real TCP
loopback → item stream → apply) end-to-end, and it only does so for one `ItemKind`
(`CONTACTS_VCF`). It's an excellent reproduction harness pattern — self-contained, no physical
device, no external fixtures, real crypto/wire code — but it isn't reusable: the sender-script
and assertions are hand-inlined for contacts only.
**Action:** extract `runSenderScript`/`freePort`/`payload` helpers into a shared
`LoopbackHarness` (new file, same package or a `testFixtures` source set on `app-recv` or a new
tiny `core-transport` test-support artifact), parameterized on `(ItemMeta, ByteArray, applyFn)`.
Then add one case per shipped `ItemKind` (14 today) reusing the same harness — most are a few
lines each since the export/apply providers already exist and are unit-tested individually; this
harness proves they compose correctly over the *real* wire, which nothing else does. This becomes
the canonical place an agent adds a regression test when reproducing a bug report of the shape
"item X arrived corrupted/wrong on the receiving device" without needing two physical phones.
**Acceptance criteria:** a table-driven test (`@Test` per kind or a JUnit `Parameterized` runner)
in `app-recv/src/test/.../transfer/` covering all 14 `ItemKind` values end-to-end over the real
`NoiseSecureChannelFactory`; CI catches any future item kind added without a corresponding
full-path case (pairs naturally with item #1's completeness assertion).

### 4. Recorded-session replay fixture format for bug reproduction (M)
**Problem:** There are zero fixtures anywhere in the repo (no `resources/`, no `*.json`/`*.pcap`
sample data — confirmed by exhaustive search). Every test builds its data programmatically
in-test. This means a raw bug report like "importing this contacts export corrupts emoji names"
or "transfer hangs after item 7 of 12" has no structured way to be captured and replayed — an
agent has to hand-translate prose into a new hand-rolled test each time, which is slow and
error-prone (easy to accidentally test something other than what the user hit).
**Action:** define a small serializable `RecordedSession` format (manifest + ordered list of
`(ItemMeta, bytes)` pairs, reusing the existing `TransferManifest`/`ItemMeta`
`kotlinx.serialization` models already in `core-model` — no new wire format needed, just a JSON
envelope for test fixtures) plus a `ReplayHarness` that feeds a `RecordedSession` through
`ItemStreamReceiver` + `ApplyProviderRegistry` exactly like #3's harness, but loading the item
bytes from a fixture file instead of constructing them inline. Store fixtures under
`app-recv/src/test/resources/recorded-sessions/` (first fixtures directory in the repo). Document
in the new checklist (item #5) how to capture one from a real device: export via app-send with a
debug "dump manifest+items to file" flag, or hand-construct from the bug reporter's description
using existing model classes.
**Acceptance criteria:** at least one real fixture + replay test demonstrating the pattern (e.g.
replay the Termux xxhdpi APK-split regression already described narratively in
`ApkReconcile.kt:76`/its test, converting it into a loadable fixture as the reference example);
a short README in the fixtures directory explaining the format and how to add a new one from a
bug report.

### 5. Write down the Store/Provider seam pattern + provider authoring checklist (S)
**Problem:** all 12 data-domain providers replicate the same three-part shape (`XyzStore`
interface seam → `AndroidXyzStore` impl → pure `XyzExportProvider`/`XyzApplyProvider` operating
only against the seam) plus two copy-pasted conventions: `available() =
runCatching { store.count() > 0 }.getOrDefault(false)` and "zero records applied but input was
non-empty ⇒ `ItemStatus.WRITE_ERROR`". None of this is named or written down; a new agent
implementing a new domain (there's an open backlog — PRP-07 Bluetooth is DRAFT, Wi-Fi/roles are
spike-gated in the northstar gap audit) has to reverse-engineer the pattern by reading two or
three existing providers side by side.
**Action:** this audit's CLAUDE.md augmentation (see below) already codifies the pattern inline;
additionally add `docs/prp/PROVIDER_PATTERN.md` with a literal copy-paste-and-fill-in template
(interface + Android impl + export + apply provider stubs) and a link to the best existing
worked example (`providers/src/main/kotlin/.../calllog/CallLogProviders.kt` — smallest, no photo/
attachment complexity, keeps the journal/idempotency pattern visible).
**Acceptance criteria:** a new provider can be scaffolded by copying the template and filling in
~4 method bodies, and the doc explicitly cross-references all 4 registration touch-points from
item #1 so nothing is missed by hand even before item #1's test exists.

## Audit findings by area

**1. Human-judgment chokepoints.** The biggest gap is that provider authoring is a fully manual,
undocumented ritual: a 4-file registration checklist, a copy-pasted `available()` idiom, a
copy-pasted zero-applied-but-nonempty-input error threshold, and an unnamed Store/Provider seam
split repeated 12×, none written down anywhere (items #1, #5). A secondary chokepoint: the
degoogle/play flavor split for privilege-gated features lives entirely in Gradle source-set
structure (`app-recv/src/{degoogle,play}/.../*PrivilegeIntegration.kt`) rather than code branches
— an agent unfamiliar with this repo could easily add a Tier-1 feature directly in `src/main` and
break the play-flavor no-bridge boundary the CI gate exists to catch. Tertiary: a real
doc/enforcement mismatch on the `:wizard` shell() exception (item #2) that will confuse whoever
next touches wizard privilege code.

**2. Verification gaps.** Per-provider and per-module unit test coverage is genuinely strong
(5,516 test LOC in `providers/` alone, 25 test files, one dedicated per domain) and the crypto/
wire layer has real adversarial coverage (`NoiseLoopbackTest`, `SocketSecureChannelTest`,
`CborMessageCodecTest`). The gap is one level up: only a single hand-written end-to-end test
(`LoopbackTransferSmokeTest`) proves a provider's export bytes survive the *real* wire and land
correctly via `ApplyProviderRegistry`, and it only covers 1 of 14 item kinds (item #3). The one
instrumentation test that touches real Android content providers
(`ProviderDeviceContractTest.kt`) requires a physical/emulated device reachable via `adb` through
`scripts/device-contract.sh` — there is no Robolectric-based fallback that would let an agent get
partial confidence on `ContentResolver` interactions without hardware. Several behaviors are
explicitly flagged as hardware-only in comments (the wizard's `NsdManager` interrupt-ignoring bug
at `PrivilegeWizard.kt:148-155`, `splitSourceDirs` population per ADR-006 Phase 1b, the Tier-0
install broadcast→confirm-dialog hop) but these flags exist only as prose comments, not as a
collected, greppable "HARDWARE-ONLY, no JVM substitute exists" registry an agent could consult
before attempting to "fully" verify a fix.

**3. Reproduction paths.** There are no fixtures, recorded sessions, or mock-provider libraries
anywhere in the repo (confirmed exhaustively) — every test constructs its data inline in Kotlin.
This is fine for hand-written regression tests but means a raw bug report has no structured
capture-and-replay path (item #4); an agent has to translate prose into code from scratch every
time, with no way to preserve exactly what a real device produced. The existing hand-rolled fakes
(`class Fake*`/`Mock*`/`Stub*`, 40 across the repo) are a solid foundation to build a shared,
reusable fixture-loading harness on top of, rather than a replacement for one.

**4. Structural obstacles.** The module graph itself is clean and already enforces the two most
important boundaries mechanically: `app-send` never links `:adb-bridge`/`:wizard` (compile-time,
no-escalation invariant) and `app-recv`'s `play` flavor excludes them via source-set split, both
re-asserted by CI. No entanglement was found between `core-model`/`core-transport` (unidirectional)
or between `providers` and `settings-catalog` (one-directional, providers depends on
settings-catalog, never the reverse). The only structural rough edge is the provider
*registration* mechanism being a hand-maintained `listOf(...)` in two `MainActivity.kt` files
rather than something a build-time or test-time check verifies is exhaustive (addressed by item
#1) — this is a completeness gap, not an entanglement problem, and doesn't warrant a module
boundary change.
