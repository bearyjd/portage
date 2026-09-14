# Turnkey Migration Audit and Delivery Plan

**Status:** Approved for sequential delivery  
**Date:** 2026-09-13  
**Decision:** Stabilize recovery and correctness before expanding the migration surface. Investigate launcher-owned restore before building the screenshot-assisted fallback.

## Goal

Make Portage feel like one continuous, trustworthy move rather than a transfer followed by a collection of fragile manual steps. The target experience is:

1. prepare both phones with clear, contextual guidance;
2. transfer without blocking the UI or losing progress unnecessarily;
3. report partial outcomes truthfully;
4. survive rotation, process death, retries, and settings/install handoffs;
5. preserve a durable "Finish your move" checklist; and
6. restore or reconstruct the home screen with the best mechanism available.

## Scope

This plan covers:

- transfer lifecycle, retry, framing, and resume behavior;
- provider correctness and large-payload handling;
- preflight, progress, failure, and post-transfer UX;
- one-app unification already accepted in ADR-007;
- launcher/Seedvault restore research;
- a screenshot-assisted Home Map fallback; and
- missing automated and hardware verification gates.

## Out of Scope

- Merging any implementation in this planning PR.
- Reading or modifying another launcher's private database.
- Root-dependent behavior.
- Accessibility-driven launcher automation.
- A permanent draw-over-other-apps overlay.
- Making Portage the user's long-term launcher.
- Weakening the sender/receiver privilege boundary to save a screenshot tap.

## Existing Strengths to Preserve

- Noise-authenticated, encrypted local transport.
- Pairing payload expiry and bounds checking.
- Declared-size and SHA-256 verification before apply.
- Per-kind, aggregate, and free-space limits.
- Numeric staging names rather than user-controlled paths.
- A compile-time sender/receiver privilege firewall.
- CI checks for flavor boundaries, dependencies, and native-library alignment.
- User-consented restoration for privileged/default-app operations.

## Audit Findings

Priorities mean: **P0** correctness or data-integrity risk; **P1** reliability or major product friction; **P2** hardening, maintainability, or coverage.

### P0 — Correctness and data integrity

1. **Activity recreation can destroy active transfer state.**

   Both activities remove staging data from `onCreate`; the receiver also abandons uncommitted installer sessions. A retained ViewModel can therefore reference payloads or install sessions deleted by a rotation or fold-state recreation.

   Evidence: `app-send/.../MainActivity.kt:90`, `app-recv/.../MainActivity.kt:118`.

   Recommendation: move orphan cleanup to a once-per-process coordinator, record active session ownership, and never remove resources referenced by a live session.

2. **The failure screen makes a false rollback claim.**

   `FailedScreen` says "Nothing was changed on this phone," but items are applied immediately as they are received. A later failure can follow successful writes.

   Evidence: `app-recv/.../ui/FailedScreen.kt:67`, `app-recv/.../transfer/ItemStreamReceiver.kt:256`.

   Recommendation: retain per-item receipts throughout the session and render the same moved/skipped/failed accounting on batch failure as on success.

3. **Large exports and hashes execute from the main dispatcher.**

   `SenderViewModel` starts manifest construction in a default `viewModelScope.launch`; `ManifestBuilder` performs blocking provider exports and file hashing. Valid large files, APKs, and relays can freeze the UI and delay cancellation.

   Evidence: `app-send/.../SenderViewModel.kt:241`, `app-send/.../ManifestBuilder.kt:54`.

   Recommendation: inject an IO dispatcher for probing, export, copy, and hashing. Keep only state publication on Main.

4. **Interrupted retries can duplicate restored user data.**

   If an apply succeeds but its final acknowledgement is lost, a new transfer can replay the same SMS, MMS, call-log, or calendar records. SMS, MMS, and calendar lack durable replay protection, while the call-log journal is cleared at the next `beginTransfer`.

   Evidence: `ReceiverViewModel.kt:355`, `CallLogProviders.kt:86`, `SmsProviders.kt:97`, `MmsProviders.kt:131`, `CalendarProviders.kt:53`.

   Recommendation: use persistent, bounded idempotency receipts keyed by a source dataset and canonical record fingerprint, or reliable target-provider identities. Test apply, disconnect, begin again, and replay for every side-effecting provider.

### P1 — Reliability and turnkey experience

5. **The app-backup relay does not provide a usable handoff.**

   Relay output is written below Portage's app-specific external directory, then the target app is simply launched. The target normally cannot discover or read that file.

   Evidence: `app-recv/.../relay/AndroidRelayHandoff.kt:49`, `app-recv/.../ui/ReceiverApp.kt:403`.

   Recommendation: publish through `MediaStore.Downloads`, or issue a `FileProvider` content URI in an explicit import/share intent with temporary read access. Always retain a visible file/share fallback.

