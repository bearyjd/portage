<!-- Generated: 2026-06-21 | Modules: 8 | Source files: 120 main / 51 test kt | Token estimate: ~700 -->

# Architecture — portage

Device-to-device parity transfer for GrapheneOS over LAN. No cloud. AGPL-3.0.
TWO APKs by design (privilege-by-packaging): `portage-send` (exporter) + `portage-recv` (importer).

## Module DAG (9 Gradle modules)

```
core-model ......... wire protocol model (pure JVM, no deps)
core-lineage ....... atomic lineage/checkpoint store → core-model
settings-catalog ... SAFE settings allowlist (pure JVM, no deps)
adb-bridge ......... privileged ADB entry point (android lib, no deps)
core-transport ..... Noise PSK_XX channel ......... → core-model
wizard ............. privilege bootstrap state-machine → adb-bridge
providers .......... export/apply per ItemKind ..... → core-model, settings-catalog
app-send  (APK) .... → core-model, core-lineage, core-transport, providers
app-recv  (APK) .... → core-model, core-lineage, core-transport, providers, settings-catalog, wizard, adb-bridge
```

KEY INVARIANTS
- `core-lineage` owns the single-writer atomic no-backup snapshot, exact-key staging revalidation,
  24-hour staging and 30-day lineage expiry, terminal tombstones, and interrupted-apply reconciliation.
- Protocol v6 NEW establishes a 32-byte resume credential over Noise; RESUME requires it plus a fresh QR.
- Receipt acknowledgement is RECEIVED_VERIFIED; only final BATCH_ACK may assert APPLIED_DURABLE.
- `app-send` has NO edge to `adb-bridge`/`wizard` → provably cannot escalate (CI no-escalation gate).
- `providers` has NO edge to `adb-bridge` → privilege is INJECTED as a seam from `app-recv` (ADR-006 C1).
- `AdbBridge` is the ONLY privileged entry point; no module else speaks the ADB wire protocol (ADR-003).

## End-to-end transfer flow

```
SENDER (app-send)                          RECEIVER (app-recv)
  pick items ──┐                             scan QR (camera) ──┐
  show QR(PSK) │   Noise PSK_XX over LAN     │ [Tier-1?] wizard → adb-bridge grant
  bind socket  └──► SocketFrameTransport ◄───┘ select items
  ExportProvider.exportTo(sink) ─ items ───►  ItemStreamReceiver
                                              └► ApplyProviderRegistry.apply(kind)
                                                  ├ Tier0: normal APIs (Settings.*, ContentProvider…)
                                                  └ Tier1 (apk/settings): reconcile + bridge / Tier-0 install
```

## Privilege tiers
- TIER0 — no privilege: contacts, calendar, calllog, sms/mms, inventory, wallpaper, sound, bluetooth, relay.
- TIER1 — one-shot `pm grant` via adb-bridge: apk install, settings write.

## See also
backend.md (engine) · frontend.md (UI) · data.md (wire model) · dependencies.md (libs).
Design substrate: `docs/prp/` (ADR-001..006, THREAT_MODEL, PROTOCOL, P6 runbook).
