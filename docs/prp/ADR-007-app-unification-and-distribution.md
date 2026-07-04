# ADR-007 — App Unification, Distribution Channels & Platform-Support Posture

Status: **ACCEPTED — pre-implementation.** Direction settled 2026-07-03: **Option A (unify into one
app) with the clean id `com.ventouxlabs.portage`** (owner decision — §13). Design via brainstorm + a
two-lens independent review (security-reviewer + architect) + an adversarial review (all 2026-07-03),
whose defensible findings are folded in (§6.1/§6.3 #3 scoping, §6.3 #7 reset control, §10
verify-before-retire, the Compose-launcher fix). Distribution note: **F-Droid / direct-APK is the
primary channel; Google Play is deferred** — the hardened `play` (lite) flavor is kept ready for a
later submission. The §10 implementation is unstarted and gated on owner go-ahead.
Supersedes the *artifact-topology* half of ADR-003 §9 ("the ADB stack is kept out of a
separate `portage-send` binary") — see §11. Extends ADR-001/ADR-003 (privilege) and ADR-006
(APK keystone). **Re-opens** several ADR-001/ADR-003 hardware-verified "established facts"
because the chosen applicationId change invalidates their package-keyed evidence (see §9).
Decision owner: JD. Drafted 2026-07-03.

## 1. Decision

Collapse the two apps (`portage-send` exporter + `portage-recv` importer) into **one
installable app** whose role — Send or Receive — is chosen at runtime, on a launch home
screen. Ship it as exactly **two packages** on a single `distribution` flavor dimension:

| Package | Flavor | Channel | applicationId |
|---|---|---|---|
| **Full** | `degoogle` | F-Droid + direct APK | `com.ventouxlabs.portage` |
| **Lite** | `play` | Google Play | `com.ventouxlabs.portage.play` |

Structure (approach "A" — the only one that keeps a real compile-time privilege firewall):

- New `:app` application module — **routing-only**: `MainActivity` → role chooser → hands off
  to one feature. Owns the launcher entry, app label, theme, and launcher icons. Declares the
  `distribution` dimension and both applicationIds. Contains no transfer/provider logic.
- `:app-send` → library `:feature-send` — the exporter flow. **No** dependency, direct or
  transitive, on `:adb-bridge`/`:wizard`.
- `:app-recv` → library `:feature-recv` — the importer flow. Declares the `distribution`
  dimension; `degoogleImplementation(:adb-bridge)` + `(:wizard)` live here and nowhere else;
  hosts the `PrivilegeIntegration` seam and its `degoogle`/`play` source sets.

**Lite (`play`) sheds every high-scrutiny / escalation surface**, for *both* roles:
`:adb-bridge`/`:wizard` (compile-excluded), `WRITE_SECURE_SETTINGS`, the SMS family + role
components, `READ`/`WRITE_CALL_LOG`, and `REQUEST_INSTALL_PACKAGES`. Lite is Tier-0 only.

## 2. Rationale

- **One app, pick your role.** Two apps is a confusing install story ("which one goes on the
  old phone?"). A single app with a Send/Receive chooser is the product both phones want.
- **It fixes the distribution funnel.** A migration needs the old phone *and* the new phone.
  With two apps, publishing only the receiver on Play leaves the sender to be sideloaded — a
  broken funnel. One app means both phones install the *same* package from the *same* channel.
- **It makes the full/lite split honest.** "Lite" stops being a marketing word and becomes a
  flavor that provably excludes the escalation stack *and* the Play-restricted permission
  families — enforced at build time and asserted in CI (§8).
- **It reflects the real support story.** portage is GrapheneOS-first; unification lets us
  state that posture once, in one place (§4), instead of implying uniform "any Android" parity
  the test matrix cannot back.

## 3. Options considered

| Option | Verdict |
|---|---|
| **A — two feature libraries + thin `:app`** (chosen) | Only option that preserves a compile-time send firewall (`:feature-send` cannot see the bridge). Keeps per-module namespaces so R/resources don't churn. Lite APK stays *provably* bridge-free. |
| B — one unified module | Rejected: send, recv, and `:adb-bridge` share one classpath; least-privilege degrades to a lint/grep convention. Weakest fit for the threat model. |
| C — keep two apps, extract a shared `:feature-ui` library | The genuine antithesis (strongest guarantee, zero re-verify, zero id change) — but it does **not** deliver the runtime role-chooser product goal. Rejected because the single-app UX is the point. |
| **C′ — two apps + in-app cross-linking, BOTH on Play** | *(added on review — not yet weighed)* Both apps are Tier-0/Play-safe (the sender never escalates; recv-lite is Tier-0), so both CAN be published to Play with in-app "get the other half" links. Captures most of the one-product UX at **none** of the §6/§9/§13 cost. **OPEN DECISION #1 (§13)**. |

> **✅ Decision #1 — A chosen (owner, JD, 2026-07-03).** The adversarial review correctly noted the
> "broken funnel" is overstated (both apps are Play-safe, so C′ could publish both) — so A was **not**
> chosen for the funnel. It was chosen because (a) the **single-app "one download, pick a role" UX is
> the priority** for the target migrator, and (b) with **nothing yet published**, A's timing costs
> collapse: no installed base to orphan (§13), and the §9 re-verify is near-zero incremental (the GOS
> HW walk is pending first-time regardless). The one accepted residual is the §6 escalation downgrade
> (sender binary carries the bridge), mitigated by the §6.3 controls. C′ remains the documented
> fallback if the §6 downgrade is later judged unacceptable.

## 4. Platform-support posture (GrapheneOS-first)

The transfer mechanism is **not** GrapheneOS-specific — settings ride the AOSP
`Settings.System/Secure/Global` providers; the bridge uses stock Android 11+ Wireless
Debugging; transport is plain LAN + Noise. It therefore *runs* beyond GOS, but with tiers:

| Tier | Devices | Posture |
|---|---|---|
| **Verified** | Pixel + GrapheneOS (the substrate all sign-offs are recorded against) | Supported; hardware-verified. |
| **Supported (best-effort)** | AOSP-like Android 12+ (minSdk 31) on unlocked consumer devices | Should work; unverified. Bridge path depends on Wireless Debugging being available. |
| **Degraded-but-safe** | OEM skins (One UI, HyperOS, …) | Vendor settings keys not in the allowlist are **skipped** (SAFE-by-default — the allowlist never mis-applies an unknown key), so coverage thins but nothing corrupts. Unverified. |
| **May fail** | Enterprise / MDM / Knox / locked-down carrier builds | ADB / Wireless Debugging may be restricted; the Tier-1 bridge can fail. Tier-0 paths still apply. |

**Marketing implication:** advertise GrapheneOS-first, "runs on Pixel/AOSP-like Android;
other OEMs best-effort, unverified." Do not claim broad any-Android parity.

## 5. Lite capability matrix

| Domain | Full (`degoogle`) | Lite (`play`) |
|---|---|---|
| Contacts, Calendar | ✅ | ✅ |
| Settings (Tier-0: `Settings.System`, ringtones, wallpaper) | ✅ | ✅ |
| Secure/Global settings (Tier-1, needs the bridge) | ✅ | ❌ (no bridge) |
| App set — store-page reinstall | ✅ | ✅ |
| App set — carried-APK install (system confirm / silent) | ✅ | ❌ (no `REQUEST_INSTALL_PACKAGES`) |
| Bluetooth roster read | ✅ | ✅ |
| **SMS / MMS** | ✅ | ❌ (dropped) |
| **Call log** | ✅ | ❌ (dropped) |

Lite must not *offer* the dropped features in its UI — feature-gated off in the `play`
flavor via a capability flag on the same seam pattern as `PrivilegeIntegration.offersAdvancedSetup`.

## 6. The escalation-boundary change (the load-bearing decision)

### 6.1 What changes
Today (ADR-003), the sender's inability to escalate is an **artifact-topology** fact: a
separate `portage-send` APK that physically contains no `:adb-bridge`/`:wizard`/
`WRITE_SECURE_SETTINGS`/conscrypt/spake2, proven by CI grepping the merged manifest and the
APK per flavor. One binary = the union of code, so the **full** app now contains the bridge
even in Send mode. The guarantee moves from *"the sender binary cannot escalate"* to a
two-part control:

- **(a) Module boundary** — `:feature-send` has no compile edge to `:adb-bridge`/`:wizard`;
  send-role code has no symbol path to escalation. This is machine-checkable **at `:feature-send`
  granularity** (§8, §6.3 #3). NOTE the asymmetry: in the **degoogle** app the bridge IS legitimately
  on `:app`'s runtime classpath (via `:feature-recv`'s `degoogleImplementation`), so the
  `:app`-degoogle boundary is NOT machine-checked — it holds only by the routing-only discipline
  (§6.3 #5). So (a) is a hard wall for `:feature-send` and a *convention* for `:app`-degoogle; it is
  not one uniform automated guarantee, and this ADR should not be read as claiming one.
- **(b) Lazy, receive-gated runtime construction** — the privilege wiring is built only when
  the Receive role is active, never in Send.

### 6.2 Threat-model analysis (why this is acceptable)
Against `THREAT_MODEL.md` §1, the in-scope adversary is same-LAN, cannot see the screen, has
no physical access; a compromised OS on either phone is explicitly out of scope. Escalation
still requires the on-device human ceremony: enable Developer options + Wireless Debugging,
complete the SPAKE2 pairing, and only then can `connect()` proceed — it hard-gates on
`Settings.Global adb_wifi_enabled` (`LocalAdbBridge.kt:78`). **No in-scope adversary and no
THREAT_MODEL property (rows 1–13, §4) gains any capability from the merge.** What is genuinely
lost is a *defense-in-depth / auditability* property, and it is sharper than "carries escalation
bytecode": because ONE app now plays both roles, a phone that was a **receiver** in a prior migration
retains its one-shot `WRITE_SECURE_SETTINGS` grant (ADR-001: persists across reboot, cleared only on
uninstall) **and** its stored ADB identity key when it is later handed down, sold, or repurposed as a
**sender**. Binary-absence previously protected that device even against the OUT-of-scope adversary
(physical access / resale) — precisely the coverage a defense-in-depth property is kept for. That is a
real reduction in assurance, consciously accepted here, and compensated by §6.3 (including the reset
control #7).

### 6.3 Compensating controls (NON-NEGOTIABLE — ship with the merge, not after)
Both reviewers were explicit: the merge silently deletes the current CI proofs and one stated
invariant is already violated by today's code. These are required, not optional:

1. **Lite APK bridge-free assert stays and becomes the *primary* binary gate** — `aapt dump
   permissions` shows no `WRITE_SECURE_SETTINGS`/adbbridge; no `conscrypt`/`spake2` native
   libs. It is now the *only* artifact-level guarantee.
2. **Lite forbidden-permission set extends to SMS/call-log** — `READ_SMS|SEND_SMS|
   RECEIVE_SMS|RECEIVE_MMS|RECEIVE_WAP_PUSH|READ_CALL_LOG|WRITE_CALL_LOG`, the SMS component
   class names, and `REQUEST_INSTALL_PACKAGES`, asserted via `aapt dump permissions` +
   `xmltree` on the packaged lite APK. **Note:** recv-`play` ships SMS/call-log *today*
   (verified: `app-recv/src/main/AndroidManifest.xml` is the single, unflavored manifest, so
   `WRITE_CALL_LOG`, the SMS family, `SmsComposeActivity`, and `REQUEST_INSTALL_PACKAGES` all
   ride into the play APK) — this assert closes a **present** exposure. Because it is live now
   and independent of unification, **do not gate the fix on Phase 3**: close it in a decoupled
   near-term hardening PR (move those permissions + SMS components into an `app-recv/src/degoogle`
   manifest and extend the existing play no-bridge assert to forbid this set), landing **before
   any Play submission**. See §13 Preconditions.
3. **Module-graph assert (correctly scoped).** A Gradle task using `resolutionResult.allComponents`
   that fails if any `ProjectComponentIdentifier` is `:adb-bridge`/`:wizard` in EITHER (i)
   `:feature-send`'s classpaths (ALL flavors — send never sees the bridge) OR (ii) `:app`'s **play**
   runtime/compile classpaths. It must **NOT** assert this for `:app`'s **degoogle** classpath: the
   bridge is *legitimately* there via `:feature-recv`'s `degoogleImplementation(:adb-bridge)`, so a
   naive "walk `:app`" task would false-positive and fail the legitimate degoogle build. That leaves
   the `:app`-degoogle boundary unguarded by the graph task, so back it with a hard rule that `:app`
   holds zero non-routing code (§6.3 #5) — enforced by a containment grep asserting `:app` sources
   reference no `:providers`/transfer symbols. Plus repurpose the per-flavor OSV lockfiles
   (`dependency-audit.yml`) to fail if the **lite** runtime graph resolves `libadb`/`spake2`/
   `conscrypt`. Robust against renames; not a text grep.
4. **Lazy, receive-gated privilege wiring + a test.** Today `MainActivity.kt:136` eagerly
   builds `providePrivilegeIntegration(context).wiring(context)`, which calls
   `AdbBridges.local()` (a process-global `object`) at construction — so the naive port warms
   the singleton in Send mode. Construction is inert (no socket until the WD-gated
   `connect()`), but the design's own invariant is violated. Fix: build the wiring lazily,
   hard-gated on the Receive role; add a test that the Send path never touches
   `AdbBridges`/`PrivilegeWizardHolder`.
5. **`:app` is provably routing-only.** If send-role logic lands in `:app`, it regains
   classpath sight of the bridge in degoogle and control (a) is void. Keep all role code in the
   feature libraries; `:app` declares no `:providers`/transfer types itself.
6. **Physically move all SMS/call-log permissions and components out of every `main`
   manifest** (both features) into `degoogle` source sets — the drop must be structural, not a
   runtime no-op.
7. **Grant + ADB-key reset on role-switch / decommission** (addresses §6.2's resold-device gap).
   Because the unified app can move Receive → Send on the same device, add an explicit reset that
   (best-effort, while the bridge is still granted) `pm revoke`s the one-shot `WRITE_SECURE_SETTINGS`
   grant and deletes the stored ADB identity key + wizard state, offered on (i) entering Send mode on
   a device that previously received, and (ii) a "reset portage / prepare to hand off this phone"
   action. This restores most of the auditability the merge spends: a resold-without-uninstall phone
   can be returned to a no-standing-grant state. (Uninstall still clears it; this covers the
   resold-without-uninstall case binary-absence used to cover for free.)

## 7. Module architecture & build wiring

- `:feature-recv` **must** declare `flavorDimensions += "distribution"` with `degoogle`
  (`isDefault = true`) + `play`, because `degoogleImplementation` only exists when the flavor
  is declared (this is verbatim how `:app-recv` links the bridge today). App↔library match by
  flavor name automatically.
- `:feature-send` is a **flavorless** library — no dimension, and (contrary to a common
  misread) **no `missingDimensionStrategy`**: it's only needed when a *dependency* declares a
  dimension the consumer lacks; here `:app` has every dimension its dependencies do.
- Libraries have no `applicationId`; set the base id + `.play` suffix only in `:app`. Keep
  `buildFeatures { compose = true }` + the compose plugin in each Compose library; keep each
  library's existing `namespace` (`…​.recv` / `…​.send`) so R/resources don't move; `buildConfig`
  stays off (unused).
- The three worst apps-to-libraries hazards are **absent** here (verified): no
  `<provider>`/`android:authorities`/FileProvider, no `BuildConfig` usage, no manifest
  placeholders. The only `applicationId`-coupled runtime code is
  `Uri.parse("package:${context.packageName}")` (`ReceiverApp.kt:292`), which resolves at
  runtime and stays correct.

## 8. CI / boundary asserts (rewritten for the 2-APK model)

- **Remove** the four-APK "sender is bridge-free" asserts (`build.yml`) and the
  `case "*send*|*recv-play*"` signed-artifact asserts (`release.yml`) — those APK names cease
  to exist.
- **Add / keep:** lite-APK forbidden set (§6.3 #1+#2); degoogle-APK positive control (bridge
  *present*); the `:feature-send`/`:app` module-graph task (#3); the OSV lockfile lite-graph
  assert (#3); the raw `AdbBridge.shell()` containment grep, module list extended to
  `feature-send feature-recv app`.
- **Update** `.github/osv/locking.init.gradle.kts` — it hardcodes `app-recv`/`app-send`;
  point it at `:app` (its degoogle + play runtime classpaths cover the full shipped graph).

## 9. Re-opened hardware-verified facts (the applicationId cost)

The clean id `com.ventouxlabs.portage` was chosen over reusing `.recv`. Because the following
sign-offs are **package-keyed** to `com.ventouxlabs.portage.recv`, changing the id invalidates
their evidence — each must be **re-verified on a Pixel/GOS device before release**, and this
ADR formally re-opens them (they are otherwise "don't re-litigate" in CLAUDE.md):

- One-shot `pm grant WRITE_SECURE_SETTINGS` + **reboot persistence** (ADR-001 V2–V8 / ADR-003 §7).
- Transient default-SMS role acquire → write → relinquish + ledger reconcile.
- PackageInstaller install-confirm chain (ADR-006).
- Null-account (device-local) contact writes visible in Contacts.

Crucially, **portage is not yet published to any channel**, and the GOS HW E2E walk (reboot-recovery
§7.5, silent-install #86, runbook §F) is **still pending first-time**. So changing the id does not
*re-open completed* work — it means "run the verification you already owe, once, under the final id."
The incremental cost of the id choice is therefore near-zero, and there is no installed base to orphan.
The earlier "re-verification walk is a cost of the clean identity" framing overstated it for this
pre-publication reality.

> **✅ Decision #2 — clean id `com.ventouxlabs.portage` (owner, JD, 2026-07-03).** The adversarial
> review argued reuse-`.recv` *only* to dodge the re-verify — and that cost is absent pre-publication
> (above). With the re-verify neutral, the clean id wins on the remaining axes: a cleaner F-Droid
> listing and no `.recv` suffix on a role-chooser app. The pending §7 HW walk is simply executed once
> under `com.ventouxlabs.portage`.

## 10. Migration sequencing (CI green at every phase; HW re-verify isolated)

- **Phase 0 (docs, direct-to-main):** this ADR; freeze the two identity answers.
- **Phase 1 (CI green, no HW):** in-place refactor inside the existing apps — extract the
  Activity-scoped logic that must be re-homed into host-agnostic functions/composables:
  send's whole-session `FLAG_SECURE` + orphaned-grant sweep (`app-send/.../MainActivity.kt`),
  recv's staging sweep + the SMS-role `registerForActivityResult` launcher.
  **Resolve the launcher-lifecycle hazard now, not in Phase 3:** a classic
  `Activity.registerForActivityResult` MUST register before STARTED, but the routing-only `:app` →
  role-chooser → *then* compose the recv feature means the recv host is created AFTER `MainActivity`
  is already RESUMED — an Activity-level registration there throws `IllegalStateException`. So the
  SMS-role launcher must use the **Compose `rememberLauncherForActivityResult`** (no before-STARTED
  constraint) inside the recv feature (preferred — host-agnostic, matches the composable model), OR
  be registered unconditionally in `:app` before any routing. This is a Phase-1 design decision, not
  a detail deferrable to the receive-feature conversion.
- **Phase 2 (CI green, light HW):** create `:app`; convert `:app-send` → `:feature-send`
  (delete its `WRITE_SECURE_SETTINGS tools:node="remove"` line — see §12); `:app` routes to
  Send only; `app-recv` untouched (its verified chain + id preserved for now). Swap the
  send-APK asserts for the `:feature-send` module-graph task + the `:app`-play bridge-free
  assert in the same PR. Re-verify is light (Tier-0 export, `FLAG_SECURE`, BT roster on GOS).
- **Phase 3a (convert + VERIFY, `app-recv` still alive):** convert `:app-recv` → `:feature-recv`
  (carry the dimension, `degoogleImplementation` bridge/wizard, the `PrivilegeIntegration` seam +
  `degoogle`/`play`/`testDegoogle`/`testPlay` source sets verbatim); fold Receive routing into
  `:app`; move SMS/call-log to `:feature-recv/src/degoogle`; consolidate `<application>` attrs /
  launcher / `app_name` / `Theme.Portage` into `:app`. **Keep `app-recv` and its existing CI asserts
  intact** as a known-good fallback. Run the §9 re-verification walk **against the new `:app` build**.
- **Phase 3b (retire — ONLY after 3a passes on hardware):** retire `app-recv`; rewrite `build.yml`
  to the final 2-APK asserts and update the OSV init script. Deliberately verify-BEFORE-retire: if the
  §9 walk fails, 3a rolls back cheaply and the project is never left with no shippable receiver.

## 11. Relationship to ADR-003

ADR-003 §9 recorded: *"Providers call AdbBridge → narrow TierOneGrant seam → keeps the ADB
stack out of `portage-send` entirely"* and *"Wizard … Receiver only … CI hard-gates app-send
as escalation-free."* Those remain true **as module/flavor facts** but no longer as
**separate-binary** facts: there is no `portage-send` APK after unification. The privilege
*architecture* (grant model, bridge internals, `ShellArgs`, call-site rule) is unchanged. On
acceptance of this ADR, annotate ADR-003 §9 with a forward pointer here; do **not** rewrite its
verified body.

## 12. Manifest & resource landmines (verified, must-handle in Phase 2/3)

| Landmine | Evidence | Action |
|---|---|---|
| `WRITE_SECURE_SETTINGS tools:node="remove"` in the sender | `app-send/src/main/AndroidManifest.xml:35` | **Delete** on conversion — if it rides into a library manifest it can *strip* the permission the degoogle app legitimately needs from `:adb-bridge`. |
| Conflicting `<application android:label/icon/theme>` + two `LAUNCHER` filters | both apps' manifests | Strip `<application>` attrs + `LAUNCHER` filter from both feature manifests; `:app` owns them (else merger errors on `android:label`). |
| Colliding `@string/app_name`, `@style/Theme.Portage`, launcher mipmaps | both `res/values` | Consolidate in `:app`; keep feature namespaces so other resources don't churn. |
| SMS `SmsComposeActivity` exported (`sms:`/`mms:`, BROWSABLE) ships on the old phone too | `app-recv/.../AndroidManifest.xml:155-167` | Confirm degoogle-only after the split (§6.3 #6); keep inert. |

## 13. Consequences & open items

- **Positive:** one product, one listing per channel; funnel fixed; a machine-checked lite
  boundary that also closes the current recv-`play` SMS/call-log exposure; a single stated
  support posture.
- **Negative / accepted:** sender escalation guarantee downgraded from binary-absence to
  module + runtime gate (§6); a mandatory GOS re-verification walk from the id change (§9);
  bounded but real refactor cost (resource/manifest/Activity consolidation).
- **Preconditions (settle before the dependent work; NOT deferrable to a later phase):**
  1. **Freeze the applicationId before ANY external publication.** No F-Droid MR / Play
     submission may ship under `.recv`/`.send` until the unified id (`com.ventouxlabs.portage`)
     is frozen — unification mints a fresh package identity that existing `.recv`/`.send`
     installs do **not** auto-migrate, so publishing first orphans early adopters. This gates
     the R2-unblocked fdroiddata MR.
  2. **Close the recv-`play` SMS/call-log/`REQUEST_INSTALL_PACKAGES` exposure now, decoupled**
     (§6.3 #2) — it is live in today's shipped play flavor and must land as a standalone
     hardening PR before any Play submission, independent of the multi-phase unification.
- **RESOLVED DECISIONS (owner, JD, 2026-07-03):**
  1. **A (unify) chosen** over C′ — the single-app UX is the priority, and pre-publication timing makes
     A cheap (§3). Accepted residual: the §6 escalation downgrade.
  2. **Clean id `com.ventouxlabs.portage` chosen** — the reuse-`.recv` argument was purely re-verify
     avoidance, which is neutral pre-publication (§9).
- **Preconditions (still binding, now easily satisfiable pre-publication):**
  1. **Freeze the id before ANY external publication** — done (`com.ventouxlabs.portage`); the
     fdroiddata MR / any later Play submission must ship under the unified id, never `.recv`/`.send`.
  2. **Close the recv-`play` SMS/call-log/install exposure** — **DONE** (merged as #107, the decoupled
     §6.3 #2 hardening).
- **Open (mechanics):** decide per-permission whether lite needs `QUERY_ALL_PACKAGES` and
  `BLUETOOTH_CONNECT` (gate the lite permission set to a documented allowlist in CI); the lite
  feature-gating UX (don't render Send/Receive options that lite can't fulfil).
