<!-- Generated: 2026-06-21 | Modules: app-send, app-recv | Token estimate: ~750 -->

# UI — Compose (`app-send` · `app-recv`)

Both apps: single-Activity Jetpack Compose, MVVM (`ViewModel` + `StateFlow`), Material3.
Shared visual identity = "Swiss" components (`SwissComponents.kt`) + branding (canoe mark).

## app-send (exporter)
```
MainActivity (189L) → SenderApp (155L)
  HomeScreen (178L) ......... choose what to carry
   ├ AppCarrySection (179L) . pick installed apps  → APK / inventory items
   └ RelayPickSection (169L). pick app-backup relay exports
  SendScreens (277L) ........ QR display + transfer progress
  SenderViewModel (402L) .... bind listener, probe+release port (SO_REUSEADDR re-bind),
                              QR(PSK), accept connection, drive ExportProviders.  NO privilege deps.
```

## app-recv (importer)
```
MainActivity (239L) → ReceiverApp (402L)
  ScanScreen (235L) ......... camera QR scan (zxing BarcodeView) → PSK
  WizardScreen (447L) ....... PrivilegeWizard UI: enable Wireless Debugging, pair, grant (Tier-1 only)
  ChecklistScreen (388L) .... select manifest items to import
  TransferScreen (581L) ..... live per-item progress + outcomes (incl. "incompatible → store")
  ReceiverViewModel (414L) .. handshake, manifest, Select, drive apply
  transfer/ItemStreamReceiver (340L) … stream items → ApplyProviderRegistry
  install/ ................... Tier-0 PackageInstaller adapter (ApkInstallPrompt, ResultReceiver,
                               AdbApkInstaller silent seam, InstallLaunch)
  sms/ ...................... default-SMS role coordinator + ledger; reconcile on launch/onResume
```

## State flow
`ViewModel` `StateFlow` → Compose recomposition. Navigation = screen enum/sealed state (no nav lib).
No external client-state library; no server state (LAN session is ephemeral, held in the ViewModel).
