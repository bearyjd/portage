# Turnkey Migration Audit and Delivery Plan

**Status:** Approved implementation plan; this PR changes documentation only.
**Date:** 2026-09-13  
**Decision:** Establish durable, truthful transfer semantics before adding relay or Home Map workflows.
Exact arbitrary cross-launcher restoration is not a capability Portage can promise as an ordinary app.
Spike same-launcher Launcher3/Seedvault restore first; otherwise ship a bounded, private reconstruction guide.

No implementation, capability claim, or hardware sign-off is created by this document.

## Goal

Make a move feel continuous without hiding Android's boundaries: prepare both devices, transfer without UI
stalls, survive interruption, report every partial outcome, and retain a durable **Finish your move** ledger.

## Scope

- Lifecycle, export, protocol, checkpoint, replay, and provider correctness.
- Receiver-authoritative validation, storage headroom, progress, and remediation UX.
- Durable follow-ups for installs, relay imports, roles, permissions, Bluetooth, and Home Map.
- The one-app decision accepted in ADR-007, including its security downgrade and controls.
- Same-launcher restore research and a typed HOME_LAYOUT_GUIDE fallback.
- Automated, constrained-memory, lifecycle, and two-device hardware release gates.

## Out of scope

- Implementation in this planning PR.
- Raw launcher-database access, translation, or patching.
- Root, Accessibility automation, Portage as a launcher, or a permanent overlay by default.
- Claims of exact cross-launcher placement, widget-state restoration, live-wallpaper fidelity, or exactly-once
  writes where Android offers no authoritative identity/readback.
- Weakening transport authentication or privilege controls to reduce user interaction.

## Strengths to preserve—and not overstate

- Noise-authenticated, encrypted LAN transport, expiring pairing payloads, staged SHA-256 checks, generated
  filenames, and fail-closed type/size checks.
- Existing per-item caps plus APK and user-file aggregate/free-space checks
  (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/transfer/ItemStreamReceiver.kt:141-177) are useful
  foundations, not complete whole-manifest or all-kind resource controls.
- Per-item byte events already exist
  (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/transfer/ItemStreamReceiver.kt:234); retain them while
  adding truthful aggregate byte progress.
- Separate artifacts currently provide a strong sender-binary privilege boundary. ADR-007 deliberately gives
  up that property in the full unified artifact; it must not be described as preserved unchanged.

## Prioritized audit

Priority: **P0** correctness/security/data integrity; **P1** recovery or major product friction;
**P2** fidelity, maintainability, or release confidence.