6. **Relay framing can accept a truncated backup.**

   If a selected relay file changes length, the staged outer item may hash correctly while the receiver copies only the stale declared blob length and reports success.

   Evidence: `AppBackupRelayProviders.kt:299`, `AndroidRelayHandoff.kt:59`.

   Recommendation: reuse the exact-length and EOF invariant already implemented for user files.

7. **MMS insertion is not failure-atomic.**

   The parent row is inserted before addresses and parts. Failure in a child returns failure but leaves the parent and any prior children behind.

   Evidence: `AndroidMmsStore.kt:65`.

   Recommendation: use a provider batch when supported; otherwise compensate by deleting the new message on any child failure or exception.

8. **Resume is present in the schema but unused.**

   `Select.resume` and `ResumePoint` exist, but the receiver does not send checkpoints and the sender ignores them and opens each file at byte zero.

   Evidence: `core-model/.../Messages.kt:38`, `app-send/.../transfer/TransferEngine.kt:50,98`.

   Recommendation: first persist completed-item checkpoints. Add byte-offset resume only after offsets can be safely bound to item identity, declared size, content hash, and session origin.

9. **Post-transfer work is process-ephemeral.**

   APK prompts, Bluetooth re-pairing, relay imports, permission restoration, and default roles are held mainly in ViewModel state. Process death during Settings or installer handoffs can erase the remaining work.

   Evidence: `app-recv/.../ReceiverState.kt:87`.

   Recommendation: store a privacy-minimized migration receipt and action ledger with pending, complete, failed, retry, and dismissed states.

10. **Reset races with the receiver coroutine.**

    The receiver does not retain its transfer job. Closing the channel during reset can produce a late failure or apply callback that overwrites Idle or mutates a newer session.

    Evidence: `app-recv/.../ReceiverViewModel.kt:435`.

    Recommendation: own and cancel the transfer job and guard every state publication with a monotonically increasing session epoch.

11. **Only the first advertised IP address is attempted.**

    The QR payload carries ordered address hints, but every retry targets `firstOrNull()`.

    Evidence: `core-transport/.../NoiseSecureChannel.kt:147`.

    Recommendation: try all sanitized hints within one cumulative deadline, using a bounded stagger where useful.

12. **Protocol identity checks are incomplete.**

    A duplicate authenticated `ITEM_BEGIN` can apply the same selected item repeatedly, while sender acknowledgements are not strictly validated against the current and uniquely sent item IDs.

    Evidence: `ItemStreamReceiver.kt:91`, `TransferEngine.kt:64`.

    Recommendation: reject duplicate begins; validate item and batch acknowledgement identity, uniqueness, and membership before accepting completion.

13. **Large valid payloads are repeatedly materialized in memory.**

    Contacts, calendars, JSONL records, and wallpaper frames can exist as raw lines, transformed lines, decoded objects, and byte arrays concurrently.

    Evidence: `VCard3.kt:126`, `Ics.kt:78`, `JsonLines.kt:42`, `WallpaperProviders.kt:217`.

    Recommendation: stream parsing and apply, bound individual records and lines, and test near-limit inputs with a constrained heap.

14. **Calendar identity is flattened.**

    Export omits source-calendar identity and import chooses the first writable calendar. Work, personal, local, and cloud events can be mixed or synced unexpectedly.

    Evidence: `AndroidCalendarStore.kt:31,58`.

    Recommendation: transfer calendar descriptors, default to a dedicated local Portage calendar, and allow explicit source-to-target mapping. Surface all skipped/malformed event counts.

15. **APK silent-upgrade replacement semantics need proof.**

    The upgrade path intentionally accepts a newer incoming version, while the generated install-create command does not visibly request replacement.

    Evidence: `LocalAdbBridge.kt:193`.

    Recommendation: verify on hardware, add replacement semantics if required, and pin the full command in tests while preserving the no-downgrade rule.

16. **The setup journey exposes readiness too late.**

    Sender permissions are requested in one burst after Start; receiver rows label privileged work only as "advanced."

    Evidence: `app-send/.../ui/HomeScreen.kt:46,155`, `app-recv/.../ui/ChecklistScreen.kt:412`.

    Recommendation: introduce a category-first preflight for network access, storage, battery, selected-domain permissions, and advanced capabilities. Request permissions contextually and allow setup inline without losing selections.

17. **"Done" is an untracked manual second phase.**

    Installs, Bluetooth pairing, backup imports, permissions, roles, and failed-item retries do not form a persistent, verifiable completion flow.

    Recommendation: replace Done with a durable "Finish your move" checklist and a final confirmation based on real action outcomes.

