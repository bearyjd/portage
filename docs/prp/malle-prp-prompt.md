# Claude Code PRP — `malle`: GrapheneOS Device-Parity Transfer (Sender + Receiver)

> Paste this as the initial PRP into Claude Code. It is written for branch-per-feature
> discipline with devils-advocate review gates. Implementing agent: read the **Hard
> Constraints** section before proposing any architecture — most of the "obvious"
> approaches are forbidden by the Android security model and will waste cycles.

---

## 0. One-line intent

I bought a new GrapheneOS phone. I have a working GrapheneOS install on the old one.
I want the new phone to feel like the old phone — settings, contacts, the app set, the
small configuration choices — with a dead-simple two-device transfer over my LAN. No
cloud, no account.

## 1. Brand / repo conventions

- **Brand:** Entrevoix (software). Legal entity: Grepon Labs LLC (Virginia).
- **License:** AGPL-3.0.
- **Project name (suggested, override freely):** `malle` (Fr. *steamer trunk* — the thing
  you pack your belongings into when moving house). Two artifacts:
  - `malle-send` (the old phone — exporter)
  - `malle-recv` (the new phone — importer)
- **Application IDs:** `cc.grepon.malle.send`, `cc.grepon.malle.recv` (adjust if you prefer
  `com.greponlabs.*`).
- **Git:** branch-per-feature off `main`; PRP doc committed under `docs/prp/`; each merge
  gated by a devils-advocate review pass (see §9).
- **Host:** your Gitea (atlas-configs sibling repo) or GitHub under `bearyjd`.

## 2. Hard Constraints (READ FIRST — do not litigate these)