| Pri | Finding and exact evidence | Required disposition |
|---|---|---|
| P0 | Activity recreation deletes staging and, on receive, abandons sessions (app-send/src/main/kotlin/com/ventouxlabs/portage/send/MainActivity.kt:90-93; app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/MainActivity.kt:115-123). | Cleanup must be process/session-owned, exclude live-lineage resources, and use durable ownership data—not every onCreate. |
| P0 | ITEM_ACK(OK) means only “bytes verified”; apply happens afterward (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/transfer/ItemStreamReceiver.kt:249-268). The sender uses those receipt acks as results if BATCH_ACK is absent (app-send/src/main/kotlin/com/ventouxlabs/portage/send/transfer/TransferEngine.kt:56-83). | Model RECEIVED_VERIFIED separately from APPLIED_DURABLE. Missing final apply verdict is UNKNOWN_INTERRUPTED, never success. Only APPLIED_DURABLE may be skipped on reconnect. |
| P0 | Failure copy says nothing changed (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/ui/FailedScreen.kt:67-70) although verified items are applied immediately (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/transfer/ItemStreamReceiver.kt:256-268). | Every terminal screen must render durable moved, partial, failed, unattempted, and unknown results; never imply rollback. |
| P0 | Provider availability/export exceptions and empty output are silently dropped (app-send/src/main/kotlin/com/ventouxlabs/portage/send/transfer/ManifestBuilder.kt:33-67). Contacts, calendar, and SMS convert read failures to empty exports (providers/src/main/kotlin/com/ventouxlabs/portage/providers/contacts/ContactsProviders.kt:28-33; providers/src/main/kotlin/com/ventouxlabs/portage/providers/calendar/CalendarProviders.kt:27-32; providers/src/main/kotlin/com/ventouxlabs/portage/providers/sms/SmsProviders.kt:67-72). MMS counts skips but its provider discards the summary (providers/src/main/kotlin/com/ventouxlabs/portage/providers/mms/MmsProviders.kt:113-117; providers/src/main/kotlin/com/ventouxlabs/portage/providers/mms/AndroidMmsStore.kt:34-62). | Export returns COMPLETE, PARTIAL, or FAILED with attempted/exported/skipped counts and stable reasons. Empty is success only after authoritative zero count. Fault and over-cap cases stay visible. |
| P0 | Manifest building starts on Main and performs blocking export/hash work (app-send/src/main/kotlin/com/ventouxlabs/portage/send/SenderViewModel.kt:238-247; app-send/src/main/kotlin/com/ventouxlabs/portage/send/transfer/ManifestBuilder.kt:45-67). | Probe/export/copy/hash on an injected IO dispatcher; publish state on Main; preserve cancellation. |
| P0 | Retry journals are transfer-local or absent (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/ReceiverViewModel.kt:355-363; providers/src/main/kotlin/com/ventouxlabs/portage/providers/calllog/CallLogProviders.kt:84-102; providers/src/main/kotlin/com/ventouxlabs/portage/providers/sms/SmsProviders.kt:88-104; providers/src/main/kotlin/com/ventouxlabs/portage/providers/mms/MmsProviders.kt:121-138; providers/src/main/kotlin/com/ventouxlabs/portage/providers/calendar/CalendarProviders.kt:49-61). | Bind durable replay decisions to one authenticated migration lineage. Never claim exactly-once unless target state can be authoritatively read back. |
| P0 | Call log does insert, then records only in a private journal; the target insert returns no authoritative identity and the journal can fail (providers/src/main/kotlin/com/ventouxlabs/portage/providers/calllog/CallLogProviders.kt:90-104; providers/src/main/kotlin/com/ventouxlabs/portage/providers/calllog/AndroidCallLogStore.kt:45-64). | Add authoritative provider readback or disclose at-least-once semantics and possible duplicates after the uncertainty boundary. A private journal alone cannot prove exactly-once. |
| P0 | MMS inserts the parent before addresses/parts and does not compensate on child failure (providers/src/main/kotlin/com/ventouxlabs/portage/providers/mms/AndroidMmsStore.kt:65-79). | Use an atomic provider batch if supported; otherwise delete the new parent and children on any failure, with orphan fixtures. |
| P0 | The received manifest is rendered before whole-manifest validation (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/ReceiverViewModel.kt:333-339), and aggregate/free-space budgets cover only APK/user files (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/transfer/ItemStreamReceiver.kt:77-85,164-177). | Before UI, require unique bounded positive IDs, bounded count/total, nonnegative per-kind sizes, valid SHA-256, bounded sanitized strings, and overflow-safe receiver-computed totals. Enforce all-kind and per-kind aggregates plus destination headroom. Use canonical receiver labels; sender labels are advisory. |
| P0 | Both manifests rely on allowBackup=false (app-recv/src/main/AndroidManifest.xml:64-66; app-send/src/main/AndroidManifest.xml:56-58), while the exportable ADB private key is stored in filesDir (adb-bridge/src/main/kotlin/com/ventouxlabs/portage/adbbridge/AdbKeyStore.kt:34-44,91-108). For target-31+ apps, some Android 12+ device-to-device implementations may ignore allowBackup=false. | Put ledgers, guide images, and file-backed keys in noBackupFilesDir; exclude sensitive state from both cloud-backup and device-transfer sections of dataExtractionRules, retain legacy fullBackupContent exclusions, and inspect actual backup/D2D datasets. Prefer an Android Keystore non-exportable ADB key. Sources: https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore and https://developer.android.com/identity/data/autobackup. |
| P1 | Relay output is app-specific and the UI merely launches the target app (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/relay/AndroidRelayHandoff.kt:49-66; app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/ui/ReceiverApp.kt:395-407). | Default to a non-exported FileProvider rooted at a dedicated narrow internal directory, an explicit package-targeted intent, a read-only one-recipient grant, and deterministic revoke. Never expose a root path or grant write/prefix/persistable access. Public Downloads is an explicit, disclosed user choice only; no universal target-app import support is claimed. |
| P1 | Relay source length can go stale and trailing data is ignored after declared-length copy (providers/src/main/kotlin/com/ventouxlabs/portage/providers/relay/AppBackupRelayProviders.kt:297-307; app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/relay/AndroidRelayHandoff.kt:59-65). Generated package/itemId names can collide across moves. | Require exact length plus EOF, immutable lineage/item/hash filenames, atomic no-replace publish, and ledger binding of URI, size, SHA-256, grant, and pending action. Never truncate or overwrite. |
| P1 | ResumePoint exists, but select handling ignores resume and each file opens at byte zero (core-model/src/main/kotlin/com/ventouxlabs/portage/model/Messages.kt:38-43; app-send/src/main/kotlin/com/ventouxlabs/portage/send/transfer/TransferEngine.kt:48-64,94-103). | Persist completed-item checkpoints first. Byte resume is optional and only valid when lineage, kind, schema, size, hash, offset, and staged bytes all match. |
| P1 | Follow-ups exist mainly in Done ViewModel state (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/ReceiverState.kt:87-126). | Persist a privacy-minimized Finish your move action ledger with pending, blocked, complete, failed, retry, and dismissed states. |
| P1 | Reset closes resources without owning the transfer job, allowing a late failure to overwrite reset (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/ReceiverViewModel.kt:427-442). | Own/cancel jobs and guard every callback/state write with the active lineage and monotonically increasing epoch. |
| P1 | Only the first advertised IP is retried (core-transport/src/main/kotlin/com/ventouxlabs/portage/transport/NoiseSecureChannel.kt:147-154). | Try every validated hint under one cumulative deadline and record which endpoint succeeded. |
| P1 | Duplicate ITEM_BEGIN replaces the prior map result, and ITEM_ACK identity is not checked against the current item (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/transfer/ItemStreamReceiver.kt:91-100; app-send/src/main/kotlin/com/ventouxlabs/portage/send/transfer/TransferEngine.kt:64-69). | Reject duplicate begins and invalid/duplicate/out-of-set receipt and final acknowledgements before any applying or completing. |
| P1 | VCF, ICS, JSONL, and wallpaper paths materialize whole payloads/records (providers/src/main/kotlin/com/ventouxlabs/portage/providers/contacts/VCard3.kt:126-130; providers/src/main/kotlin/com/ventouxlabs/portage/providers/calendar/Ics.kt:78-83; providers/src/main/kotlin/com/ventouxlabs/portage/providers/wire/JsonLines.kt:42-48; providers/src/main/kotlin/com/ventouxlabs/portage/providers/wallpaper/WallpaperProviders.kt:216-221). | Stream records, bound line/record/object size, decode one image at a time, and verify under a constrained heap. |
| P1 | Calendar export omits calendar identity and rich fields; import selects one writable calendar and forces UTC (providers/src/main/kotlin/com/ventouxlabs/portage/providers/calendar/AndroidCalendarStore.kt:31-66; providers/src/main/kotlin/com/ventouxlabs/portage/providers/calendar/Ics.kt:24-59). | Preserve calendar mapping, timezone/DST, recurrence exceptions, reminders, and attendees, or label each omitted field as a fidelity degradation before selection. Default to a dedicated local calendar. |
| P1 | Wallpaper source access converts framework refusal/null to unavailable, and mirror state is not represented (providers/src/main/kotlin/com/ventouxlabs/portage/providers/wallpaper/AndroidWallpaperStore.kt:21-24,39-43,55-65). | Hardware-prove a permitted source path or disable the automatic claim. Model SYSTEM/HOME and LOCK independently, including “lock mirrors system”; never imply one wallpaper surface. |
| P1 | Permissions arrive as an up-front burst and receiver “advanced” labeling is disconnected from setup (app-send/src/main/kotlin/com/ventouxlabs/portage/send/ui/HomeScreen.kt:46-55,155-163; app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/ui/ChecklistScreen.kt:412-425). | Category-first preflight, just-in-time permission requests, inline remediation, and selection preservation across Settings/recreation. |
| P1 | One-app is accepted but unimplemented (docs/prp/ADR-007-app-unification-and-distribution.md:16-33). It necessarily puts receive privilege code in the full artifact even in Send mode (docs/prp/ADR-007-app-unification-and-distribution.md:106-140). | Treat this as a real defense-in-depth downgrade. Ship all ADR-007 controls with—not after—the merge; see PR 7. |
| P2 | Status copy conflates user choice, unsupported, permission, and write failures; overall progress is item-count based (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/ui/ItemStatusDisplay.kt:25-40; app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/ui/TransferScreen.kt:35-49). | Stable machine reason/remediation codes plus current/overall verified/applied bytes. Rate/ETA may be shown only with honest “estimating” and stalled states. |
| P2 | Capability catalogs can drift (app-recv/src/main/kotlin/com/ventouxlabs/portage/recv/checklist/ReceiverChecklist.kt:127-138). | One exhaustive ItemKind/flavor capability registry drives offer, permission, preflight, labels, apply support, and copy; completeness tests fail on a new kind. |
| P2 | CI runs unit/assembly gates but no lint/detekt/ktlint in the main job (.github/workflows/build.yml:71-79); core-model:test is NO-SOURCE in observed runs. | Add model wire goldens, every-kind loopback, lifecycle, fault, constrained-heap, static-analysis, backup-dataset, and hardware evidence gates. |

