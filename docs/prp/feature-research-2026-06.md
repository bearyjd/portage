# portage — feature research & ranked backlog (2026-06-12)

Market research to source the NEXT features beyond the landed scope (Tier-0 transfer, Tier-1
settings parity, privilege bridge). Two parallel sourced scans — competitor coverage + GOS/de-
Googled community pain points — synthesized and ranked by **value × feasibility** against
portage's actual privilege ceiling (one-shot `WRITE_SECURE_SETTINGS` via the app's own ADB
bridge; allowlisted `Settings.Secure/Global`; `pm install/grant`; NO root, NO cloud, NO Google
account; owner profile; AGPL; LAN/Noise transport). Excludes already-built categories and
app-*data* blobs (Seedvault's territory — the no-`seedvault.blob` discipline holds).

## Ranked backlog (each gets a PRP under `docs/prp/features/`)

| # | Feature | Tier / privilege | Source signal | Seedvault gap |
|---|---------|------------------|---------------|---------------|
| 1 | Wi-Fi saved networks + passphrases — **DECLINED** (passwords unreadable at any non-root privilege on GOS; see PRP-01) | Tier 1 — privileged read of `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml`; restore via `WifiManager`/suggestions | Every competitor (Google cable, Smart Switch, Swift, Migrate); top "did my stuff move?" question | Yes — skips/flaky |
| 2 | Wallpaper (home + lock) | Tier 0 — `WallpaperManager` get/set `FLAG_SYSTEM`/`FLAG_LOCK`, no privilege | Nearly every competitor moves it | Yes — image bytes ≠ setting |
| 3 | Secure-settings allowlist expansion (Panic config, USB-peripheral hardening, Bluetooth timeout, priority conversations, emergency owner info, starred-contact status, DND/Zen global) | Tier 1 — `settings-catalog` allowlist entries | CalyxOS Seedvault docs name these as explicit exclusions; on-brand for privacy audience | Yes — named exclusions |
| 4 | Ringtone / notification / alarm sound selection (+ custom sound files) | Tier 0 — `Settings.System` `RINGTONE`/`NOTIFICATION_SOUND`/`ALARM_ALERT` + media copy | Smart Switch (Clock/preferences) | Partial |
| 5 | Notification channel / per-app notification parity — **DECLINED** (no per-channel shell verb; channels are app-owned; see PRP-05) | Privileged — adjacent to existing runtime-permission parity | Seedvault/CalyxOS: "planned, not currently available"; loud, broad annoyance | Yes — named gap |
| 6 | Guided LAN relay for app-owned encrypted backups (Signal/Molly, Aegis) | Tier 0 orchestration — ferries the app's OWN encrypted export file + passphrase prompt over LAN; does NOT own the blob | Very high demand (Signal/Molly + Aegis are flagship de-Googled apps); no good no-cloud answer today | Adjacent (not owned) |
| 7 | Bluetooth pairing list + assisted re-pair (feasibility spike) | Tier 1 — privileged `/data/misc/bluedroid/bt_config.conf` | Recurring migration annoyance; CalyxOS names BT among Seedvault exclusions | Yes — named gap |

## Declined / deferred (no PRP — rationale recorded)
- **eSIM** — carrier-bound eUICC, cryptographically non-copyable by an app; needs OS-level
  integration (GrapheneOS's job). VERY high demand but out of reach. Note in user docs since
  users will ask.
- **Private Space / work-profile** — cross-profile data is sandbox-bound even with shell-uid;
  high complexity. Possible future differentiator; defer.
- **Home-screen / launcher layout** — Seedvault already does this partially; launcher-specific,
  no portable API. Skip.
- **System fonts / boot animation** — `/system` is immutable on stock GrapheneOS. N/A.
- **Accounts** — credentials are non-portable; bare account stubs add ~nothing. Skip.
- **Generic clock-app alarms** — no stable cross-app API; risks colliding with Seedvault app-data
  ownership. Skip (the *sound selection* in #4 is the salvageable part).

## Strategic note
GrapheneOS devs have publicly signaled intent to replace Seedvault, and community sentiment on
Seedvault restore reliability is poor. portage should position as the *reliable* complementary
LAN parity layer for the categories it owns (settings, Wi-Fi, notifications, pairings) and as an
*orchestrator* (not absorber) of app-owned encrypted backups — never a Seedvault app-data
competitor.

## Sources (selected)
- GrapheneOS Discuss: d/24034 (transfer to new phone), d/3302 (plan to replace Seedvault),
  d/20886 (eSIM), d/17470 (work-profile Seedvault).
- GrapheneOS os-issue-tracker: #3389 (Seedvault unreliable), #3523 (BT), #7251/#3901 (eSIM),
  #4300 (Private Space backup).
- CalyxOS Seedvault docs (authoritative exclusion list: Wi-Fi, BT timeout, Panic settings,
  USB/peripheral, priority conversations, emergency owner info, notifications "planned").
- Privacy Guides thread on Seedvault restore experiences; matthewbrunelle.com GrapheneOS
  migration write-up.
- Vendor: android.com transfer, Samsung ANS10001412 (Smart Switch), swiftapps.org, Neo-Backup
  FAQ, XDA Migrate/ZIPme threads.
- Android API: `WallpaperManager`, `WifiManager`/`WifiNetworkSuggestion`, `RingtoneManager`,
  `UserDictionary.Words`, `AutomaticZenRule`/`NotificationManager`, `AccountManager`.
