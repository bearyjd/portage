# portage — Issue Decomposition Plan (2026-07-07)

**Status: CREATED on GitHub 2026-07-07.**
Mapping: E1=#114 · E2=#115 · S1.1=#116 · S1.2=#117 · T1–T11=#118–#128 in plan order.
#61/#86 labeled `bug` + area labels.
Repo: `bearyjd/portage` · CI green (build + dependency-audit) · 2 open issues (#61, #86) —
both kept, not duplicated. Note: working tree is on `fix/pin-lifecycle-2.10.0` (open PR);
this plan file is untracked and rides along or lands with the docs exception.

## Sources consulted

- `README.md`, `CLAUDE.md` (working cadence: branch-per-feature, mandatory independent
  review, security-reviewer for crypto/privilege/wire changes)
- `docs/prp/features/northstar-gap-audit.md` (2026-07-04) — the roadmap decision doc;
  lever A already shipped (PR #112), lever B is the open frontier
- `docs/prp/open-questions.md` — PRP-01 Wi-Fi spike questions, all unresolved
- ADR-003 §7 remaining release gates (per CLAUDE.md "Established facts")
- `gh issue list --state all`: 7 issues; open = #86 (silent install degrades to Tier-0),
  #61 (C2 SMS broken on GOS)
- TODO/FIXME sweep of `*.kt`: zero hits (clean tree)

## Glossary used in the issues (spelled out per issue too)

- **GOS** = GrapheneOS. **Tier 0** = no special privilege. **The bridge** = portage's
  self-contained ADB shell-uid bridge (`:adb-bridge`, ADR-003) — the only allowed entry
  point to privileged operations. **Seedvault** = GrapheneOS's app-data backup app;
  portage's scope line is *courier-not-absorber*: it may hand off to Seedvault, never
  carry app data itself.

## Proposed hierarchy

| Tier | Count | Items |
|---|---|---|
| Epic | 2 | E1 northstar lever B (privileged handoffs) · E2 release readiness (ADR-003 §7 + E2E sign-off) |
| Story | 2 | S1.1 Seedvault restore trigger · S1.2 default-app role restore |
| Task | 10 | T1–T7 under E1 · T8–T11 under E2 (E2 direct-task, flagged thin) |

Existing #61/#86 stay the canonical bugs; T9 depends on #86. Labels to create: `epic`,
`story`, `task`, `spike`, `area:privilege`, `area:providers`, `area:release`, `size:S`,
`size:M`.

---

## EPIC E1 — Northstar lever B: privileged handoffs (spike-gated)

**Labels:** `epic`, `area:privilege`, `enhancement`

The gap audit's remaining recommended path: use the existing shell-uid bridge for two new
capabilities — triggering a Seedvault app-data restore (the "all data" pillar, without
portage ever touching a blob) and restoring default-app roles (browser/dialer/home
selection). Both are explicitly **spike-gated**: on-device go/no-go before any feature
code. Plus the low-ranked Wi-Fi partial lever (spike only) and one hygiene fix the audit
flagged.

### STORY S1.1 — Seedvault restore trigger ("hand off the baton")

**Labels:** `story`, `area:privilege` · Parent: E1

After portage's parity transfer, the receiver can kick off a Seedvault app-data restore
in the same session via the bridge (`bmgr restore`), turning "portage can't touch app
data" into "portage hands off to the tool that owns it." Scope-sensitive: sits right on
the Seedvault line, so the ADR and security review come first.

#### TASK T1 — ADR: Seedvault restore trigger scope & consent (before any code)

## Summary
Write the Architecture Decision Record defining exactly what "portage triggers a Seedvault restore" may and may not do, and pass it through a security-review lane, before any spike code ships in a build.

## Why (context a newcomer wouldn't have)
- portage's hardest scope rule (CLAUDE.md "Scope discipline") is that Seedvault owns app *data*; portage owns settings/inventory/parity and never carries a `seedvault.blob`. The gap audit (§4.1) green-lights the restore *trigger* only with "a `security-reviewer` scope-discipline pass + an ADR before any code — the courier-not-absorber framing must be explicit from day one."
- Parent: S1.1 → E1. Source: `docs/prp/features/northstar-gap-audit.md` §2B/§4.

## Scope (what to touch)
- New `docs/prp/ADR-008-seedvault-restore-trigger.md` (next free ADR number — confirm). No code.
- Must cover: the exact bridge verbs (`bmgr restore <pkg>` / `<token>`), what portage never does (read/produce/inspect backup data), consent UX (opt-in, per the SMS-role precedent), failure honesty (Seedvault not active / no backup), and Play-flavor exclusion (the play flavor strips the privilege surface).

## Acceptance Criteria
- [ ] ADR merged with an explicit reviewer sign-off recorded (repo cadence: independent review lane, security reviewer mandatory for privilege-boundary changes).

## Implementation notes
- Repo convention: ADRs live in `docs/prp/`, follow ADR-001..007's structure; docs changes that encode decisions "still get a glance" even under the docs exception.

## Testing / Definition of Done
- Review sign-off; T2 (spike) references the ADR's boundaries verbatim.

## Size
S

## Depends on
none — blocks T2, T3

## Labels
task, area:privilege, size:S

#### TASK T2 — On-device spike: `bmgr restore` reachable via the bridge on GOS A16

## Summary
Prove or refute on real GrapheneOS Android 16 hardware that portage's shell-uid bridge can trigger a Seedvault restore: `bmgr restore` reaches Seedvault's transport, doesn't silently no-op, and GrapheneOS hasn't stripped the `BACKUP` permission from its Shell package.

## Why
- This is the audit's ★ top lever, and it's unverified on metal — the feasibility rests on AOSP Shell holding `android.permission.BACKUP` (same reachability class as the verified SMS-role op), which GOS may have changed. A no-go here kills S1.1 cheaply. Parent: S1.1 → E1.

## Scope
- Spike only: manual/scripted bridge session on a GOS A16 device with Seedvault configured + a real backup; results recorded in `docs/prp/features/` (SPIKE-RESULTS pattern, cf. `SPIKE-RESULTS-2026-06-12.md`). No app code merged.

## Acceptance Criteria
- [ ] A dated spike-results doc records go/no-go with evidence (command transcripts, restore observed or the exact failure), including both `<pkg>` and `<token>` forms.

## Implementation notes
- Precondition discipline: Seedvault must be the active transport with a completed backup on the *sending-profile* device — record the setup so the result is reproducible. Owner profile only (established grant-architecture fact).

## Size
S

## Depends on
T1

## Labels
task, spike, area:privilege, size:S

#### TASK T3 — Implement the Seedvault restore handoff (go-path only)

## Summary
Add the opt-in "restore app data via Seedvault" step to the receiver flow: a bridge verb that triggers the restore, gated on capability probe + user consent, with honest failure states.

## Why
- Converts the audit's biggest product-story upgrade into shipping behavior — but only if T2 says go. Parent: S1.1 → E1; governed by the T1 ADR.

## Scope
- `:adb-bridge` (new verb — the ONLY module allowed to speak the ADB wire protocol), `:app-recv` flow/UI, degoogle flavor only (play flavor has no bridge — the play-recv no-bridge CI assert must stay green).
- Out of scope: any reading/verifying of backup contents; retry orchestration of Seedvault itself.

## Acceptance Criteria
- [ ] On a GOS A16 device with a Seedvault backup, the receiver flow triggers the restore end-to-end after explicit opt-in, and when Seedvault is absent/unconfigured the step reports that honestly instead of failing silently — with unit tests on the state machine and the E2E runbook gaining a section.

## Implementation notes
- Mirror the SMS-role pattern: transient privilege use, wizard-style consent, disconnect after the operation (never hold shell uid). Security-reviewer lane is mandatory for this PR (privilege boundary).

## Size
M

## Depends on
T1, T2 (go verdict)

## Labels
task, area:privilege, size:M, enhancement

### STORY S1.2 — Default-app role restore (browser/dialer/home selection)

**Labels:** `story`, `area:privilege` · Parent: E1

Generalize the shipped transient-SMS-role mechanism: after reinstalling apps, restore the
user's previous default-app choices for qualifying roles via `cmd role add-role-holder`.
Consent-sensitive: the shell path shows **no** user-confirm dialog, so it must be opt-in.

#### TASK T4 — On-device spike: `cmd role add-role-holder` flips defaults and survives reboot

## Summary
Verify on GOS A16 that `cmd role add-role-holder android.app.role.BROWSER <pkg>` via the bridge actually changes the default browser, that the change survives reboot, and that role qualification (target app declares the role's components) behaves as documented.

## Why
- Audit §4.1's second spike: decides whether a generic `AdbBridge.setRoleHolder(role, pkg)` gets built beside the SMS one. `MANAGE_ROLE_HOLDERS` sits in the Shell manifest, but GOS behavior is unverified. Parent: S1.2 → E1.

## Scope
- Spike only; results doc in `docs/prp/features/`. Try BROWSER plus one more role (e.g. DIALER) to check generality.

## Acceptance Criteria
- [ ] Dated spike-results doc records go/no-go per tested role with reboot-survival evidence.

## Size
S

## Depends on
none (parallel with S1.1)

## Labels
task, spike, area:privilege, size:S

#### TASK T5 — Implement generic role restore with opt-in consent

## Summary
Add `AdbBridge.setRoleHolder(role, pkg)` and an opt-in receiver step that restores captured default-app roles for apps that made it across, with per-role qualification checks.

## Why
- The shell path bypasses the system's role-change confirm dialog — power without consent UX is exactly what this repo's threat model forbids, so the audit requires surfacing it opt-in like SMS. Parent: S1.2 → E1.

## Scope
- `:adb-bridge` (one generic verb), sender-side role capture in the app-inventory provider, `:app-recv` opt-in UI. Degoogle flavor only.
- Out of scope: the SMS role (already shipped, and currently broken on GOS — #61 owns that).

## Acceptance Criteria
- [ ] On device: a captured default-browser choice is restored after reinstall via the opt-in step, roles whose target app is missing/unqualified are skipped with a visible per-item status, and the state machine has JVM tests.

## Implementation notes
- Reuse the per-item failure-surfacing pattern from U3a (PR #111). Security-reviewer lane mandatory.

## Size
M

## Depends on
T4 (go verdict)

## Labels
task, area:privilege, size:M, enhancement

### TASK T6 — Spike: sender-side saved-Wi-Fi read feasibility (PRP-01 open questions)

## Summary
Resolve `docs/prp/open-questions.md` PRP-01's three questions on-device — headline: can the *sender* enumerate saved Wi-Fi networks at all without linking a privilege stack — producing a go/no-go for the "partial Wi-Fi" lever.

## Why
- The audit ranks Wi-Fi below levers A/B ("convenience, not credential parity" — passphrases are root-only per the 2026-06-12 spike) and says "spike the source-side read before committing." The killer constraint: `app-send` must link NO privilege stack (a CI assert enforces this), so a privileged sender read likely means NO-GO. Parent: E1 (direct — deliberately story-less until the spike says go).

## Scope
- Spike only; check off the three PRP-01 boxes in `open-questions.md` with evidence; update `docs/prp/features/PRP-01-wifi-networks.md` with the verdict.

## Acceptance Criteria
- [ ] All three PRP-01 open-questions checkboxes are resolved with on-device evidence and PRP-01 carries an explicit GO / NO-GO / RESHAPE verdict.

## Size
S

## Depends on
none

## Labels
task, spike, area:providers, size:S

### TASK T7 — Fix two stale doc comments that would mislead a future gap scan

## Summary
Correct `ApkExportProvider.kt:24-26` (claims "no producer") and `AppBackupRelayProviders.kt:277-279` (claims "SAF not implemented") — both kinds are wired and shipping via `SenderViewModel.kt:262-263`.

## Why
- Flagged by the gap audit's hygiene aside (§1): stale comments in a repo whose planning runs on doc audits are landmines. Parent: E1 (rides with the audit's other outputs).

## Acceptance Criteria
- [ ] Both comments describe current behavior; no functional change in the diff.

## Size
S

## Depends on
none

## Labels
task, size:S

---

## EPIC E2 — Release readiness: close the ADR-003 §7 gates

**Labels:** `epic`, `area:release`

CLAUDE.md's "Established facts" lists the remaining release gates for the privilege
bridge: reboot-recovery walk (§7.5), silent-install session verdict on GOS (§7.6, tied
to open bug #86), 16 KB native-lib alignment (§7.7), and the formal E2E runbook §F
sign-off. Direct-task epic (flagged thin — these are four independent verification
gates, not slices of one behavior).

### TASK T8 — Gate §7.5: full reboot-recovery walk on hardware

## Summary
Execute ADR-003 §7.5 on a GrapheneOS Android 16 device: after a reboot, the granted `WRITE_SECURE_SETTINGS` persists and the whole privilege chain (pair → connect → probe) recovers without re-pairing surprises; record the evidence in the ADR.

## Why
- The grant architecture's core promise is "one-shot grant persists across reboot"; §7.1–7.4 are verified, §7.5 is the explicitly-open remainder. Parent: E2.

## Acceptance Criteria
- [ ] ADR-003 §7.5 is marked closed with a dated on-device transcript (reboot → settings write succeeds without re-grant; wizard reconnect behavior recorded).

## Size
S

## Depends on
none

## Labels
task, area:release, size:S

### TASK T9 — Gate §7.6: silent-install session verdict on GOS

## Summary
Close the silent-install gate: with a working bridge, a carried APK installs silently (no user tap) and the session verdict is correctly read back — which first requires fixing #86 (SILENT_INSTALL capability not live at apply time, so installs currently degrade to Tier-0).

## Why
- ADR-003 §7.6 is "tied to #86" (CLAUDE.md). The gate is the *verification*; #86 is the *fix*. Kept separate so the bug keeps its own repro/fix lifecycle. Parent: E2.

## Acceptance Criteria
- [ ] With #86 fixed, the E2E evidence shows a bridge-path install completing with no confirm dialog and the correct verdict surfaced per-item; ADR-003 §7.6 marked closed.

## Size
S (verification; the fix effort lives in #86)

## Depends on
#86

## Labels
task, area:release, size:S

### TASK T10 — Gate §7.7: 16 KB native-library alignment check

## Summary
Verify all shipped native libraries (libadb-android/conscrypt/spake2 in the degoogle flavor) meet Android's 16 KB page-size alignment requirement, and wire the check into CI or the release workflow so it can't regress.

## Why
- Android is moving to 16 KB pages; misaligned `.so`s hard-fail there. ADR-003 §7.7 names this a release gate. Parent: E2.

## Scope
- Release/CI workflow (`release.yml` or `build.yml`) + whatever build flags the check demands. No product code.

## Acceptance Criteria
- [ ] An automated check (e.g. `zipalign`-level or `llvm-readelf` alignment assert) runs against the release APKs and passes; ADR-003 §7.7 marked closed.

## Size
S

## Depends on
none

## Labels
task, area:release, size:S

### TASK T11 — Formal E2E runbook §F sign-off

## Summary
Run the post-build two-phone end-to-end verification (`docs/prp/E2E-VERIFICATION-RUNBOOK.md`) through §F on real hardware and record the formal sign-off — the last named release gate.

## Why
- The runbook exists precisely so "release-ready" is evidence, not vibes; §F is called out in CLAUDE.md as unfinished. This run should also sweep the CLAUDE.md "On-device VERIFY_FIRST" list that isn't already covered (WRITE_CALL_LOG-only inserts; null-account contacts visible in the default Contacts app; camera released post-scan; lever A's contact-ringtone aggregation assumption). Parent: E2.

## Acceptance Criteria
- [ ] E2E runbook executed with §F signed off and dated on-device results; any failure found becomes its own issue rather than blocking the record of the run.

## Implementation notes
- #61 (SMS restore broken on GOS) will presumably fail its section — record it against #61, don't re-diagnose here.

## Size
M

## Depends on
T8, T9 (gates feed the same evidence file); #61 ideally fixed first

## Labels
task, area:release, size:M

---

## Existing issues — disposition (no new issues created for these)

- **#86** (silent install degrades to Tier-0): stays the canonical bug; T9 depends on it. Suggest labels `bug`, `area:privilege`.
- **#61** (C2 SMS broken on GOS — role grants but 0 messages write + role not relinquished): stays canonical; also carries CLAUDE.md's open SMS hardware-verify questions (incl. whether `READ_SMS` is needed for role eligibility — drop if not). Suggest labels `bug`, `area:providers`.

## Deliberately NOT filed

- **Notification channels / DND / launcher layout / Wi-Fi passphrases / BT link keys / accounts**: audit §C structural ceilings, explicitly "do NOT chase."
- **Priority-3 (Android→Android) work**: audit recommends not investing.
- **Fable guided-flow polish** (audit §4.3): real but underspecified — needs its own product brief first; premature to decompose.
- **PRP-05 notification-channel parity**: already declined per audit §C.

## Creation order

Labels → E1, E2 → S1.1, S1.2 → T1–T11 → parent checklists; add labels to #61/#86.