### APK replacement disposition

The absence of an explicit replacement flag at
adb-bridge/src/main/kotlin/com/ventouxlabs/portage/adbbridge/LocalAdbBridge.kt:193 is not presently a defect:
AOSP enables replacement by default and accepts -r as a compatibility no-op. Retain a hardware
upgrade/no-downgrade regression instead of adding a flag without evidence:
https://android.googlesource.com/platform/frameworks/base/+/8e8460b4b3ea4ee7069ac43b8f6cd0d7ab4084ba/services/core/java/com/android/server/pm/PackageManagerShellCommand.java

## Transfer truth and replay contract

### Durable lineage and checkpoints

- Generate exactly 16 random bytes with SecureRandom only when the user chooses **Start a new move**. Persist
  that migration-lineage ID on both devices, bind it to the authenticated peers, carry it only inside the
  authenticated channel, never derive it from device/user data, and never reuse it for a later move.
- During the first authenticated session, establish a separate random 32-byte lineage-scoped resume credential
  inside the encrypted channel. A later connection may resume that lineage only by proving possession of this
  credential as an additional handshake PSK; fresh transport static keys and a newly scanned QR alone are not
  peer identity. Never encode the resume credential in the QR. Delete it on finish, cancel, expiry, or explicit
  **Start a new move**; loss on either device requires a visibly new move rather than an unauthenticated resume.
  It expires 30 days after the last authenticated activity. Finish/cancel/new-move tombstones and deletes it
  locally immediately; while connected, an authenticated cancel-and-ack requests peer deletion. An offline peer
  purges independently at its own deadline—Portage never claims remote deletion without that acknowledgement.
- Generate and persist a random 16-byte logical-item occurrence ID for each selected item within the lineage;
  carry it in the authenticated manifest. Namespace each checkpoint by lineage + occurrence ID + kind + wire-
  schema version + declared size + SHA-256. Numeric Item ID is an ordering index, not sufficient identity; two
  byte-identical selected files remain two independently accountable occurrences.
- Persist transitions transactionally: PREPARED → RECEIVED_VERIFIED → APPLYING → APPLIED_DURABLE, or a typed
  failure. After connection loss, APPLYING without durable provider proof becomes UNKNOWN_INTERRUPTED.
- RECEIVED_VERIFIED permits byte retransmission avoidance only while staged bytes exist and re-hash. It does
  not permit skipping apply; only APPLIED_DURABLE plus provider-specific evidence does.
- Sender staging and receiver checkpoints are sensitive, app-private, no-backup state. Staged payloads and
  URI grants expire 24 hours after the last authenticated transfer activity; interrupted checkpoints and the
  action ledger expire 30 days after that activity or terminal state; Home Map images expire seven days after
  APPLIED_DURABLE and immediately on finish/cancel. Relaunch and viewing do not extend deadlines. Test every
  policy with an injected clock at deadline minus one millisecond and at the deadline. Purge earlier only after
  durable final receipts and follow-up references no longer need the data.

### Export result contract

Every provider returns a typed result:

| Result | Meaning | User/transfer behavior |
|---|---|---|
| COMPLETE | Authoritative enumeration succeeded and all eligible records were encoded. | May advertise the item and later count it complete. |
| PARTIAL | Some records were exported; counts and bounded reason codes identify omissions. | Advertise with warning; require acknowledgement before “complete move.” |
| FAILED | Permission, query, read, encode, hash, or staging failed; no trustworthy payload exists. | Do not advertise as successful; retain a retry/remediation action. |

