# Two-phone migration guide

This guide covers the common case:

- **old phone:** any Android 12+ phone, including a non-Pixel or a phone not running GrapheneOS;
- **new phone:** a Pixel running GrapheneOS; and
- **goal:** make the new phone behave like the old phone without pretending Android permits a
  byte-for-byte clone.

Portage is the phone-to-phone parity layer. Seedvault, app-native exports, file copy, and manual
setup remain necessary for state that Android keeps private or binds to hardware.

## What “as close as possible” means

A successful migration has four layers:

1. **Portage:** portable system data and selected configuration.
2. **Seedvault, when available:** best-effort app-private data from apps that participate in
   Android backup.
3. **App-native migration:** encrypted exports or vendor transfer flows for apps that deliberately
   exclude themselves from Android backup.
4. **Manual setup:** secrets, hardware-bound credentials, and state with no portable Android API.

It is not an identical disk image. Android application sandboxes, the hardware-backed keystore,
carrier provisioning, user profiles, and app-owned databases deliberately prevent that.

## Recommended order

### 1. Preserve the old phone

Do not erase, trade in, or reset the old phone until the new phone has been used successfully for
several days. Keep it charged and offline when it is no longer needed day to day.

Before starting:

- update both phones and the important apps;
- record the old phone's user profiles and Private Space separately;
- export recovery codes and confirm access to the password manager;
- make app-native backups for messaging, 2FA, password-manager, notes, and finance apps;
- decide which photos, videos, Downloads, documents, recordings, and custom media should move
  through Portage's explicit file picker versus another file workflow;
- take screenshots of every launcher page, folder, widget, Quick Settings page, alarm, VPN, and
  important per-app setting;
- if Seedvault is available, create a fresh backup and retain its 12-word recovery code.

Treat a completed Seedvault run as an additional recovery source, not proof that every selected app
has restorable data. Android apps can exclude data or all backups, and restore behavior varies by
app and OS version. Android's own documentation also states that a backup transport is not
guaranteed to exist on every Android device.

### 2. Decide which tool owns each category

Do this before restoring. Do not blindly run Seedvault and Portage over the same data. Contacts have
exact-record deduplication in Portage, but calendar events, call history, and SMS are not guaranteed
to deduplicate across independent restore tools.

Recommended ownership:

- **Portage:** contacts, calendar, call history, SMS, supported settings, static wallpaper,
  built-in sound selections, app/APK inventory, user-selected shared files, and the Bluetooth
  re-pair checklist.
- **Seedvault:** app-private data for participating apps, when the old phone already has a usable
  Seedvault backup.
- **App-native export:** Signal/Molly, Aegis and other apps with their own backup or device-transfer
  mechanism.
- **File copy or sync:** very large media libraries, folder trees, or anything you do not select
  explicitly in Portage.
- **Manual:** accounts, hardware-bound credentials, launcher layout when the launcher has no export,
  Wi-Fi passwords, eSIM, widgets, and app-specific settings that were not restored.

### 3. Restore Seedvault during GrapheneOS setup, if using it

Seedvault is most useful for app-private data because Portage cannot read another app's sandbox.
Restore it at the point offered by device/profile setup. A later Portage transfer fills the parity
gaps.

If the old phone is not running an OS with Seedvault, skip this step. Portage can still send its
supported categories from a normal Android 12+ phone. Use each app's native export and ordinary file
copy for the rest.

Seedvault backups are profile-specific. Plan and verify the owner profile, secondary users, and
Private Space independently; Portage v1 operates only in the owner profile.

### 4. Run Portage

1. Install `portage-send` on the old phone and `portage-recv` on the new phone.
2. Put both phones on the same trusted LAN and grant GrapheneOS Network permission.
3. Grant the sender read permissions only for the categories being transferred.
4. On the receiver, grant the requested write permissions and “Modify system settings.”
5. Optionally complete Advanced Transfer Setup with Wireless Debugging for allow-listed secure
   settings, silent APK installation, and supported permission parity.
6. Show the QR on the old phone, scan it on the new phone, review the item list, and transfer.
7. If SMS is selected, temporarily make Portage the default SMS app, then restore the preferred
   messaging app when prompted.

