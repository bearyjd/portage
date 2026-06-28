# Privacy policy

Last updated: 2026-06-27

portage transfers data directly between devices selected by the user. It has no account,
analytics, advertising, telemetry, cloud service, or project-operated server.

## Data processed

Depending on the features and permissions the user selects, portage can process:

- contacts, calendars, call history, and SMS/MMS;
- selected system settings, wallpaper, and sound selections;
- installed-app inventory, APK files, and runtime-permission selections;
- paired Bluetooth device names and addresses;
- user-selected, app-encrypted backup files.

The sender reads selected data and transmits it to the paired receiver. The receiver
temporarily stages transferred data in its private application storage while validating
and applying it. Transfer staging is deleted on completion, cancellation, failure, and
the next application start. Android and GrapheneOS may retain data after it is imported
into the corresponding system provider or installed application.

The app never asks for, reads, or transfers the passphrase of an app-encrypted backup.

## Network use

portage uses the local network and device-localhost connections:

- the two devices discover or directly connect to each other on the LAN;
- transfer payloads are encrypted and authenticated using a QR-anchored Noise session;
- the degoogle receiver can connect to its own Wireless Debugging service on localhost
  when the user explicitly enables Tier 1.

portage does not intentionally contact an Internet service. GrapheneOS exposes Android's
`INTERNET` permission as the user-controlled Network permission; disabling it also blocks
LAN and localhost access and therefore prevents transfers and Tier 1 from working.

## Storage and sharing

Data is shared only with the receiving device chosen through the QR pairing ceremony and,
when requested by the user, with Android system providers or the selected target app.
portage does not sell data or share it with the project maintainers.

An app-backup relay can create a user-visible temporary copy for handoff to its target
app. The original user-selected file remains under the user's control.

## Permissions

Permissions are requested only for selected transfer categories. The two applications
have intentionally different access:

- the sender primarily requests read access;
- the receiver requests the corresponding write access;
- SMS restoration temporarily requires the receiver to become the default SMS app;
- modifying system settings, installing APKs, camera scanning, Bluetooth inventory, and
  notifications use their respective Android permission or special-access flows;
- the degoogle receiver alone contains the optional local ADB bridge for Tier 1.

Permissions can be revoked through Android or GrapheneOS settings. Features relying on a
revoked permission stop working or degrade to a lower capability tier.

## Security and retention

The protocol and adversary model are documented in
[`docs/prp/PROTOCOL.md`](docs/prp/PROTOCOL.md) and
[`docs/prp/THREAT_MODEL.md`](docs/prp/THREAT_MODEL.md). Security reports should follow
[`SECURITY.md`](SECURITY.md).

The project does not receive transferred content, so it cannot retrieve, delete, or
provide a copy of that content. Uninstalling portage removes its private application data;
it does not remove records already imported into Android providers or apps.

## Changes and contact

Material policy changes will be recorded in this file and distributed with a new release.
For privacy questions, open a GitHub issue that contains no personal or transferred data.
Use private vulnerability reporting for security-sensitive matters.