The result oracle is fixed: query failure → FAILED/QUERY_FAILED; a mid-stream failure after at least one encoded
record → PARTIAL/READ_FAILED_AFTER_PARTIAL; unwritable staging → FAILED/STAGING_WRITE_FAILED; hash failure →
FAILED/HASH_FAILED; at least one encoded record followed by a record cap → PARTIAL/RECORD_TOO_LARGE; at least
one encoded record followed by the aggregate cap → PARTIAL/AGGREGATE_LIMIT_REACHED; and an authoritative empty
enumeration → COMPLETE/NONE with all counts zero. Before the first encoded record, either partial case is FAILED
with the same reason. Attempted = exported + skipped for every terminal result. No fault becomes empty success.
The applicable cases run against every production exporter in both flavors. Each implementation/path has a
stable `exporterId` independent of ItemKind—including parameterized APK, relay, user-file, wallpaper-surface,
and sound-role factories—and declares its applicable oracle cases. Equality between the production exporter-ID
set and the test-matrix ID set fails when an alternate or new exporter lacks contract tests. MMS counts must
match exactly.

### Receiver validation and storage contract

Before rendering or selecting, reject a manifest unless it has 0–512 items, every present numeric ID is unique
and in 1..Int.MAX_VALUE, and every present logical occurrence ID is a unique canonical 32-character lowercase
hex encoding of 16 bytes. Require no more than 16 GiB receiver-computed total bytes, 64 UTF-8 bytes for group,
128 for sender name, 256 for display name, a canonical 64-character lowercase hexadecimal SHA-256, and
nonnegative sizes within the kind cap. An empty manifest is a valid “nothing available” screen. Sender total
and labels are advisory; the receiver recomputes totals and owns category labels.

Per-item caps are APK 1 GiB, USER_FILE 512 MiB plus its 4,097-byte header allowance,
APP_BACKUP_RELAY 2 GiB including framing and SOUND_FILE 64 MiB plus its 4,097-byte header allowance. WALLPAPER
permits 32 MiB of image bytes plus a 4,097-byte header allowance per wire item. HOME_LAYOUT_GUIDE permits
128 MiB of encoded image bytes, 64 KiB of metadata, and 4 KiB of framing, so checked addition caps its complete
wire item at 128 MiB + 68 KiB. Every other current kind has a 64 MiB item cap; narrower codec limits still apply.
Per-kind aggregate caps are APK 8 GiB, APP_BACKUP_RELAY 8 GiB, SOUND_FILE 256 MiB,
WALLPAPER 64 MiB + 8,194 bytes across at most one SYSTEM and one LOCK item, HOME_LAYOUT_GUIDE one complete item
of 128 MiB + 68 KiB, and 64 MiB for every other kind. The whole-manifest 16 GiB cap still wins. Any limit change
is a reviewed protocol/security change, not a runtime preference.

USER_FILE has two aggregate limits: raw payload bytes total at most 4 GiB, and complete encoded item sizes total
at most `4 GiB + (512 × 4,097 bytes)` = 4,297,064,960 bytes. The receiver enforces the encoded bound from the
manifest, then enforces the exact raw-payload sum as headers are parsed. Eight 512 MiB payloads with eight valid
headers are accepted; one additional raw byte or encoded byte beyond the corresponding limit is rejected.

For every filesystem volume `v`, both pre-selection and immediate pre-apply admission require
`usable(v) >= futureAllocation(v, phase) + reserve(v)`, using checked arithmetic, where
`reserve(v) = max(256 MiB, ceil(capacity(v) * 5 / 100))`. `futureAllocation` counts only bytes not already
allocated when `usable` is sampled. At pre-selection it includes future staging, destination, and provider
temporary allocations; at pre-apply the existing staged file is excluded and only remaining destination/
temporary allocations count. A same-volume rename of the existing staged file is zero future bytes; a copy
counts its not-yet-created destination bytes. The exhaustive capability registry must declare phase- and volume-
specific future-allocation formulas for every kind; tests also assert usable space after success remains at
least `reserve(v)`.

### Side-effect inventory and honest guarantees

Each kind gets an explicit replay key, durable evidence, and uncertainty behavior before it ships:

| Kind | Durable evidence required before skip | If authoritative proof is unavailable |
|---|---|---|
| CONTACTS_VCF | Stable provider identity plus canonical field readback. | Disclose at-least-once and possible duplicate. |
| CALENDAR_ICS | Calendar/event identities plus canonical timezone, recurrence, exception, reminder, attendee, and mapping readback. | Explicit degradation/at-least-once residual; retries may duplicate. |
| CALL_LOG | Provider row identity or canonical target query. | Explicit at-least-once residual; retries may duplicate. |
| SMS | Provider row identity or canonical target query. | Explicit at-least-once residual; retries may duplicate. |
| MMS | Parent/part/address identities and successful atomic batch or compensation proof. | Explicit at-least-once/orphan residual; retry only with confirmation. |
| APP_INVENTORY | Lineage/item/hash-keyed durable install-action rows. | Rebuild missing actions without marking any app installed. |
| APK | Package, installed version, signing digest, split set, and installer outcome/readback. | Pending/failed; never infer installed from receipt ack. |
| SETTINGS | Per-key prior value, intended value, write result, and immediate readback. | If state later differs, classify unknown/user-changed and require confirmation; never auto-overwrite. |
| WALLPAPER | Per-surface artifact hash, before/after wallpaper IDs, setter result, and conflict check. | Unknown/manual; never overwrite a later user change. |
| SOUND_SELECTION | Intended role mapping plus post-write role/URI readback. | Unknown/manual; do not replay over a later user selection. |
| SOUND_FILE | MediaStore URI/ID + size/hash and no-replace publication proof. | Keep pending/unknown; do not silently reinsert. |
| BLUETOOTH_DEVICES | Lineage/item/hash-keyed re-pair action rows; no bond state is claimed transferred. | Rebuild missing actions without claiming devices paired. |
| APP_BACKUP_RELAY | Immutable URI + lineage/item/hash + exact bytes + active grant/action state. Every open checks the persisted deadline synchronously. | Retain pending source until its 24-hour access deadline; later new opens fail even if scheduled cleanup was delayed. Revoke/delete converges to EXPIRED_BLOCKED with re-selection guidance. |
| USER_FILE | Final scoped URI/document ID + byte size + SHA-256; atomic no-replace publish. | Publish a distinct lineage/hash name; never overwrite. |
| DEFAULT_ROLES | Lineage/item/hash action plus current role-holder readback after each explicit action. | Unknown/user-changed; never silently reclaim a role. |