The full verification procedure is in
[E2E-VERIFICATION-RUNBOOK.md](prp/E2E-VERIFICATION-RUNBOOK.md).

### 5. Finish manually and verify

Open each important app. A visible icon does not prove its data, login, push registration, or
permissions were restored.

Verify at minimum:

- calls and SMS can be sent and received;
- contacts and calendars appear in the intended account or local store;
- password manager, 2FA, messaging history, and recovery codes work;
- banking, government, work, DRM, and payment apps have been re-enrolled;
- camera photos, downloads, recordings, and documents are present;
- alarms, notifications, VPNs, widgets, launcher pages, and Quick Settings are correct;
- Bluetooth accessories and Wi-Fi networks reconnect;
- the preferred SMS, browser, phone, launcher, keyboard, and assistant defaults are selected;
- GrapheneOS per-app Network, Sensors, exploit-protection, battery, and notification settings are
  reviewed.

## Capability matrix

“Manual” includes an app's own export/import or device-transfer flow.

| Category | Best path | What to expect |
|---|---|---|
| Contacts | **Portage** | vCard transfer into device-local contacts, including nicknames, birthdays, websites, the favorite/starred bit, and bounded contact thumbnails; exact retries and existing raw contacts are deduplicated. Full-resolution photos, account-specific groups, and app-specific raw-contact data are not portable. Account-backed contacts may instead reappear through their sync provider. |
| Calendar events | **Portage** | ICS event transfer. Account sync may be preferable for cloud calendars. Avoid restoring the same events through two tools. |
| Call history | **Portage** | Phone provider rows are copied. Retries within one Portage transfer are idempotent; independent tools are not cross-deduplicated. |
| SMS text messages | **Portage** | Requires a temporary default-SMS role on the receiver. |
| MMS messages | **Portage, limited** | Requires the same temporary default-SMS role. Portage carries inbox/sent MMS message rows, address rows, and text/binary parts up to the standard 64 MiB item cap; large video/attachment-heavy messages may be skipped. RCS media/state, carrier/service state, drafts, pending sends, and exact thread state are not currently transferred. |
| Installed app list | **Portage** | Produces an assisted reinstall list. This does not include app-private data. |
| APK files and splits | **Portage** | Carries compatible installed APKs. Tier 0 requires system confirmation; Advanced Transfer can install silently. Paid/licensed, device-incompatible, or protected apps may need their store. |
| Runtime permissions | **Portage, limited** | Supported permission parity accompanies carried APKs. Dangerous permissions remain explicit and device/app policy still wins. GrapheneOS-specific toggles are not all ordinary Android permissions. |
| App-private databases, preferences, and sessions | **Seedvault or app-native** | Portage cannot enter another app's sandbox. Seedvault works only for data the app allows and should be verified app by app. |
| Signal/Molly/Aegis-style encrypted backups | **App-native + Portage relay** | The user creates the encrypted backup; Portage can courier supported files but never decrypts them or knows the passphrase. |
| Photos, videos, music, downloads, documents | **Portage for selected files; file copy/sync for bulk libraries** | Portage transfers files the user explicitly selects through Android's file picker and writes them to `Downloads/Portage` on the receiver. It does not crawl folders, preserve original directory trees, or become a bulk sync engine. |
| Allow-listed system settings | **Portage** | A conservative, validated subset is applied. Hardware-specific, unsafe, unknown, and many app-owned settings are intentionally excluded. |
| Static home/lock wallpaper | **Portage** | PNG/JPEG/WebP static images transfer. Live wallpaper app state does not. |
| Ringtone, notification, and alarm selection | **Portage** | Built-in sounds are resolved by title on the receiver. Active custom default-sound files are copied, registered through MediaStore, and assigned to the matching role. |
| Bluetooth devices | **Portage checklist + manual** | Names and addresses become a checklist. Link keys are not copied; every accessory must be paired again. |
| Saved Wi-Fi networks/passwords | **Manual or Seedvault if it succeeds** | A normal app and shell-level Wireless Debugging cannot read saved passphrases on GrapheneOS. Portage cannot transfer them. |
| Launcher pages, folders, icon positions | **Launcher export, Seedvault, or manual** | Portage cannot read or write another launcher's private database. Same-launcher backup/import may work. Seedvault may restore launcher data when that launcher participates, but this is launcher- and restore-dependent. Keep screenshots as the reliable fallback. |
| Widgets | **Manual** | Even if launcher placement returns, widget host bindings and app state commonly require re-adding or reauthorization. |
| Quick Settings layout | **Manual** | Tiles can be OS- and app-version-specific; Portage does not currently remap the layout. |
| Notification channels and per-app notification tuning | **Manual** | Channels are owned and created by each app. Android exposes no reliable cross-app restore API for their full state. |
| Accounts and login sessions | **Manual / app-native** | Passwords, OAuth tokens, and account-manager credentials are intentionally non-portable. A password manager reduces the work. |
| Password-manager vault | **App-native sync/export** | Confirm the vault and emergency/recovery material before wiping the old phone. |
| 2FA/TOTP tokens | **App-native encrypted export or account migration** | Never assume Android backup contains them. Verify codes on the new phone before deleting the old copy. |
| Passkeys, hardware-backed keys, biometrics, screen lock | **Manual re-enrollment** | Hardware-backed keystore material and biometric templates cannot be cloned to different hardware. |
| Banking, payment, government, DRM, and work enrollment | **Manual re-enrollment** | These apps commonly bind credentials or integrity state to the old device and may explicitly reject backup restore. |
| VPN profiles, client certificates, device/admin enrollment | **App/administrator export or manual** | Private keys may be non-exportable. Managed devices may require the administrator. |
| Alarms, timers, and clock-app data | **Manual or clock-app export** | There is no stable cross-clock-app migration API. Portage transfers the selected alarm sound, not alarm schedules. |
| eSIM | **Carrier/OS transfer flow** | eSIM credentials are carrier-managed and cryptographic; Portage cannot copy them. Do not erase the old eSIM until the new one is active. |
| Physical SIM | **Move the SIM** | Carrier activation rules still apply. |
| Secondary users, work profile, Private Space | **Per-profile tooling/manual** | Sandboxes are isolated. Portage v1 is owner-profile only and cannot merge profiles. |
| Default apps and special access | **Manual review** | Re-select SMS, browser, phone, launcher, keyboard, VPN, accessibility, notification access, battery exceptions, and other special-access roles. |
| GrapheneOS exploit-protection and per-app controls | **Manual review, limited permission parity** | Many GrapheneOS controls are private OS state, not portable settings keys. Review Network, Sensors, native-code debugging, memory tagging, hardened malloc, and battery policy per app. |

