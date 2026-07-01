<!-- Generated: 2026-06-21 | Modules: core-transport, providers, adb-bridge, wizard | Token estimate: ~850 -->

# Engine — transport · providers · privilege bridge

portage has no server; the "backend" is the on-device transfer engine (non-UI logic).

## Transport stack (`:core-transport` → `:core-model`)
```
SecureChannel (iface) ◄── NoiseSecureChannel  Noise PSK_XX, vendored noise-java (ADR-002)
                          NoiseChannel ....... handshake state
SocketFrameTransport ..... u16-length framed TCP (frame cap)
MessageCodec ............. CBOR encode/decode ProtocolMessage
PskRegistry .............. single-use PSK consumption
PairingCodecImpl ......... QR-encoded PSK ↔ pairing token
TransferTimeouts ......... 10s handshake / per-op deadlines
```
Crypto stays behind `SecureChannel` (swappable). PSK single-use; `payload.wipe()` on accept + connect.

## Provider pipeline (`:providers` → `:core-model`, `:settings-catalog`)
Interfaces (`Providers.kt`):
```
ExportProvider { val kind: ItemKind; suspend exportTo(sink: OutputStream) }
ApplyProvider  { val kind: ItemKind; suspend apply(source): ApplyOutcome }
ApplyProviderRegistry(providers).apply(kind, source)   ← receiver dispatch by manifest kind
```
Per-kind subpackages: contacts/ (VCard3 + starred state + bounded thumbnails) · calendar/ (Ics) · calllog/ · sms/ + mms/ (transient default-SMS
role) · wallpaper/ · sound/ · bluetooth/ · inventory/ · relay/ (AppBackupRelay, opaque) · settings/
(SAFE-allowlist gated) · apk/ (the keystone). Support pkgs: text/ (formatting), wire/ (codec helpers).

APK sub-pipeline (`providers/apk/`):
```
export (sender):  ApkExportProvider → ApkCodec → ApkContainer (+ApkContainerValidation)
apply  (receiver): ApkApplyProvider → ApkReconcile(targetConfig) → ApkInstallSeams
  reconcile: base + matching abi + density(bucket-match else keep-fallback) + ALL lang + feature;
             ABI-miss → Incompatible → per-app SKIP ("install from store")
  install:   ApkSilentInstaller (exec: bridge, batched) → else Tier-0 PackageInstaller confirm
```

## Privilege bridge (`:adb-bridge`; `:wizard` → `:adb-bridge`)
```
AdbBridge (iface, 230L) ... ONLY privileged entry: pair/connect/shell/selfGrant/installApk/probe
LocalAdbBridge (473L) ..... libadb exec:, split install-write -S, connect() gated on adb_wifi_enabled
LibAdbDeviceGate (192L) ... Wireless-Debugging state + libadb wiring
AdbKeyStore (120L) ........ persisted RSA-2048 ADB identity (SecureRandom)
PrivilegeWizard (:wizard, 246L) … pair→connect→probe→grant SM; DISCONNECTS after probe (no held uid)
```