An exhaustiveness test asserts this table's executable policy registry covers `ItemKind.entries`; Home Map adds
its kind and policy in its own PR. Tests cover interrupted replay for every row and intentional later moves. A
new lineage must not be suppressed because an earlier move imported similar content.

## Launcher feasibility

### Platform decision

An ordinary app cannot exactly read and recreate arbitrary launcher pages, cells, folders, or
widget bindings. LauncherApps exposes launcher activities, not layout coordinates, and the Android
app sandbox protects a launcher's private database:

- https://developer.android.com/reference/android/content/pm/LauncherApps
- https://source.android.com/docs/security/app-sandbox

AOSP Launcher3 declaring backup support and including database files in its scheme proves only that
the launcher/backup transport may own a restore path; it does not grant Portage a selective API:

- https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest-common.xml
- https://android.googlesource.com/platform/packages/apps/Launcher3/+/master/res/xml/backupscheme.xml

Path A is therefore a same-launcher Launcher3/Seedvault hardware spike. Portage never copies or
patches raw launcher data. If a supported, selective, user-comprehensible restore cannot pass PR 8,
launcher restore remains an external prerequisite and the product uses Home Map.

### Home Map design and fidelity limits

Home Map is a guide, not automatic placement:

1. V1 imports ordered page/folder screenshots with the system picker. V2 may guide one foreground,
   user-consented MediaProjection session. Android requires fresh consent for each session:
   https://developer.android.com/media/grow/media-projection#user-consent
2. Projection capture uses a fresh consent result and one single-use session. V2 contextually requests
   POST_NOTIFICATIONS before capture and verifies both app notifications and the dedicated capture channel are
   enabled with importance above NONE. Any denial/disablement does not start projection and routes to the V1
   picker, because capture must have a visible foreground-service notification and Stop action while Portage is
   backgrounded. The service rechecks both before accepting each frame and stops on failure. A session also has
   a hard 10-minute deadline from consent that relaunch cannot extend.
   When granted, start a mediaProjection foreground service, obtain the projection there, register its callback, and
   create one virtual display. On Android 14+, request Display.DEFAULT_DISPLAY with
   MediaProjectionConfig.createConfigForDefaultDisplay(), never the default user-choice picker;
   older releases use the legacy screen-capture intent:
   https://developer.android.com/reference/android/media/projection/MediaProjectionConfig
   An explicit, notification-visible **Capturing Home Map** state survives leaving Portage for the
   launcher; Activity onStop alone does not cancel it. Returning to
   Portage, tapping Stop in the notification, revocation, timeout, error, or completion idempotently
   stops projection, releases the virtual display, Surface, ImageReader, and open images, discards
   the token, terminates the foreground service, and rejects later frames. Required service type and
   permissions are structural manifest gates. Compare capture callbacks with current display metrics
   and abort on invisibility or a clear size mismatch, but do not claim authoritative capture-source
   detection: Android exposes none and an OEM may override the display-only request. If hardware
   acceptance cannot prove launcher capture on a supported build, disable V2 and retain the ordered
   picker as the guaranteed path. Returning to Portage also exposes an always-reachable in-app Stop control.
   Notification denial behavior follows:
   https://developer.android.com/develop/ui/views/notifications/notification-permission
3. Secure windows may be blank; notifications, widgets, badges, and names may expose personal data.
   Preview every frame and allow delete/retake before transfer.
4. Encode a typed HOME_LAYOUT_GUIDE with ordered pages/folders, launcher package/version, source
   display/density/insets/posture, dock/folder labels, user-correctable app matches, and widget
   placeholders. A widget placeholder does not preserve binding or widget data.
5. Validate before decode: at most 32 images, 8 MiB encoded per image, 128 MiB aggregate image
   bytes, 8,192 pixels per dimension, 12 megapixels per image, 64 KiB metadata, 4 KiB framing, and
   only magic-sniffed static PNG/JPEG/WebP. Checked addition caps the entire wire item at 128 MiB +
   68 KiB. Decode one sampled image at a time under a 64 MiB decoded working-set budget.
6. Keep images and progress in noBackupFilesDir, exclude cloud and D2D backup explicitly, and
   encrypt in transit. Purge the sender copy only after the receiver records APPLIED_DURABLE. Purge
   the receiver copy immediately after finish/cancel, or seven days after APPLIED_DURABLE; reopening
   the guide does not extend that deadline. App-private storage is sandbox protection, not a claim
   of file-level encryption at rest.
7. After relevant apps are installed and the preferred Home role is set, show a full-screen,
   page-by-page reference plus installed/missing-app, folder, dock, and widget checklists. Grid,
   crop, parallax, icon pack, font, and launcher-version differences are declared fidelity losses;
   Portage cannot verify exact final placement.
8. The safe default never mutates wallpaper and never requests overlay access. Overlay special
   access is not part of V1:
   https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)
