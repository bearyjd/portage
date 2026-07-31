# ADR-008 — Seedvault Restore Trigger: Scope & Consent

**Status:** PROPOSED — awaiting the mandatory `security-reviewer` scope-discipline sign-off
(repo cadence: privilege-boundary changes get an independent security lane before merge).
**Date:** 2026-07-31
**Tracks:** #118 (this ADR) · blocks #119 (on-device spike) and #120 (implementation)
**Parents:** #116 (story) → #114 (epic, northstar lever B)
**Source:** `docs/prp/features/northstar-gap-audit.md` §2B / §4 — the audit's ★ top-ranked lever.

> This ADR defines the **boundary**, not the verdict. It is deliberately written **before** the
> on-device spike (#119) so the spike tests inside a fixed scope rather than discovering one. If
> #119 returns NO-GO, this ADR stays on file as the recorded boundary and #120 is closed unbuilt.

---

## 1. Decision

portage MAY, on the receiving device, **ask Seedvault to restore app data** for apps that portage
itself just carried across — via the existing shell-uid ADB bridge, behind an explicit per-session
opt-in, and **without portage ever touching backup data**.

Concretely, portage is permitted to:

1. Probe whether the restore path is reachable, as a new
   `AdbBridge.PrivilegedCapability.BACKUP_RESTORE`, alongside the existing `SMS_ROLE` /
   `SILENT_INSTALL` probes.
2. Invoke **one new typed operation** on `AdbBridge` — never raw `shell()` from any other module:
   ```
   suspend fun restoreAppData(token: String, packages: List<String>): OpResult
   ```
   backed by `bmgr restore <token> <pkg> [<pkg>…]`, routed through the existing
   `ShellArgs.command(...)` metacharacter rejection like every other typed op.
3. Surface the outcome honestly per package, using the per-item failure-surfacing pattern already
   shipped for the Done screen (U3a, PR #111).

Everything not on that list is out of scope. §3 states the prohibitions as testable invariants.

---

## 2. Rationale — courier, not absorber

portage's hardest scope rule (CLAUDE.md, "Scope discipline") is that **Seedvault owns app data;
portage owns settings / inventory / parity** and never carries a `seedvault.blob`. The obvious
misreading of "portage restores app data" would breach that rule outright.

This decision does not do that. The distinction that makes it safe is a **control-plane / data-plane
split**:

| | Control plane (portage) | Data plane (Seedvault) |
|---|---|---|
| Who decides a restore starts | portage, after user opt-in | — |
| Who reads the backup set | — | Seedvault |
| Who decrypts it | — | Seedvault (its own passphrase/keys) |
| Who writes app-private data | — | The platform's backup transport |
| What portage observes | a per-package success/failure verdict | nothing else |

portage issues an **imperative** and reads a **verdict**. The bytes never enter portage's address
space, never cross portage's wire protocol, and never become an `ItemKind`. This is the same shape
as the already-shipped transient SMS-role op: portage briefly exercises a platform capability it
does not permanently hold, to make the platform do the thing that platform owns.

It is also the same shape the repo already accepted for `APP_BACKUP_RELAY` (PRP-06), where portage
ferries a **user-exported, opaque** file it never decrypts — except here portage carries even less,
because it carries nothing at all.

**Why it matters:** it converts portage's weakest sentence ("portage can't move your app data")
into its strongest ("portage hands off the baton"), without moving the scope line.

---

## 3. Prohibitions (the load-bearing half)

These are not aspirations; each is meant to be checkable in review, and where noted, in CI.

portage MUST NOT:

1. **Read, write, parse, decrypt, sniff, hash, stage, or transfer backup data** in any form. No file
   handle to a backup blob is ever opened by portage code.
2. **Introduce any `ItemKind`** for app data. There is no `seedvault.blob`, and no item kind whose
   payload is app-private data. *(Enforceable: the `ItemKind` enum is a compiled, reviewed
   allowlist.)*
3. **Trigger a BACKUP.** The permitted verb is restore only. portage never causes app data to be
   written *out* to any destination — that removes the entire exfiltration shape from the design.
4. **Change the backup transport, enable/disable backup, or touch Seedvault's passphrase, keys, or
   storage location.** If Seedvault is not already the user's configured, active transport, portage
   reports that and stops. portage never configures Seedvault on the user's behalf.
5. **Restore packages the user did not receive in this transfer.** The package list is derived from
   the completed manifest of the current session, intersected with what actually installed. A
   package name that did not come from the verified manifest can never reach the bridge.
6. **Perform a whole-device / whole-set restore.** The bare `bmgr restore <token>` form (restore
   *everything* in the set) is explicitly out of scope: its blast radius is not what the user
   consented to when they consented to moving *these* apps. Package-scoped only.
7. **Run in the Play flavor.** The play build has no bridge at all; this feature is degoogle-only,
   expressed by source set (`app-recv/src/degoogle/…`) exactly like every other privileged feature.
   No new manifest surface in `src/main`. *(Enforceable: the existing play-recv no-bridge CI
   assert.)*
8. **Hold shell uid open.** Acquire → invoke → `disconnect()` in a `finally`, matching the wizard's
   probe discipline and the SMS role's acquire/write/relinquish shape.
9. **Claim success it cannot observe.** See §5.

---

## 4. Consent

**Opt-in, explicit, per session, never default-on, never pre-checked.** Precedent: the SMS role and
the opt-in permission grants (`ReceiverViewModel.grantOptIn`) — portage already refuses to use a
privileged capability just because it happens to have it.

Requirements:

- The step is offered **after** portage's own apply completes and after APKs install (app data can
  only restore into an installed app), as a distinct action on the Done screen — not a step the user
  is swept through.
- The consent copy must name, in plain language: that **Seedvault** performs the restore, that
  **portage does not see the data**, and **which apps** are in scope. A count is not sufficient; the
  list is available.
- Declining is a first-class outcome that leaves everything else the transfer accomplished intact.
- No silent retry. A failed restore is reported, not re-attempted behind the user's back.

**Why consent is non-negotiable here:** the shell path shows **no system confirmation dialog**. The
platform will not ask on portage's behalf, so the entire consent burden sits in portage's UI. This
is the same reasoning that made the generic role restore (#122) opt-in, and it is the reason this
ADR exists before the spike.

---

## 5. Failure honesty

`bmgr restore` is asynchronous and reports coarsely. portage must not paper over that. Distinct,
user-visible outcomes:

| Condition | Surfaced as |
|---|---|
| Bridge unavailable / `BACKUP_RESTORE` capability not live | The step is not offered at all (no dead button) |
| Seedvault not installed | "Seedvault isn't installed — nothing to restore from" |
| Seedvault installed but not the active transport | "Seedvault isn't your active backup — set it up in Settings first"; portage does **not** offer to do it |
| No backup set / no backup for a package | Per-package "no backup found" |
| Restore reported failure | Per-package failure, with the reason surfaced |
| Restore reported success | **"Handed off to Seedvault"** — not "restored" |

That last row is the important one. portage cannot verify the data actually landed without reading
app-private data, which §3.1 forbids. So the honest claim is that the restore was *handed off and
accepted*, and the UI must say that. Overclaiming here would be a correctness bug, not a copy nit.

---

## 6. Security posture

**Capability breadth (accepted, mitigated).** `com.android.shell` (uid 2000) holds
`android.permission.BACKUP`, which is why this is reachable at all — the same reachability class as
the already-verified SMS-role op. That permission is broad: it can initiate a restore for *any*
package. portage narrows it by construction — manifest-derived package list (§3.5), no whole-set
form (§3.6), opt-in (§4), transient connection (§3.8). The residual is that a portage build *could*
ask for more than it does; that is bounded by code review and the AGPL-published source, the same
control that bounds every other bridge verb.

**Input trust.** Package names originate in the transfer manifest, which arrives over the
PSK-gated, mutually-authenticated Noise channel and is validated on receipt. They are re-validated
against the package-name grammar before reaching `ShellArgs.command(...)`, which independently
rejects shell metacharacters. A hostile sender therefore cannot inject a command, and cannot name a
package outside what it actually shipped.

**No new network, storage, or permission surface.** No new `uses-permission`. No new listener. The
transport is the existing localhost-only ADB channel (ADR-003).

**Threat-model delta.** Adds one new privileged verb to the degoogle receiver. It does **not**
widen the privilege boundary (the bridge already exists and already runs shell-uid ops), does not
change the sender (which still links no privilege stack), and does not touch the wire protocol.

---

## 7. Options considered

| Option | Verdict |
|---|---|
| **A. Package-scoped `bmgr restore <token> <pkgs>` via the bridge, opt-in** | **CHOSEN.** Narrowest form that delivers the outcome. |
| B. Whole-set `bmgr restore <token>` | Rejected — blast radius exceeds what the user consented to (§3.6). |
| C. portage carries app data itself | Rejected — the scope rule exists precisely to forbid this. Also infeasible without root. |
| D. Deep-link the user into Seedvault's own restore UI (no privilege) | **Kept as the NO-GO fallback.** Zero privilege, zero scope risk, worse UX (user re-navigates and re-chooses). If #119 says no-go, do this instead — it needs no ADR. |
| E. Ask GrapheneOS/Seedvault for a supported restore intent | Right long-term answer; out of portage's control and far slower than the release. Worth raising upstream regardless (cf. ADR-003 §6, the OS-integration surface). |

---

## 8. Verification gates

Nothing ships on this ADR alone.

1. **#119 on-device spike (GOS A16), before any feature code.** Must establish: `bmgr restore`
   reaches Seedvault's transport; it does not silently no-op; GOS has not stripped `BACKUP` from its
   Shell package; and the per-package form behaves. Recorded as a dated spike-results doc following
   the `SPIKE-RESULTS-2026-06-12.md` pattern, with command transcripts. Precondition discipline:
   Seedvault configured as active transport **with a completed backup**, owner profile only.
2. **security-reviewer sign-off on this ADR** (privilege boundary) — the gate on #118 itself.
3. **security-reviewer on the #120 implementation PR**, mandatory, separately from authorship.
4. **JVM tests on the state machine** — capability-absent, Seedvault-absent, transport-inactive,
   per-package failure, consent-declined. The real `bmgr` call is hardware-only, so the seam is a
   `Store`-style interface per the repo's provider-authoring convention.
5. **E2E runbook gains a section** (feeds #128 / §F sign-off).
6. **CI:** the existing play-recv no-bridge assert must stay green, unmodified.

---

## 9. Consequences & open items

**If GO:**
- portage's headline claim changes from "settings + inventory parity" to "settings + inventory
  parity, and it hands your app data to Seedvault." That is a real product-story upgrade and should
  be reflected in README/store copy — carefully, without implying portage backs up data.
- One new bridge verb, one new capability, one new opt-in step. No protocol change.

**If NO-GO:** fall back to option D (deep link into Seedvault), close #120, keep this ADR as the
recorded boundary. Cost of having written it first: one document.

**Open items:**
- `bmgr`'s exact output grammar on GOS A16 is unknown — the per-package verdict parsing is
  spike-derived and must be treated as untrusted text (bounded, control-stripped) like every other
  shell output portage reads.
- Whether a restore can be triggered for an app installed *in the same session* without a
  reboot/settle delay is unverified. #119 should record it.
- Multi-user / secondary profile: out of scope. Owner profile only, per the established
  grant-architecture fact (ADR-001).
- Interaction with #86 (silent install degrading to Tier-0): if apps land via the Tier-0 tap path,
  the restore step must wait for the installs the user actually confirmed. Sequencing is #120's
  problem, but it is named here so it is not discovered late.

---

## 10. Relationship to other ADRs

- **ADR-003** (self-contained privilege): this uses that bridge unchanged. `AdbBridge` remains the
  only entry point; the new verb is a typed op, and raw `shell()` stays inside `:adb-bridge` —
  including for `:wizard`, per the CI-enforced rule.
- **ADR-007** (unification & distribution): degoogle-only, consistent with §6.3's flavor controls.
  Under the eventual unified app, this belongs to the receive feature module and must not widen the
  sender's surface.
- **PRP-06** (app-backup relay): adjacent but distinct — PRP-06 ferries a user-exported opaque file;
  this triggers a restore and ferries nothing. Both are "courier, not absorber."

---

## 11. Addendum — #119 spike outcome (2026-07-31, GOS A17)

This ADR was written before the spike, deliberately. The spike has since run and returned **GO**
(`docs/prp/features/SPIKE-RESULTS-2026-07-31.md`). It **confirmed** the boundary above rather than
reshaping it; the changes below are tightenings, not reversals.

**Confirmed:**

- `com.android.shell` holds `android.permission.BACKUP: granted=true` on GOS Android 17 — the
  feasibility assumption in §1 holds.
- `bmgr restore <token> <pkg>` reaches Seedvault's transport and genuinely runs, reporting a
  per-package verdict rather than silently no-opping.
- **The bare `restore <package>` form is rejected by the platform outright.** The two-argument form
  §1 chose is the only one available, which also means §3.6's prohibited whole-set restore cannot be
  reached by a careless argument slip.

**Closes an open item from §9** — the output grammar is now known:

```
restoreStarting: <n> packages
onUpdate: <index> = <package>
restoreFinished: <code>      # 0 = TRANSPORT_OK
done
```

Per §6 this is still **untrusted text**: bound it, strip control characters, and treat any
unparseable form as failure rather than success. Do not infer success from the absence of an error.

**NEW gap this ADR must close before #120 implements (added to §9):**

- **Token selection is unspecified.** `bmgr list sets` returned *two* restore sets on the test
  device, including one belonging to a **different phone** (a Pixel 9 Pro Fold) alongside the local
  one. That multi-set case is portage's actual use case — old phone → new phone — so "which backup
  set" is a real user-facing choice, not an implementation detail.

  portage MUST NOT guess a token. Restoring from the wrong set would write another device's app data
  over the user's, which is a data-loss shape this ADR has no business enabling silently. The
  consent step in §4 must therefore name the set being restored from, and a multi-set device must
  surface the choice explicitly.

- **Framework backup state:** the restore was exercised with Backup Manager toggled on. Whether a
  restore is possible while the framework's backup scheduling is disabled was not isolated. #120
  must not assume it is.

**Still unexercised** (see spike §1.5): a third-party app's data, restoring into an app installed in
the same session, and the honest-failure paths of §5 (Seedvault absent / not the active transport /
no set) — the test device had a working Seedvault, so those branches were never taken.
