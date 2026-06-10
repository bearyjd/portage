# portage

**Device-to-device parity transfer for GrapheneOS.** Make a new phone feel like the old
one — settings, contacts, calendar, call log, SMS, and your app set — over your LAN.
No cloud, no account, no relay.

> *portage* (Fr.) — carrying your belongings over. Also what you do when you *port* to a
> new device.

Two artifacts:

- **`portage-send`** — the old phone (exporter)
- **`portage-recv`** — the new phone (importer)

## Division of labor with Seedvault

**Seedvault moves your app data; `portage` moves the settings-and-parity layer Seedvault
misses, directly phone-to-phone.** They are complementary, not competitors:

| | Seedvault | portage |
|---|---|---|
| App internal data, databases, login sessions | ✅ (privileged system app) | ❌ (deferred to Seedvault) |
| Contacts / calendar / call log / SMS | partial | ✅ |
| Curated, allow-listed system settings | partial | ✅ |
| App **inventory** + assisted reinstall | ❌ | ✅ |
| Direct phone-to-phone over LAN | ❌ (needs a backup target) | ✅ |

## Capability tiers

- **Tier 0 — no special privilege (always works):** contacts (vCard), calendar (ICS),
  call log, SMS/MMS (via temporary default-SMS-app handoff), app inventory + assisted
  reinstall, and the `Settings.System` slice of settings sync (font scale, screen
  timeout, auto-rotate, haptics, time format) via user-granted "Modify system settings."
- **Tier 1 — requires [Shizuku](https://shizuku.rikka.app/) (graceful-degrade):**
  allow-listed `Settings.Secure` / `Settings.Global` sync, batched app reinstall, and
  opt-in runtime-permission parity. See `docs/prp/ADR-001-privilege-feasibility.md` for
  the privilege architecture and on-device verification plan.

## Status

**Planning / design substrate.** No application code yet. The design artifacts live in
[`docs/prp/`](docs/prp/):

- [`malle-prp-prompt.md`](docs/prp/malle-prp-prompt.md) — execution brief (original `malle` naming)
- [`ADR-001-privilege-feasibility.md`](docs/prp/ADR-001-privilege-feasibility.md) — Shizuku / Tier 1 go-no-go + verification procedure
- [`PROTOCOL.md`](docs/prp/PROTOCOL.md) — pairing + transfer wire format (QR anchor, Noise XXpsk3)
- [`THREAT_MODEL.md`](docs/prp/THREAT_MODEL.md) — semi-trusted-LAN adversary, attack→defense table
- [`settings_allowlist.md`](docs/prp/settings_allowlist.md) — SAFE / RISKY / DEVICE_SPECIFIC key classification
- [`DEVILS_ADVOCATE.md`](docs/prp/DEVILS_ADVOCATE.md) — adversarial review of the plan

> Note: the design docs were drafted under the working name `malle`. The project name and
> app IDs (`cc.grepon.malle.*`) may be renamed to `portage` (`cc.grepon.portage.*`) before
> implementation.

## License

[AGPL-3.0](LICENSE). © Grepon Labs LLC. Brand: Entrevoix.