18. **The accepted one-app plan remains unimplemented.**

    Two APKs force users to understand product architecture before migrating and duplicate UI/service code.

    Evidence: `docs/prp/ADR-007-app-unification-and-distribution.md:16`.

    Recommendation: execute ADR-007 after recovery primitives are stable, preserving the compile-time privilege firewall and flavor gates.

### P2 — Hardening and quality

19. **Wallpaper mirror behavior contradicts its implementation.**

    A source whose lock wallpaper mirrors Home emits only the Home item, but import writes only `FLAG_SYSTEM`; an old target lock wallpaper can remain.

    Evidence: `AndroidWallpaperStore.kt:63`.

    Recommendation: encode mirror state and apply it explicitly to both surfaces.

20. **Status and progress models are too coarse.**

    `SKIPPED` conflates user choice, missing permission, unsupported platform, and setup failure. Progress counts items, so a contact bundle and a 1 GiB APK contribute equally.

    Evidence: `ItemStatusDisplay.kt:33`, `TransferScreen.kt:35`.

    Recommendation: add stable reason/remediation codes and byte-based current/overall progress, rate, elapsed time, and approximate remaining time.

21. **Flavor and item catalogs can drift.**

    Offerings, receiver rows, permission requests, and copy are not all derived from a single exhaustive capability registry.

    Evidence: `ReceiverChecklist.kt:127`, ADR-007 open items.

    Recommendation: create one exhaustive `ItemKind`/flavor capability registry with completeness tests.

22. **Automated coverage does not match the product surface.**

    `core-model:test` is `NO-SOURCE`; only one real wire-loopback item kind is covered; Compose recreation and full provider paths are largely outside CI; Android lint/detekt/ktlint are not CI gates.

    Recommendation: add model wire goldens, table-driven loopback coverage for every item kind, Compose lifecycle tests, recorded regression fixtures, static-analysis gates, and hardware release sign-off.

## Launcher Layout Feasibility

### Decision

An ordinary Portage installation cannot reliably read or recreate an arbitrary launcher's page, cell, folder, and widget database. Android's public `LauncherApps` surface does not expose those coordinates, while per-app sandboxing protects the launcher's private data.

- Android `LauncherApps`: https://developer.android.com/reference/android/content/pm/LauncherApps
- Android app sandbox: https://source.android.com/docs/security/app-sandbox

Portage will therefore use a two-path strategy.

### Path A — Launcher-owned restoration

When the source and target use a compatible launcher, try the launcher's own backup/restore route first. AOSP Launcher3 declares a backup agent and includes its grid databases in its backup scheme:

- Launcher3 manifest: https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest-common.xml
- Launcher3 backup scheme: https://android.googlesource.com/platform/packages/apps/Launcher3/+/master/res/xml/backupscheme.xml

The first launcher deliverable is a two-device GrapheneOS hardware spike covering:

- pages, dock, folders, and widgets;
- missing applications;
- matching and differing grid sizes;
- launcher/version compatibility;
- readback rather than command-exit success; and
- reboot persistence.

Portage must not copy or patch the raw launcher database. If launcher/Seedvault restoration is unavailable or unverified, the flow falls through immediately to Home Map.

### Path B — Home Map

Home Map is a first-class, privacy-preserving reconstruction guide.

1. Capture ordered screenshots for every page, the dock, and opened folders. V1 uses a dedicated ordered picker. V2 can guide a single user-consented MediaProjection session; Android requires consent for every capture session: https://developer.android.com/media/grow/media-projection#user-consent
2. Transfer a typed `HOME_LAYOUT_GUIDE`, not generic user files.
3. Keep source images in app-private storage and exclude them from backup.
4. Record launcher package/version, display size, density, safe-area insets, orientation/posture, page order, dock, folder labels, and user-correctable app matches.
5. Wait until relevant APKs are installed and the preferred Home role is restored.
6. Normalize the reference to the target safe area and generate a desaturated, 20–30% opacity ghost image.
7. Temporarily apply it as the Home wallpaper. Android permits wallpaper updates, but launcher-controlled crop and presentation remain best-effort: https://developer.android.com/reference/android/app/WallpaperManager
8. Present page-by-page checklists for installed/missing apps, folders, dock entries, and widget placeholders.
9. Persist reconstruction progress and a recovery marker.
10. On Finish, Cancel, crash recovery, or the next launch, restore the real wallpaper and purge all guide images.

Android exposes one wallpaper surface while the launcher controls paging, offsets, and parallax. The ghost wallpaper is therefore strongest for the main page; secondary pages and folders should also have a full-screen reference inside Portage.