## Launcher layout specifically

Portage cannot currently back up or restore the launcher screen. Android does not provide a
portable API for a third-party migration app to read another launcher's pages, folders, widgets,
and placement, and the database schema differs between launchers and versions.

Use this priority order:

1. If the old and new phones use the **same launcher** and it offers export/import, use that.
2. If using Seedvault, allow it to restore the launcher during profile setup, then verify every
   page and widget. Do not assume success merely because the wallpaper or apps returned.
3. Otherwise, take screenshots of all pages and folders and rebuild them manually after Portage
   reinstalls the apps.

A future Portage feature could carry a **user-created launcher export file** as an opaque relay,
similar to app-backup relay. It still could not translate arbitrary launcher databases into the
GrapheneOS launcher without launcher cooperation.

## Seedvault's role and limits

Seedvault is still worth attempting when it is available because it has system-backup privileges
Portage intentionally does not have. It is the only one of these two tools that can restore
participating apps' private backup data.

It is not a complete image:

- apps decide whether backup is allowed and which data is included;
- hardware-bound and security-sensitive state cannot be restored;
- a successful backup job does not guarantee every app will restore correctly;
- restore timing and profile boundaries matter;
- it is not a reliable substitute for app-native exports or a separate copy of user files.

Useful current references:

- [GrapheneOS encrypted backups feature](https://grapheneos.org/features#encrypted-backups)
- [Android Auto Backup inclusion, exclusion, and transfer rules](https://developer.android.com/identity/data/autobackup)
- [Android key/value backup and transport availability](https://developer.android.com/identity/data/keyvaluebackup)

This guide describes Portage as implemented on the date of its latest edit. The capability matrix
must be updated whenever a provider or Android/GrapheneOS restriction changes.