9. An optional desaturated 20–30% ghost wallpaper may call only the SYSTEM/HOME setter and is
   available only when Portage already holds and verifies the intended final static SYSTEM/HOME
   artifact **and** the target has a distinct positive FLAG_LOCK wallpaper ID. A mirrored/no-distinct-
   lock target disables ghost mode because Android can implicitly preserve the old system wallpaper
   as a new lock wallpaper when SYSTEM changes. Live, target-only, inaccessible, or independently
   cropped wallpapers also disable it. Use only the explicit non-backup system-surface setter.
10. Before mutation, persist lineage and the intended final SYSTEM/HOME artifact. Record the positive
    wallpaper ID returned when installing the ghost. Finish/cancel restores immediately. Process
    death cannot guarantee immediate restoration; the durable journal restores on next launch.
    Restore only if getWallpaperId(FLAG_SYSTEM) still equals the recorded ghost ID, so a later user
    wallpaper change wins. This is a best-effort conflict guard, not an atomic compare-and-set. Verify
    that the restoration setter's returned ID became current; ordinary apps cannot use wallpaper-byte
    readback on current Android as proof. Purge the journal/final artifact only after restoration is
    confirmed; retain a visible recovery action when the outcome is unknown. Record the pre-ghost
    FLAG_LOCK ID and require it to remain identical throughout; no LOCK setter or clear operation is
    allowed. Any final LOCK migration remains a separate, explicit operation using transferred mirror
    metadata after the ghost workflow has ended.

Wallpaper framework reference:
https://developer.android.com/reference/android/app/WallpaperManager

## Sequential implementation PRs and acceptance gates

Each numbered unit is independently reviewed and mergeable. Lettered units are separate PRs, not
one high-risk mega-change. A later unit may not bypass an earlier gate. Every incompatible change to a
serialized message, enum, or handshake increments PROTOCOL_VERSION, updates wire goldens, and rejects mixed
versions during pairing; `ignoreUnknownKeys` is not compatibility for unknown enum values.

### PR 0a — Receipt phases, lineage, and durable checkpoint store

- Add the state machine and authenticated stable lineage before any dependent cleanup or workflow. Validate
  logical occurrence IDs for canonical length/encoding and manifest-wide uniqueness before UI and before every
  checkpoint lookup/write; PR 5 adds the remaining manifest/resource checks. Bump the current protocol from v5
  to v6 because the handshake/acknowledgement contract changes; v5↔v6 peers refuse cleanly during pairing rather
  than failing after manifest exchange.
- **Gate:** construction uses exactly 16 SecureRandom bytes only on explicit **Start a new move**.
  One hundred consecutive moves receive distinct lineages; a process restart retains the current
  lineage; a second new move cannot read or suppress work with the first move's checkpoints; and an
  authenticated peer mismatch is rejected. The initial channel creates a distinct 32-byte resume
  credential; both-process restart resumes only with proof of that secret, while a fresh QR without
  it fails. Connected cancel requires authenticated delete acknowledgement from both sides. Disconnected cancel
  tombstones/deletes locally, the offline peer remains unclaimed, and its injected clock purges at the exact
  30-day deadline but not at deadline minus one millisecond. Two identical selected files have distinct occurrence IDs and
  neither checkpoint suppresses the other; malformed, wrong-length, or duplicate occurrence IDs are rejected
  before UI with zero checkpoint access and zero side effects. For each state boundary, 20
  kill/restart repetitions
  never promote RECEIVED_VERIFIED/APPLYING to APPLIED_DURABLE. Dropping BATCH_ACK produces
  UNKNOWN_INTERRUPTED on the sender in 20/20 runs.

### PR 0b — Backup/key containment

- Move sensitive state to noBackupFilesDir, add legacy and dataExtractionRules exclusions for cloud
  and D2D, and use a non-exportable Android Keystore ADB key if the ADB signer supports it.
- **Gate:** cloud-backup and actual adb/OEM-supported D2D extraction artifacts on both flavors
  contain zero ledger, screenshot, staging, wizard, ADB-private-key, or grant state. If provider-
  backed signing is infeasible, record that downgrade and require verified key deletion and
  permission revocation controls.

Android backup rules:
https://developer.android.com/identity/data/autobackup

### PR 0c — Lifecycle and main-thread safety

- Move cleanup to lineage-aware ownership and all blocking preparation to injected IO.
- **Gate:** an automated matrix recreates each app 20 times in Preparing, Pairing/QR, Transferring,
  and terminal/follow-up states with zero referenced files/sessions deleted and zero stale callbacks
  changing the active epoch. StrictMode instrumentation reports zero transfer file/network work on
  Main; cancellation terminates within 2 seconds in the injected blocking-stream fixture.

### PR 1 — Wallpaper capability truth

- Hardware-test static SYSTEM-only, distinct SYSTEM+LOCK, mirrored LOCK, live, and denied/unavailable
  sources on both supported GrapheneOS devices.
- **Gate:** all 10 device/case observations record build fingerprint and artifact/result. Automatic
  wallpaper is offered only for proven readable cases; every other case is visibly manual. Mirror
  state round-trips without leaving a stale target lock wallpaper.

### PR 2 — Honest export and terminal outcomes

- Introduce COMPLETE/PARTIAL/FAILED export results, final apply verdicts, remediation codes, and
  truthful failure screens. Carry export disposition/counts in the manifest and increment the then-current
  protocol version.
- **Gate:** every registered production exporter in both flavors runs every applicable fixed oracle
  case from the export contract; an exporter-ID set equality assertion prevents omissions. All
  attempted/exported/skipped counts are exact, including MMS, and no missing final verdict displays
  “moved.”

### PR 3 — Provider replay and fidelity series

- PR 3a: SMS/MMS/call-log replay and MMS compensation.
- PR 3b: calendar mapping/rich-field preservation or explicit degradation, plus contacts, files, sounds,
  settings, and wallpaper provider replay contracts that do not depend on the later action ledger.