The reason no such app exists: GrapheneOS's own backup (Seedvault) is a **privileged
system app compiled into the OS**. It holds `BACKUP`, `WRITE_SECURE_SETTINGS`,
`INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, `QUERY_USERS`. A sideloaded APK on stock
GrapheneOS **cannot** be granted any of those. Therefore:

- ❌ A normal app **cannot** read another app's private/sandboxed data. (Per-UID sandbox.)
- ❌ A normal app **cannot** write `Settings.Secure` / `Settings.Global`. (`WRITE_SECURE_SETTINGS`
  is signature|privileged; only grantable via ADB or to a system app.)
- ❌ A normal app **cannot** silently install APKs. (Only the per-app installer intent,
  which requires user confirmation for each package.)
- ❌ `adb backup` / full app-data backup is deprecated and effectively dead on modern
  Android — **do not try to reimplement it.** App *data* is Seedvault's job; we defer to it.
- ❌ GrapheneOS-specific per-app toggles (network/sensors permission, exploit protection,
  per-app storage scopes) are stored in privileged state. Treat as **uncertain / best-effort**
  even with Shizuku; verify empirically, do not assume.

### The privilege bridge: Shizuku (default decision)

We accept **Shizuku** as an optional dependency to unlock Tier 1. Shizuku runs an
ADB-privileged binder service started via Wireless Debugging — **no root, works on
GrapheneOS**. UX caveat to surface in-app: on GOS the Shizuku service must be restarted
after each reboot unless the device is rooted. Tier 0 must work with Shizuku absent.

> **DECISION POINT for the human (JD):** default is Shizuku-first. If you instead want
> (a) pure-unprivileged only — drop Tier 1 entirely; or (b) rooted — replace the Shizuku
> binder with a root shell provider behind the same `PrivilegedOps` interface. Either swap
> is localized to one module (§4, `:privileged`).

## 3. Capability tiers (the actual scope)

### Tier 0 — no special privilege (must always work)
- Contacts (`ContactsContract`, READ/WRITE_CONTACTS) → vCard 4.0 export/import.
- Calendar (`CalendarContract`) → ICS export/import.
- Call log (READ/WRITE_CALL_LOG).
- SMS/MMS export (read with READ_SMS; restore requires receiver to be temporary default
  SMS app — implement the default-SMS-app handoff dance, then relinquish).
- **App inventory:** enumerate user-installed packages (`QUERY_ALL_PACKAGES` is *normal*
  on Android for listing; GOS allows it but confirm). Export `{packageName, installerSource,
  versionCode}`. Receiver presents a checklist and fires install intents (F-Droid / Aurora /
  Play per source). Document that each install needs one user tap without Tier 1.
- App-pair's own config.

### Tier 1 — requires Shizuku (graceful-degrade if unavailable)
- **Settings sync:** read/write a curated, **allow-listed** set of `Settings.System`,
  `Settings.Secure`, `Settings.Global` keys via `pm`-level privilege. **Never** blindly
  copy all keys (device-specific keys will brick UX). Maintain `settings_allowlist.kt`
  with categories: display/brightness/font scale, accessibility, sound/notification
  behavior, input/keyboard, developer-options subset, locale/time format. Each key tagged
  `SAFE | RISKY | DEVICE_SPECIFIC`; only `SAFE` synced by default, `RISKY` opt-in,
  `DEVICE_SPECIFIC` excluded.
- **Batched app reinstall** via privileged install (still confirm-per-batch in UI).
- **Best-effort** per-app runtime permission grants on the new device to match the old
  (via `pm grant`), gated behind explicit user opt-in and clear warnings.

### Explicitly deferred (NOT in scope — tell the user to use Seedvault)
- App internal data / databases / login sessions.
- Home-screen / launcher layout (not exposed to non-launcher apps).
- GOS per-app security toggles (flag as research item, do not promise).

Frame the product to the user as: **"Seedvault moves your app data; `malle` moves the
settings-and-parity layer Seedvault misses, directly phone-to-phone."**

## 4. Module layout

```
malle/
├─ app-send/                 # malle-send (exporter) — Compose UI
├─ app-recv/                 # malle-recv (importer) — Compose UI
├─ core-model/               # shared transfer manifest + payload schema (serialization)
├─ core-transport/           # LAN pairing + encrypted channel (see §5)
├─ providers/                # Tier-0 readers/writers (contacts, calendar, sms, calllog, inventory)
├─ privileged/               # PrivilegedOps interface; ShizukuPrivilegedOps impl (swappable)
├─ settings-catalog/         # allow-list + key metadata (SAFE/RISKY/DEVICE_SPECIFIC)
└─ docs/prp/                 # this file + ADRs
```

Keep `privileged` behind a single interface so the root/no-priv swaps in §2 stay local.

## 5. Transport design (no cloud)

- **Discovery/pairing:** sender displays a QR code encoding `{ip, port, ephemeral_pubkey,
  session_nonce}`; receiver scans. (NSD/mDNS as fallback discovery; QR is the trust anchor.)
- **Channel:** direct TCP over the LAN. **Mutually authenticated, encrypted** — use a
  Noise-protocol handshake (e.g. `Noise_XX`) or libsodium box keyed off the QR-exchanged
  ephemeral keys. No data leaves the LAN; no relay.
- **Manifest-first protocol:** sender sends a signed manifest of available items + tiers;
  receiver picks what to pull; then streamed payloads, each independently
  integrity-checked. Resumable per-item; one failed item never aborts the batch.
- **Threat model note:** assume the LAN is semi-trusted; the QR exchange is what prevents
  a same-network attacker from MITM. Document this.

## 6. Tech stack

- Kotlin, Jetpack Compose (Material 3), single-activity per app.
- Target the **current GrapheneOS Android version** (verify at build time — do not hardcode
  to an old API like Seedvault's older branches; check the live target). `minSdk` set to the
  oldest GOS-supported Pixel you care about.
- Shizuku: `dev.rikka.shizuku:api` + `:provider`.
- Serialization: `kotlinx.serialization`. Crypto: libsodium (lazysodium) or a vetted Noise lib.
- No analytics, no network permission beyond LAN sockets, no Google Play Services.

## 7. UX target (the "dead simple" requirement)

- Old phone: open `malle-send` → "Transfer to new phone" → show QR.
- New phone: open `malle-recv` → scan → see a single checklist grouped by category with
  sane defaults pre-checked → "Bring it over" → progress → done summary (what moved, what
  needs a manual tap, what to use Seedvault for).
- Tier 1 is presented as an optional "Unlock advanced settings transfer (Shizuku)" step
  with a 3-line setup guide; everything in Tier 0 works without ever seeing it.

## 8. Deliverables

1. Two installable APKs (`malle-send`, `malle-recv`), reproducible debug build.
2. `settings_allowlist.kt` with at least the SAFE-tier keys populated and annotated.
3. `PROTOCOL.md` documenting the pairing + transfer wire format.
4. `THREAT_MODEL.md` (LAN trust, QR anchor, what's encrypted).
5. README with the Seedvault division-of-labor framing and the Shizuku setup steps.
6. Per-feature branches; final integration on `main` only after §9 passes.

## 9. Devils-advocate review gate (run before merging to `main`)

The reviewing pass must answer, with evidence from the running build:
1. Does Tier 0 fully function with Shizuku **uninstalled**? Prove it.
2. Has any `DEVICE_SPECIFIC` settings key leaked into the default sync set? Audit the list.
3. Can a same-LAN attacker without the QR observe or inject payload? Walk the handshake.
4. Does the SMS default-app handoff cleanly relinquish afterward, or does it strand the
   user as default SMS? Verify the teardown path.
5. Are we anywhere implicitly promising app-*data* transfer we can't deliver? Kill it.

## 10. First tasks for the agent (in order)

1. Scaffold the monorepo + modules in §4; wire AGPL headers + Entrevoix/Grepon Labs notice.
2. Verify on a real GOS target: (a) `QUERY_ALL_PACKAGES` listing works as a normal perm;
   (b) Shizuku starts and `WRITE_SECURE_SETTINGS`-class ops succeed via the binder. Record
   findings in `docs/prp/ADR-001-privilege-feasibility.md` **before** building UI. If (b)
   fails on current GOS, stop and report — that's a go/no-go for Tier 1.
3. Implement `core-transport` pairing + a hello-world encrypted manifest exchange between
   two devices. This is the riskiest piece — prove it early.
4. Then Tier 0 providers, then receiver checklist UI, then Tier 1 behind Shizuku.

---
**Open questions for JD to answer inline before/at kickoff:**
- Confirm Shizuku-first (vs. pure-unprivileged or rooted).
- Confirm `malle` naming + `cc.grepon.*` app IDs, or supply alternates.
- Which Pixel generations must be supported (sets `minSdk`)?
- Gitea or GitHub for the repo?