A permanent overlay is not the default: it needs special access, increases trust and touch-safety concerns, and remains launcher-dependent: https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)

## Sequential Implementation PRs

Each implementation PR is independently reviewed, CI-green, and mergeable before the next begins.

### PR 0 — Lifecycle and main-thread safety

- Move staging/session cleanup out of activity recreation.
- Move export, copy, and hashing to IO.
- Add rotation, fold/recreation, cancellation, and dispatcher tests.

**Gate:** rotating in Preparing, QR, Transferring, or Done cannot delete active resources; large staging work never runs on Main.

### PR 1 — Truthful partial outcomes

- Preserve per-item receipts throughout a transfer.
- Replace false rollback copy.
- Add stable reason/remediation codes.

**Gate:** every terminal screen accounts for successfully applied, skipped, failed, and unattempted work without claiming rollback.

### PR 2 — Idempotent provider retries

- Add persistent bounded replay protection for side-effecting providers.
- Make MMS apply failure-atomic.
- Add interrupted-retry tests.

**Gate:** replay after a lost acknowledgement creates no duplicate or orphan SMS, MMS, call-log, or calendar record.

### PR 3 — Reliable backup relay

- Correct destination visibility and import/share handoff.
- Enforce exact-length and EOF framing.
- Track the restore action in the completion model.

**Gate:** a target or user-visible file recipient can open the exact transferred bytes; truncation is rejected.

### PR 4 — Durable "Finish your move"

- Persist the migration/action ledger.
- Record installer and other follow-up outcomes.
- Add cancellable session ownership and epoch guards.

**Gate:** process death, Settings round-trips, installer cancellation, and receiver reset preserve or safely terminate all remaining work.

### PR 5 — Transport resilience

- Try all advertised network addresses.
- Reject duplicate item begins and invalid acknowledgements.
- Implement completed-item checkpoints, followed by safe byte resume if justified.
- Stream large payload parsers.

**Gate:** interrupted transfers resume at the strongest safely verified boundary; protocol duplicates cannot cause repeated apply; near-limit inputs remain within the memory budget.

### PR 6 — Unified, preflight-driven application

- Execute ADR-007's one-app migration.
- Add the exhaustive item/flavor capability registry.
- Add category-first, just-in-time permission and setup preflight.
- Add byte-based progress.

**Gate:** one artifact supports Send/Receive without exposing unavailable flavor capabilities or weakening the sender privilege boundary.

### PR 7 — Launcher restore hardware spike

- Test compatible Launcher3/Seedvault restoration on two GrapheneOS devices.
- Record supported cases, failure modes, and readback evidence.

**Gate:** proceed to product integration only for cases verified after reboot; otherwise document immediate fallback to Home Map.

### PR 8 — Home Map

- Add typed, app-private capture/transfer.
- Add geometry calibration and ghost wallpaper.
- Add page/folder/widget checklists and crash-safe wallpaper restoration.

**Gate:** a user can reconstruct a representative multi-page layout, recover the real wallpaper after every exit path, and leave no guide images behind.

### PR 9 — Release confidence

- Add model goldens, all-kind loopback, Compose lifecycle, constrained-heap, static-analysis, and hardware gates.
- Resolve stale runbooks and coroutine opt-in warnings.

**Gate:** release evidence covers all supported item kinds and both product flavors on automated and required hardware paths.

## Risks and Controls

- **Persistent receipts contain sensitive metadata:** store only identifiers, counts, state, and controlled URIs; encrypt or keep app-private; expire after completion.
- **Resume weakens integrity if offsets are trusted:** bind every checkpoint to immutable item metadata and verify the final whole-item hash.
- **Launcher screenshots expose personal information:** instruct users to hide sensitive widgets/notifications, encrypt in transit, store privately, and purge automatically.
- **One-app unification can erode privilege separation:** retain compile-time flavor boundaries, provider seams, and manifest/CI forbidden-permission gates.
- **Launcher backup behavior is implementation-specific:** require hardware readback and always provide Home Map fallback.
- **Calendar mapping can cause cloud sync surprises:** default to local storage and require an explicit choice before mapping to a synced calendar.

## Verification Baseline

The audit's clean configured test run produced:

- 183 Gradle tasks executed with `--rerun-tasks`;
- 1,188 tests;
- 0 failures and 0 errors;
- `core-model:test` reported `NO-SOURCE`;
- many missing `ExperimentalCoroutinesApi` opt-in warnings; and
- no Android lint, detekt, or ktlint gate in the current CI test job.

This baseline does not replace two-phone hardware acceptance. Full hardware sign-off remains required for provider side effects, launcher/default-role persistence, installer behavior, process death, and end-to-end reconstruction.