- **Gate:** apply → disconnect before final verdict → restart same lineage produces zero duplicate
  or orphan writes for each PR 3 provider claiming exactly-once. Any kind that cannot prove this is labeled
  at-least-once in UI/docs and a fixture demonstrates the residual. A new lineage remains eligible.
  Calendar fixtures cover DST-zone crossing, recurrence exception, reminder, attendee, local/cloud
  mapping, and malformed records with exact preserved/degraded counts.

### PR 4 — Persistent Finish your move and cancellation

- Build the action ledger and lineage/epoch-owned jobs before relay or Home Map consumes it. Implement replay
  policies for APP_INVENTORY, APK, BLUETOOTH_DEVICES, and DEFAULT_ROLES; the relay policy remains in PR 6a.
- **Gate:** process death at each of installer, Settings, role, Bluetooth, and manual-import
  round-trips preserves 100% of pending actions across 20 repetitions; reset cancels within 2
  seconds and no old-lineage callback mutates the new screen.

### PR 5 — Manifest and resource hardening

- Introduce the exhaustive ItemKind/flavor capability registry, validate the manifest before UI, and enforce
  receiver-authoritative budgets for every kind and destination. Later PRs consume this registry rather than
  creating parallel catalogs.
- **Gate:** use the exact validation constants and storage formula above. IDs 0, -1, duplicate, and
  Int.MAX_VALUE+1-on-wire; counts 0/512/513; totals 16 GiB/16 GiB+1 and Long overflow; each UTF-8 string
  cap/cap+1; invalid/mixed-case hashes; every per-kind cap/cap+1; and usable space at required-1/
  required bytes are tested before selection and immediately before apply. A registry test requires
  every ItemKind entry to declare its caps and peak bytes on each touched volume. USER_FILE fixtures cover eight
  512 MiB payloads plus headers, raw and encoded limit+1, and checked multiplication overflow.

### PR 6a — Relay delivery, framing, and grant lifecycle

- Implement exact framing, immutable atomic publish, targeted FileProvider handoff, durable pending action,
  replay policy, and revoke lifecycle; make public Downloads an explicit privacy downgrade. The narrowly rooted
  provider checks persisted grant state and the 24-hour deadline synchronously on every open, so delayed work or
  Doze cannot authorize a new post-deadline descriptor. Expiry cleanup first commits durable EXPIRING intent,
  then idempotently revokes and deletes, then commits EXPIRED_BLOCKED; provider startup and app startup reconcile
  any interrupted or overdue state. Already-open descriptors remain the documented Android residual.
- **Gate:** 1-byte-short, 1-byte-long, hash-mismatch, collision, target-refusal, cancel, restart, and
  expiry-at-24-hours fixtures yield no success, overwrite, broad grant, or lost pending action. With cleanup
  deliberately unscheduled/process-dead, provider open succeeds at deadline minus one millisecond and fails at
  the exact deadline; the open triggers overdue reconciliation. Crash injection
  after EXPIRING persistence, revoke, deletion, and EXPIRED_BLOCKED persistence converges on revoked + deleted +
  EXPIRED_BLOCKED after restart. Cleanup eventually deletes/revokes and the UI requires
  source re-selection rather than offering a dead retry. The happy path recipient reads exactly the
  staged size/SHA; after revoke, a new open fails. A descriptor
  opened before revoke may remain readable and is an explicit Android residual, not a failed promise.

### PR 6b — Transport identity, addresses, and resume

- Try all address hints; reject duplicate/invalid messages; implement completed-item checkpointing.
  Add byte resume only if its stronger invariant is implemented.
- **Gate:** a deterministic 8 MiB item interrupted after 3 MiB reconnects under the same lineage:
  completed-item resume sends 0 payload bytes for APPLIED_DURABLE; optional byte resume starts at the
  last verified chunk and sends no more than the remaining 5 MiB plus one chunk. A size/hash/schema
  change restarts at byte 0. Duplicate begin or wrong/duplicate ack causes protocol failure and zero
  duplicate applies. Three hints with success only on hint 3 connect within a 15-second total bound.
  VCF/ICS/JSONL 64 MiB fixtures pass with a 256 MiB test heap; Home Map uses its stricter limits.

### PR 7 — One-app unification, preflight, and capabilities

- Follow ADR-007 as separate build-graph, routing, and retirement PRs; drive just-in-time preflight and
  aggregate verified/applied byte progress from PR 5's exhaustive registry.
- The accepted downgrade is explicit: the full degoogle artifact contains receive privilege code
  in Send mode. Compensating controls are non-negotiable: Lite remains bridge-free;
  feature-send has no direct/transitive bridge/wizard dependency; the app shell is routing-only;
  receive privilege construction is lazy and Receive-only; flavor manifests structurally exclude
  forbidden Lite permissions/components; role switch/decommission revokes grants and deletes the
  ADB key/wizard state, then verifies absence and refuses Send/decommission completion if it cannot.
- **Gate:** packaged Lite has zero bridge/native libs and forbidden SMS/call/APK/secure-setting
  permissions/components; dependency graph proves feature-send and Lite cannot resolve bridge or
  wizard; Send-mode tests touch zero privilege constructors; source containment proves the app shell
  references routing symbols only. Reset readback proves grant absent and key/wizard state deleted.
  Hardware verification passes before old artifacts retire.

### PR 8 — Same-launcher restore go/no-go spike

- Matrix: Pixel 9 Pro Fold → Pixel 10 Pro Fold and reverse, current stable GrapheneOS fingerprints
  recorded. Configure 5 columns × 5 rows → 5×5 and 5×5 → 4×5 profiles, three repetitions per
  direction/profile (12 runs); inability to configure either exact profile is NO-GO. Each fixture
  contains 3 pages, 12 icons at recorded page/row/column coordinates, 5 ordered dock entries, 2
  folders with ordered contents, 2 widgets, and 1 missing app.
