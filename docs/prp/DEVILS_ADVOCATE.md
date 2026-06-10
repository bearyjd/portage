# Devil's-advocate pass — `portage` plan

Answers are evidence/reasoning, not reassurance. Where the honest answer is "not yet
proven," it says so and points at the verification that settles it.

## Q1 — Does Tier 0 stand entirely on its own with Shizuku uninstalled?

**Mostly yes, with one correction that *improves* the plan and two real caveats.**

- Contacts (vCard), calendar (ICS), call log, app inventory enumeration, and the
  app-pair's own config are pure Tier 0 — runtime permissions only, no privilege bridge.
  These stand alone. ✔
- **Correction (in Tier 0's favor):** a whole slice of "settings sync" the PRP parked in
  Tier 1 is actually Tier 0. `Settings.System` writes need only the user-grantable
  "Modify system settings" (`WRITE_SETTINGS`) special access — no Shizuku. So font scale,
  screen timeout, auto-rotate, brightness *mode*, haptics, UI sounds, and time format
  ship in Tier 0. Tier 0 is *stronger* than the PRP claims.
- **Caveat 1 — app install is one-tap-per-app, by design.** Without Shizuku the receiver
  fires `PackageInstaller` intents; each app is one user confirmation, and the *source*
  (F-Droid/Aurora/Play) must be present to resolve. Inventory "restore" is really
  "assisted re-download," and the UI must say so or it will feel broken.
- **Caveat 2 — SMS/MMS restore is the soft spot** (see Q4). Reading SMS is Tier 0;
  *writing* requires becoming default SMS app. That works without Shizuku but is the
  jankiest Tier 0 path and must be opt-in and clearly bounded.

So: Tier 0 functions with Shizuku absent, *provided* the UI honestly frames inventory as
assisted-reinstall and SMS-restore as an opt-in handoff. Prove with the §9.1 gate:
uninstall Shizuku, run a full transfer, confirm every Tier 0 item completes.

## Q2 — Any DEVICE_SPECIFIC key leaking into the default sync set?

**Not in the design — and the architecture makes leakage structurally hard — but it
needs the compile-time audit to *prove* it.**

- Default set = SAFE only; receiver applies a key **only if present in its compiled
  catalog**; the sender's manifest cannot add keys. So a leak requires *us* to
  misclassify a key as SAFE, not an attacker or a data-driven path.
- The likely misclassifications are the trap list — `SCREEN_BRIGHTNESS` (absolute),
  sound **URIs**, `ENABLED_ACCESSIBILITY_SERVICES`, stream **volumes**,
  `DEFAULT_INPUT_METHOD` — all are explicitly classified DEVICE_SPECIFIC or RISKY in
  `settings_allowlist.md` with the trap spelled out. None are in the SAFE default.
- **Required guardrail for the build agent:** a unit test that asserts
  `defaultSyncSet ⊆ {keys where class == SAFE}` and that the trap-list keys are *never*
  SAFE. Plus VERIFY_FIRST #2: dump both phones' providers and diff what actually got
  written after a Tier 1 run. Until that diff is clean on a real device, treat Q2 as
  "designed-correct, unproven."

## Q3 — Can a same-LAN attacker without the QR observe or inject? Walk the handshake.

**No, assuming a correctly-used Noise implementation. Walk it:**

1. Discovery (mDNS or QR-embedded IP) is **untrusted** — an attacker can lure the
   receiver into connecting to *them*. That only gets them to the handshake.
2. Receiver (initiator) sends `e` (ephemeral public key) — public by design, reveals
   nothing.
3. To answer, the attacker must send msg-2 (`e, ee, s, es`) and later derive transport
   keys that the receiver will accept. Acceptance requires the chaining key to include
   the **PSK** (mixed at psk3). The attacker doesn't have the QR PSK, so:
   - Toward the **receiver**: the attacker cannot produce msg-2/transport frames the
     receiver authenticates → receiver aborts.
   - Toward the **sender**: the attacker cannot produce a valid msg-3 tag → sender
     aborts.
4. A true MITM (relaying between real sender and real receiver) also fails: each leg's
   handshake mixes the PSK *and* fresh ephemerals; the attacker can't sit in the middle
   of a chaining-key computation it can't reproduce. No payload byte is sent before the
   handshake completes, and every byte after is AEAD-sealed.
5. **Replay/second-suitor:** ephemerals are per-session; the sender consumes the PSK
   after one completed handshake and the QR expires in 120 s.

Residual: **DoS** (the attacker can occupy the listener or flood mDNS) — always possible
on a hostile LAN, mitigated only to "fail-closed, retry." And the **honest** residual:
this guarantee is exactly as good as the Noise library and its glue code. Mandate a
security-review of `core-transport`; that module is where this answer can quietly become
false.

## Q4 — Does the SMS default-app handoff cleanly relinquish, or strand the user?

**It can strand the user if implemented naively — this is the highest-UX-risk path in
Tier 0 and needs explicit teardown + a safety net.** Walk the real flow:

- To write SMS/MMS, `portage-recv` must hold the `RoleManager` SMS role
  (`ROLE_SMS`). Acquisition = a system dialog the user accepts.
- The danger: after import, if we don't actively hand the role back, the user is left
  with `portage-recv` as their texting app — and `portage` has no compose/receive UI, so
  incoming texts could be silently dropped.
- **Required teardown:**
  1. Before requesting the role, **record the prior holder**
     (`RoleManager.getRoleHolders(ROLE_SMS)`).
  2. After the last MMS/SMS row is written, immediately prompt the user to restore the
     default SMS app, deep-linking to the role/default-apps settings (an app cannot
     silently *give away* the SMS role to a *specific* other app without user action at
     Tier 0; with live Shizuku, `cmd role add-role-holder` can set it back to the
     recorded holder — verify V7).
  3. **Safety net:** a persistent notification "portage is temporarily your SMS app — tap
     to restore" that survives process death until the role is relinquished, so a crash
     mid-import can't silently strand them.
- **Better path to consider:** make SMS the *last* item in the batch and gate the whole
  SMS feature behind a dedicated screen that explains the handoff up front and won't
  start until the user acknowledges. Don't fold it into the generic checklist.
- On GOS specifically the default-SMS-app mechanism is stock AOSP `RoleManager`; no known
  GOS divergence, but confirm the role-restore path on-device (VERIFY_FIRST #5).

## Q5 — Are we implicitly promising app-*data* transfer we can't deliver?

**The design is clean, but three spots can *imply* it and must be policed in copy and
scope:**

1. **"App inventory" wording.** Users hear "transfer my apps" as "apps *and their
   data/logins*." We deliver package re-acquisition only. The receiver UI must label it
   "Reinstall app list (data stays with Seedvault)" and the done-summary must restate it.
2. **`DEFAULT_INPUT_METHOD` / role / tile keys** create a backdoor implication: if we
   restore "your keyboard," users assume learned dictionary/clipboard came too. It
   didn't. Either label as "selects your keyboard app (its data: Seedvault)" or drop.
3. **The Seedvault blob courier idea** (if `core-transport` is allowed to ferry a
   Seedvault file as an opaque item) blurs the line hardest — it looks like portage moved
   app data. Keep it explicitly framed as "carrying Seedvault's backup file for you,"
   never "portage backs up app data." If this muddies the message, **cut it from v1.**

**Net:** the engine promises nothing it can't do; the *words and labels* are where the
over-promise sneaks in. The §9.5 gate should review every user-facing string for implied
data transfer, and the README's Seedvault division-of-labor framing must lead, not
trail.

## Cross-cutting risks the five questions don't cover

- **GOS auto-reboot (18 h idle)** silently kills a pre-staged Shizuku — design for
  liveness-at-use, never liveness-assumed (ADR §2.2).
- **Owner-profile-only** scope for v1; secondary-profile settings writes are a trap
  (ADR §2.4). State it.
- **Noise library availability** on Android is the single biggest schedule risk; if no
  vetted psk-capable lib clears review, the fallback is `NNpsk0` — plan for it now
  (PROTOCOL §2).

## Confidence + open questions

- Q1, Q2, Q5: **high confidence** the *design* is sound; each carries one concrete
  on-device proof obligation (uninstall-Shizuku run; provider diff; string audit).
- Q3: **high confidence** in the crypto property, **conditional** on library correctness.
- Q4: **medium confidence** — the teardown is designable but is the most likely place a
  real user gets hurt; treat the SMS path as its own mini-project with its own review.