- **Gate:** GO only if a supported launcher/backup-owned flow—not raw DB access—restores 100% of
  installed icon/dock/folder page/cell placements in all same-grid runs, reports missing apps
  without silent collapse, classifies both widgets as restored or manual, replays no non-launcher
  Portage data, and survives two reboots. For 5×5 → 4×5, non-widget entries must preserve row-major
  order using source ordinal `page*25 + row*5 + column` mapped to target page/row/column by 20 cells
  per page; dock order and folder membership must remain exact, and widgets may be explicitly manual.
  Any missing, collapsed, duplicated, or otherwise displaced entry is NO-GO for product integration
  and selects Home Map.

### PR 9 — Home Map

- Add typed capture/import, lifecycle teardown, exact limits, app matching, in-app reconstruction,
  durable checklist, and guarded optional ghost wallpaper. Appending HOME_LAYOUT_GUIDE to ItemKind increments
  the then-current PROTOCOL_VERSION and updates wire goldens; mixed old/new versions refuse at pairing before
  either peer decodes a manifest.
- **Gate:** boundary tests cover 32/33 images, 8 MiB/8 MiB+1 per image, 128 MiB/128 MiB+1 aggregate
  image bytes, 64 KiB/64 KiB+1 metadata, 4 KiB/4 KiB+1 framing, and complete wire size at
  128 MiB+68 KiB / that limit+1 using checked arithmetic; also cover dimension/pixel
  overage, wrong magic, malformed/truncated images, and a decompression bomb. Instrumented Java,
  native, and bitmap allocation accounting must show no more than 64 MiB peak decoded working set
  under a 256 MiB test heap. On Android 14+, tests prove production requests the default-display
  configuration and never the user-choice configuration; a negative fixture shows app-window capture
  becoming invisible after Home does not produce accepted guide frames. A hardware path covers
  Portage → launcher → 3 pages + 2 opened folders → Portage on every supported build; any build where
  the OEM override prevents this disables V2. POST_NOTIFICATIONS denial starts no projection and lands on
  app-notifications disabled, channel importance NONE, or permission denial starts no projection and lands on
  the ordered-picker fallback; grant plus enabled channel shows the FGS Stop action, and returning shows the
  in-app Stop action. Disabling either during capture stops before the next accepted frame. The session stops at
  10 minutes and the injected-clock test proves 10 minutes minus one millisecond remains active while the exact
  deadline stops it.
  Capture survives that app switch, while every stop path
  yields zero later frames and a stopped service. The fixture remains usable after process death. Ghost mode is unavailable when
  FLAG_LOCK has no distinct positive ID; eligible runs call no LOCK setter/clear and retain the exact
  pre-ghost LOCK ID. A later user SYSTEM change wins in 20/20 crash-recovery runs; guide data is
  absent immediately after finish/cancel and at the seven-day deadline.

### PR 10 — Release confidence

- Add wire goldens, all-kind loopbacks, lifecycle/fault/static-analysis/backup gates, and hardware
  evidence; resolve ExperimentalCoroutinesApi warnings or opt in deliberately.
- **Gate:** CI runs lint, detekt, ktlint, every supported kind in both flavors, all PR 0–9 regression
  fixtures, and fails on any warning not present with rationale in a checked-in warning allowlist.
  Release evidence names commit, APK SHA-256, exact Gradle
  command, XML/JUnit artifact, both device fingerprints, security patch, and these 11 named
  scenarios: (1) clean, (2) disconnect before receipt, (3) disconnect after RECEIVED_VERIFIED,
  (4) disconnect after apply before final verdict, (5) process death, (6) low storage,
  (7) permission denial, (8) partial provider, (9) installer cancel, (10) reset, and (11) replay.
  Run degoogle end to end in both device directions and Lite negative-capability checks on each
  device. Every named assertion must pass; there are no waived, retried-away, or “explained” failures.
  Missing evidence blocks release; raw test-count totals do not substitute for the matrix.

## Risks and explicit residuals

- **At-least-once providers:** duplicates remain possible wherever authoritative readback is absent.
  The UI must say so before retry; a local journal is evidence of intent, not target truth.
- **Persistent metadata:** lineage, hashes, URIs, and screenshots are sensitive/linkable. Minimize,
  store app-private/no-backup, bound retention, purge deterministically, and define key lifecycle
  before claiming encryption at rest.
- **Unified full artifact:** the sender loses binary absence of privilege code. Module, flavor,
  lazy-construction, reset, and readback gates reduce but do not erase that downgrade.
- **Relay sharing:** FileProvider works only when the target accepts an import/share URI. Public
  Downloads improves discoverability by deliberately increasing exposure; it requires explicit
  consent and cleanup guidance.
- **Calendar:** unsupported rich fields, account semantics, and target-provider normalization can
  change meaning. No full-fidelity claim until the fixture matrix passes.
- **Launcher/Home Map:** exact arbitrary restore remains impossible; widgets, icon packs, grid
  normalization, crop, parallax, secure-window capture, and user edits are fidelity limits.
- **Ghost wallpaper:** a crash/force-stop can leave it installed until Portage next launches. A
  later user change always wins over automated recovery.

## Verification baseline

The earlier draft reported 183 Gradle tasks and 1,188 tests with zero failures/errors under a
rerun-tasks invocation. Fresh recounts produced 1,187 and 1,184, and no immutable result artifact
ties any count to an exact command/commit. The exact test-count claim is therefore withdrawn.

Stable observations remain:

- core-model:test reported NO-SOURCE;
- many ExperimentalCoroutinesApi warnings were emitted;
- .github/workflows/build.yml:71-79 runs unit and assembly tasks but no Android lint, detekt, or
  ktlint gate; and
- there is no full two-device hardware sign-off for this plan.

From PR 10 onward, only a stored result artifact tied to commit, exact command, toolchain, flavor,
and device fingerprints is acceptable release evidence.
