/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.ItemResult
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProviderRegistry
import com.ventouxlabs.portage.providers.apk.ApkContainerValidation
import com.ventouxlabs.portage.providers.apk.RuntimePermissionGranter
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.providers.roles.RoleRestoreCandidate
import com.ventouxlabs.portage.providers.roles.RestorableRole
import com.ventouxlabs.portage.providers.roles.RoleRestorer
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.relay.AppBackupRelayApplyProvider
import com.ventouxlabs.portage.providers.relay.RelayRestorePrompt
import com.ventouxlabs.portage.providers.sound.SoundFileHeader
import com.ventouxlabs.portage.providers.userfile.UserFileHeader
import com.ventouxlabs.portage.recv.checklist.ReceiverChecklist
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt
import com.ventouxlabs.portage.recv.sms.SmsRoleCoordinator
import com.ventouxlabs.portage.recv.sms.SmsRoleStrand
import com.ventouxlabs.portage.recv.transfer.ItemStreamReceiver
import com.ventouxlabs.portage.transport.DATA_PHASE_TIMEOUT_MS
import com.ventouxlabs.portage.transport.NoiseSecureChannelFactory
import com.ventouxlabs.portage.transport.PairingCodec
import com.ventouxlabs.portage.transport.PairingCodecImpl
import com.ventouxlabs.portage.transport.SecureChannel
import com.ventouxlabs.portage.transport.withDataPhaseDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Drives the receiver flow: scan → pair (real [SecureChannel.Factory.connectAsReceiver]) →
 * receive manifest → checklist → confirm → live item stream ([ItemStreamReceiver]: stage,
 * verify sha256, ack) → per-item apply via [ApplyProviderRegistry] → real done counts from
 * the final results. A dropped connection mid-transfer fails visibly, never hangs.
 */
class ReceiverViewModel(
    private val pairingCodec: PairingCodec = PairingCodecImpl(),
    private val channelFactory: SecureChannel.Factory = NoiseSecureChannelFactory(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    // Aggregate wall-clock ceiling on the WHOLE authenticated data phase (the live item stream
    // after the user taps confirm); see [DATA_PHASE_TIMEOUT_MS] for the rationale and the
    // effective-ceiling caveat. Injectable so tests drive it on virtual time.
    private val dataPhaseTimeoutMs: Long = DATA_PHASE_TIMEOUT_MS,
    private val appVersion: String = "0.1.0",
    private val osFingerprint: String = android.os.Build.FINGERPRINT,
    // Deliberately NO default: staged payloads are plaintext PII, so the staging location
    // must be wired explicitly (production: app-private cacheDir via the factory).
    private val stagingDir: File,
    // Inert by default: without a real coordinator (or its manifest role components) SMS
    // can never be granted, so the apply path always self-skips.
    private val smsRoleCoordinator: SmsRoleCoordinator = SmsRoleCoordinator.Inert,
    // The factory is handed the providers' Done-screen sinks (see [DoneSinks]) — reinstall actions,
    // re-pair entries, relay prompts, APK install prompts, and the permission summaries. Each surfaces
    // on the Done screen; none performs a silent side effect — they produce user-driven checklists.
    applyRegistryFactory: ApplyRegistryFactory =
        ApplyRegistryFactory { _ -> ApplyProviderRegistry(emptyList()) },
    // Called on [reset] (return-home) to abandon any sealed-but-uncommitted PackageInstaller
    // sessions from this run. Injected from `:app-recv` so the ViewModel stays Android-free (fix 5).
    // No-op default; production wires PackageInstallerApkInstaller.abandonUncommittedSessions.
    private val abandonSessions: () -> Unit = {},
    // POST-Done opt-in dangerous-permission grant (ADR-006 D5, Phase 5d-2). DISTINCT from the apply
    // provider's auto-grant seam: that one runs INSIDE the silent-install apply, belt-filtered to
    // DEFAULT_SAFE, and has already disconnected by the time Done shows. THIS one is user-driven — it
    // fires only when the user expands "Advanced permissions" on the Done screen and taps grant, and only
    // for perms portage itself offered as opt-in (the belt in [grantOptIn]). Best-effort/non-fatal. Default
    // NoOp; production wires AdbRuntimePermissionGranter(adbBridge) — the same process-scoped bridge, which
    // is idle once the transfer is done.
    private val optInPermissionGranter: RuntimePermissionGranter = RuntimePermissionGranter.NoOp,
    // Default-app role restore (#122). Defaults to Unavailable so a caller that forgets to wire it
    // degrades to "cannot restore", never to an unguarded restore.
    private val roleRestorer: RoleRestorer = RoleRestorer.Unavailable,
    // Flavor-level: play ships no bridge, so it must not OFFER a default it can never set. Defaults
    // to false so an unwired caller shows nothing rather than a dead button.
    private val canRestoreRoles: Boolean = false,
    // The packages present RIGHT NOW (#122). Called fresh every time role candidates are surfaced —
    // never cached — because Tier-0 APK installs are user-confirmed and land AFTER apply returns
    // (see [DefaultRolesApplyProvider]); a snapshot taken at any single moment would miss the apps
    // this very transfer is installing. Defaults to "nothing installed", which offers no role at
    // all: an unwired caller shows an empty section rather than an unverifiable one.
    private val installedPackages: () -> Set<String> = { emptySet() },
    // Keeps the process at foreground importance + the CPU awake for the item stream so a screen-off
    // can't reset the streaming socket mid-frame (#85). NoOp default (tests/previews); production
    // wires a foreground-service-backed implementation. Driven start-before-phase / stop-in-finally.
    private val transferKeepAlive: TransferKeepAlive = TransferKeepAlive.NoOp,
    // The data phase runs here, NOT on viewModelScope's main dispatcher. Socket reads, sha256
    // staging to disk, and every provider apply are blocking work; on Main they freeze the UI and,
    // worse, stall the ack loop until the sender gives up mid-stream (#158 — an 859-event calendar
    // killed a transfer on real hardware after ~9 s, well inside any deadline). Injected rather
    // than hardcoded so tests keep virtual-time control via StandardTestDispatcher.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    /** Reinstall checklist produced by the app-inventory apply (one user tap per app). */
    private val _installActions = MutableStateFlow<List<InstallAction>>(emptyList())
    val installActions: StateFlow<List<InstallAction>> = _installActions.asStateFlow()

    /** Re-pair checklist produced by the bonded-Bluetooth apply (PRP-07 Phase 1: display only). */
    private val _repairEntries = MutableStateFlow<List<RePairEntry>>(emptyList())
    val repairEntries: StateFlow<List<RePairEntry>> = _repairEntries.asStateFlow()

    /**
     * Guided re-link prompts produced by the app-backup relay apply (PRP-06): "open this in <app>
     * and enter your passphrase". portage hands the OPAQUE file to the user / target app and never
     * imports it or holds the passphrase. Empty unless an APP_BACKUP_RELAY item was applied.
     */
    private val _relayPrompts = MutableStateFlow<List<RelayRestorePrompt>>(emptyList())
    val relayPrompts: StateFlow<List<RelayRestorePrompt>> = _relayPrompts.asStateFlow()

    /**
     * Tier-0 APK install prompts produced by the APK apply fallback (ADR-006 D3/D6): one row per app
     * whose split set was reconciled, sealed into a `PackageInstaller` session, and awaits a one-tap
     * system install-confirm on the Done screen. Empty unless an APK item routed to the Tier-0 path.
     */
    private val _apkInstallPrompts = MutableStateFlow<List<ApkInstallPrompt>>(emptyList())
    val apkInstallPrompts: StateFlow<List<ApkInstallPrompt>> = _apkInstallPrompts.asStateFlow()

    /**
     * Per-app runtime permissions re-granted by the APK apply's parity step (ADR-006 D5, silent install
     * only). Surfaced read-only on the Done screen ("restored Network, Sensors"); empty unless a silent
     * APK install re-granted at least one default-safe permission.
     */
    private val _restoredPermissions = MutableStateFlow<List<RestoredPermissions>>(emptyList())
    val restoredPermissions: StateFlow<List<RestoredPermissions>> = _restoredPermissions.asStateFlow()

    /**
     * Per-app OPT-IN dangerous permissions offered for an explicit confirm (ADR-006 D5, Phase 5d, silent
     * install only). Surfaced on the Done screen; NOTHING here is granted until the user acts (the grant
     * UI is the next Phase-5d slice). Empty unless a silent APK install carried opt-in-eligible perms.
     */
    private val _optInPermissions = MutableStateFlow<List<OptInPermissions>>(emptyList())
    val optInPermissions: StateFlow<List<OptInPermissions>> = _optInPermissions.asStateFlow()

    /**
     * Default-app roles (#122) the sender CARRIED, already through every security filter but NOT
     * filtered to what is installed here — that is [restorableCandidates]'s job, evaluated live.
     *
     * NOTHING is applied to produce this list. The shell path shows no system confirm dialog, so
     * roles are offered, never auto-restored.
     */
    private val _carriedRoles = MutableStateFlow<List<RoleRestoreCandidate>>(emptyList())

    /**
     * The CARRIED roles — deliberately NOT the offerable ones.
     *
     * Named for what it holds. The offerable set is [restorableCandidates], which filters this
     * against a live installed-set read and is reachable only through [ReceiverState.Done]. Do not
     * bind this flow to UI: it includes roles whose target app is not installed here, and surfacing
     * those is exactly what the live filter exists to prevent. An earlier revision called this
     * `roleCandidates` and documented it as the filtered, recomputed set — it was neither, and the
     * name invited precisely that mistake.
     */
    val carriedRoles: StateFlow<List<RoleRestoreCandidate>> = _carriedRoles.asStateFlow()

    /** Roles the user confirmed and the platform accepted — drives the in-place row update. */
    private val _restoredRoles = MutableStateFlow<List<RestorableRole>>(emptyList())
    val restoredRoles: StateFlow<List<RestorableRole>> = _restoredRoles.asStateFlow()

    /**
     * In-flight / last-failure status per tapped role (#122). Absent ⇒ untouched.
     *
     * Also the duplicate-tap guard: [restoreRole] marks IN_FLIGHT synchronously before launching,
     * so a second tap is refused rather than enqueueing another ≤90 s bridge round-trip behind the
     * first on the shared [optInGrantMutex].
     */
    private val _roleAttempts = MutableStateFlow<Map<RestorableRole, ReceiverState.RoleAttempt>>(emptyMap())
    val roleAttempts: StateFlow<Map<RestorableRole, ReceiverState.RoleAttempt>> = _roleAttempts.asStateFlow()

    /**
     * Non-null ⇒ portage is still the default SMS app from an interrupted handoff (process death,
     * dismissed restore prompt). Drives an in-app one-tap restore — the persistent backstop to the
     * `finally` relinquish, which cannot survive a kill (DEVILS_ADVOCATE.md Q4 §3).
     */
    private val _smsRoleStrand = MutableStateFlow<SmsRoleStrand?>(null)
    val smsRoleStrand: StateFlow<SmsRoleStrand?> = _smsRoleStrand.asStateFlow()

    private val applyRegistry: ApplyProviderRegistry =
        applyRegistryFactory.create(
            DoneSinks(
                // MERGE, not replace: both the App-Inventory apply (the full reinstall list) and the APK
                // apply's "incompatible on this device — get it from the store" fallback feed this sink, and
                // items apply sequentially in arbitrary order. Dedup by package so a reinstall row and an
                // incompatible-APK row for the same app never key the LazyColumn twice.
                onInstallActions = { actions ->
                    _installActions.value = (_installActions.value + actions).distinctBy { it.packageName }
                },
                onRepairEntries = { entries -> _repairEntries.value = entries },
                // Relay items arrive one per apply call; append each prompt so multiple relayed backups
                // (e.g. Signal AND Aegis in one session) all surface on the Done screen.
                onRelayPrompt = { prompt -> _relayPrompts.value = _relayPrompts.value + prompt },
                // APK items arrive one per apply call; append each Tier-0 install prompt so multiple apps
                // in one session all surface their one-tap install row on the Done screen.
                onApkInstallPrompt = { prompt -> _apkInstallPrompts.value = _apkInstallPrompts.value + prompt },
                // Parity re-grants arrive one per silently-installed app; append each so every app's
                // restored permissions surface on the Done screen (dedup by package — one row per app).
                onPermissionsRestored = { packageName, permissions ->
                    _restoredPermissions.value =
                        (_restoredPermissions.value + RestoredPermissions(packageName, permissions))
                            .distinctBy { it.packageName }
                },
                // Opt-in dangerous perms arrive one per silently-installed app; append (dedup by package).
                // DATA ONLY — surfaced for an explicit confirm on Done; nothing is granted here (Phase 5d UI).
                onOptInPermissions = { packageName, permissions ->
                    _optInPermissions.value =
                        (_optInPermissions.value + OptInPermissions(packageName, permissions))
                            .distinctBy { it.packageName }
                },
                // Default-app role candidates (#122). DATA ONLY — dedup by role so a hostile or
                // duplicated snapshot cannot produce two rows for one role.
                onRoleCandidates = { candidates ->
                    // Drop them entirely on a build that cannot restore (play). Surfacing a "SET"
                    // affordance that can never succeed would be a dead button, and silently
                    // failing is precisely the dishonesty this feature exists to avoid.
                    if (canRestoreRoles) {
                        _carriedRoles.update { (it + candidates).distinctBy { c -> c.role } }
                    }
                },
            ),
        )

    /**
     * Reference to the relay apply provider, extracted from the registry after construction.
     * Used by [applyStaged] to set the current item id before each apply call so that two relay
     * items for the same package get distinct [RelayRestorePrompt] row keys and distinct filenames.
     * Null when no relay provider is registered (e.g. in tests that don't exercise relay).
     */
    private val relayApplyProvider: AppBackupRelayApplyProvider? =
        applyRegistry.forKind(ItemKind.APP_BACKUP_RELAY) as? AppBackupRelayApplyProvider

    private var channel: SecureChannel? = null

    /**
     * Serializes the user-driven opt-in grants on the Done screen. [AdbRuntimePermissionGranter] documents
     * that it assumes EXCLUSIVE use of the process-scoped bridge for the duration of a call (it
     * connect→grants→disconnects in `finally`); two overlapping Done-screen taps (e.g. "Grant all" on app A
     * then app B) would otherwise race that single bridge, one's teardown aborting the other's in-flight
     * session. The lock makes the grants strictly sequential so each gets exclusive use. (The install-time
     * granter is temporally disjoint — it runs in the data phase, before Done — so it needs no coordination
     * with this.)
     */
    private val optInGrantMutex = Mutex()

    init {
        refreshSmsRoleStrand()
    }

    fun startScanning() {
        if (_state.value is ReceiverState.Idle || _state.value is ReceiverState.Failed) {
            // Clear the role state on the way IN as well as on the way out (#122). reset() covers
            // the Done → Home exit, but Failed → Scanning re-enters without passing through it, so
            // a failed transfer's carried roles would survive into the next one — where the sink's
            // distinctBy { role } keeps the FIRST entry and the stale row would SHADOW the new
            // transfer's legitimate one for that role.
            clearRoleState()
            _state.value = ReceiverState.Scanning
        }
    }

    /** Drop every Done-scoped role flow (#122). Both the entry and the exit path must call this. */
    private fun clearRoleState() {
        _carriedRoles.value = emptyList()
        _restoredRoles.value = emptyList()
        _roleAttempts.value = emptyMap()
    }

    /** Called by the QR scanner with a raw `portage1:` URI. */
    fun onQrScanned(qr: String) {
        if (_state.value !is ReceiverState.Scanning) return
        val payload = pairingCodec.decode(qr, nowEpochSeconds()).getOrElse {
            _state.value = ReceiverState.Failed(it.message ?: "Invalid pairing QR")
            return
        }
        _state.value = ReceiverState.Pairing
        viewModelScope.launch {
            try {
                val ch = channelFactory.connectAsReceiver(payload).also { channel = it }
                ch.send(ProtocolMessage.Hello(appVersion, osFingerprint))
                when (val msg = ch.receive()) {
                    is ProtocolMessage.Manifest ->
                        _state.value = ReceiverState.Reviewing(
                            senderName = msg.manifest.senderName,
                            groups = ReceiverChecklist.build(msg.manifest),
                            absentKinds = ReceiverChecklist.absentKinds(msg.manifest),
                        )
                    else -> fail("Sender did not send a manifest")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(t.message ?: "Pairing failed")
            }
        }
    }

    fun onToggle(itemId: Int) {
        val current = _state.value as? ReceiverState.Reviewing ?: return
        _state.value = current.copy(groups = ReceiverChecklist.toggle(current.groups, itemId))
    }

    fun onConfirm() {
        val current = _state.value as? ReceiverState.Reviewing ?: return
        val selected = ReceiverChecklist.selectedMetas(current.groups)
        if (selected.isEmpty()) return
        // Retry ledgers prevent duplicate rows only within this transfer. A later intentional
        // transfer must be able to restore records the user deleted in the meantime.
        applyRegistry.beginTransfer()
        _state.value = ReceiverState.Transferring(
            items = selected.map { ItemProgress(it.itemId, it.displayName, totalBytes = it.size) },
        )
        val needsSmsRole = selected.any { it.kind == ItemKind.SMS || it.kind == ItemKind.MMS }
        viewModelScope.launch {
            try {
                val ch = channel ?: error("no channel")
                // Hold the process alive + CPU awake for the whole item stream (#85): released in the
                // finally below on EVERY exit (done / fail / timeout / reset). Idempotent.
                transferKeepAlive.start()
                // Cap the WHOLE data phase, not just each read. withDataPhaseDeadline returns null
                // on ITS OWN deadline ONLY, so a stalled peer becomes a visible Failed rather than
                // a re-thrown cancellation. null strictly means "this budget elapsed": the block
                // always returns a non-null List, and a concurrent reset() here CLOSES THE CHANNEL
                // (the receiver has no transferJob to cancel) — which surfaces as a transport error
                // in catch(t), never as null (the helper rethrows pre-deadline errors untouched).
                // Its watchdog closes the channel at the deadline (#56), unblocking even a read
                // parked in native code, so the cap fires at ~dataPhaseTimeoutMs instead of the old
                // budget-plus-one-soTimeout slack; the block's finally clauses (staging sweep /
                // SMS-role relinquish) still run on the unwind before we fail. See
                // [DATA_PHASE_TIMEOUT_MS].
                val results = withDataPhaseDeadline(ch, dataPhaseTimeoutMs) {
                    withSmsRoleIfNeeded(needsSmsRole) {
                        ch.send(ProtocolMessage.Select(selected.map { it.itemId }))
                        ItemStreamReceiver(
                            stagingDir = stagingDir,
                            // Raise the per-item cap for the two large-payload kinds, each to its OWN
                            // documented ceiling: the APP_BACKUP_RELAY opaque blob (PRP-06 §5) and the
                            // APK container item (ADR-006 D4, 1 GiB via ApkContainerValidation). Every
                            // other kind keeps the 64 MiB Tier-0 default — the raise never leaks into
                            // the PII/Tier-0 item paths.
                            maxBytesByKind = mapOf(
                                ItemKind.APP_BACKUP_RELAY to MAX_RELAY_ITEM_BYTES,
                                ItemKind.APK to ApkContainerValidation.MAX_APK_ITEM_BYTES,
                                ItemKind.SOUND_FILE to SoundFileHeader.MAX_ITEM_BYTES,
                                ItemKind.USER_FILE to UserFileHeader.MAX_ITEM_BYTES,
                            ),
                            userFileMaxTotalBytes = UserFileHeader.MAX_TOTAL_BYTES,
                        ).let { receiver ->
                            // Off Main for the whole stream (#158). Deliberately INSIDE
                            // withSmsRoleIfNeeded, not outside it: the SMS role handoff fires an
                            // interactive system dialog and must keep running on the caller's
                            // (main) context — that path is hardware-verified and must not regress.
                            // Applies stay strictly sequential; withContext suspends until the
                            // stream completes, so applyStaged's setNextItemId invariant holds.
                            withContext(ioDispatcher) {
                                receiver.run(
                                    channel = ch,
                                    expected = selected.associateBy { it.itemId },
                                    apply = ::applyStaged,
                                    onEvent = ::onReceiveEvent,
                                )
                            }
                        }
                    }
                }
                if (results == null) {
                    // null ⇒ the aggregate budget elapsed (a reset() closes the channel → transport
                    // error in catch(t), not null). Fail closed: fail() closes the channel; the block's
                    // finally clauses already swept staging / relinquished the SMS role on the unwind.
                    fail("Transfer timed out — it took too long to finish")
                    return@launch
                }
                ensureActive() // a reset() mid-run must not be overwritten by Done
                // Built while the state is still Transferring — doneStateFrom reads it for the
                // per-item display names.
                _state.value = doneStateFrom(results)
                channel?.close()
                channel = null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // reset() closes the channel underneath this coroutine (the receiver stores no
                // transferJob, so it cannot cancel the coroutine — only the sender does that).
                // ensureActive() guards only a true external cancel; a reset()-induced IO error
                // falls through to fail(), which may overwrite the Idle reset() just set. This
                // is a pre-existing, accepted LOW: the window is narrow and it fails closed.
                ensureActive()
                fail(t.message ?: "Transfer failed")
            } finally {
                // Always release the keep-alive — done, fail, timeout, or reset() close
                // unwinding through here. Idempotent.
                transferKeepAlive.stop()
            }
        }
    }

    /**
     * Assemble the Done snapshot from the run's results plus whatever the apply providers pushed
     * into the [DoneSinks] flows.
     *
     * Done — not those flows — is what the screen renders and what [restoreRole] and [grantOptIn]
     * validate a user's tap against, so it is built in exactly one place. MUST be called while the
     * state is still [ReceiverState.Transferring]: the per-item display names come from that state,
     * and a failed item read after the transition would fall back to "#id".
     */
    private fun doneStateFrom(results: List<ItemResult>): ReceiverState.Done {
        val moved = results.count { it.status == ItemStatus.OK }
        val nameById = (_state.value as? ReceiverState.Transferring)
            ?.items?.associate { it.itemId to it.displayName }
            ?: emptyMap()
        return ReceiverState.Done(
            moved = moved,
            skipped = results.size - moved,
            installActions = _installActions.value,
            repairEntries = _repairEntries.value,
            relayPrompts = _relayPrompts.value,
            apkInstallPrompts = _apkInstallPrompts.value,
            restoredPermissions = _restoredPermissions.value,
            optInPermissions = _optInPermissions.value,
            roleCandidates = restorableCandidates(),
            restoredRoles = _restoredRoles.value,
            roleAttempts = _roleAttempts.value,
            failedItems = results
                .filter { it.status != ItemStatus.OK }
                .map { r -> FailedItem(r.itemId, nameById[r.itemId] ?: "#${r.itemId}", r.status, r.detail) },
        )
    }

    /**
     * Wrap [transfer] in the default-SMS-app handoff when an SMS item is selected: record the
     * prior holder, acquire the role (interactive system dialog), run the transfer with the
     * role held so SmsApplyProvider can write, then ALWAYS relinquish toward the prior holder
     * in a finally (DEVILS_ADVOCATE Q4 — teardown is required, never optional). A declined
     * role just runs the transfer; SmsApplyProvider self-skips without the role and there is
     * nothing to give back.
     */
    private suspend fun withSmsRoleIfNeeded(
        needsSmsRole: Boolean,
        transfer: suspend () -> List<ItemResult>,
    ): List<ItemResult> {
        if (!needsSmsRole) return transfer()
        val prior = smsRoleCoordinator.priorDefaultPackage()
        if (!smsRoleCoordinator.acquireRole()) return transfer()
        return try {
            transfer()
        } finally {
            smsRoleCoordinator.relinquishTo(prior)
        }
    }

    /** Map stream events onto the per-item progress rows. */
    internal fun onReceiveEvent(event: ItemStreamReceiver.Event) {
        when (event) {
            is ItemStreamReceiver.Event.ItemStarted ->
                updateItem(event.itemId) { it.copy(phase = ItemPhase.RECEIVING) }

            is ItemStreamReceiver.Event.ItemProgressed ->
                updateItem(event.itemId) {
                    it.copy(bytesReceived = event.bytesReceived, totalBytes = event.totalBytes)
                }

            is ItemStreamReceiver.Event.ItemApplying -> Unit // applyStaged flips APPLYING itself

            is ItemStreamReceiver.Event.ItemFinished ->
                updateItem(event.result.itemId) {
                    val phase =
                        if (event.result.status == ItemStatus.OK) ItemPhase.DONE else ItemPhase.FAILED
                    it.copy(phase = phase, detail = event.result.detail ?: it.detail)
                }
        }
    }

    /**
     * Apply one staged, hash-verified item through the compiled registry, tracking the
     * per-item phase. Never throws for a bad payload or handler failure — every outcome
     * maps to an [ItemPhase] + [ApplyOutcome] (a failed item never aborts the batch,
     * PROTOCOL.md §5).
     */
    internal suspend fun applyStaged(meta: ItemMeta, source: InputStream): ApplyOutcome {
        updateItem(meta.itemId) { it.copy(phase = ItemPhase.APPLYING) }
        // Thread the item id into the relay provider so each relay item gets a distinct prompt key
        // and a distinct handoff filename — prevents same-app overwrite and Compose duplicate-key crash.
        // INVARIANT: setNextItemId + the provider's mutable nextItemId rely on SEQUENTIAL apply —
        // ItemStreamReceiver applies items one at a time on a single coroutine, so the set-then-apply
        // pair is atomic per item. Parallelizing applies would race nextItemId and corrupt the
        // prompt/file identity (a backup could be tagged with another item's id); keep applies serial.
        if (meta.kind == ItemKind.APP_BACKUP_RELAY) relayApplyProvider?.setNextItemId(meta.itemId)
        val outcome = try {
            applyRegistry.apply(meta.kind, source)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            ApplyOutcome(ItemStatus.WRITE_ERROR, t.message ?: "apply failed")
        }
        val phase = if (outcome.status == ItemStatus.OK) ItemPhase.DONE else ItemPhase.FAILED
        updateItem(meta.itemId) { it.copy(phase = phase, detail = outcome.detail) }
        return outcome
    }

    private fun updateItem(itemId: Int, transform: (ItemProgress) -> ItemProgress) {
        val current = _state.value as? ReceiverState.Transferring ?: return
        _state.value = current.copy(
            items = current.items.map { if (it.itemId == itemId) transform(it) else it },
        )
    }

    /**
     * User opted in to re-grant dangerous permissions for one carried app on the Done screen (ADR-006 D5,
     * Phase 5d-2) — the FIRST user-driven `pm grant` of a dangerous permission. Best-effort and non-fatal.
     *
     * THE BELT (security-critical): only perms portage itself OFFERED as opt-in for [packageName] — i.e.
     * present in the current [ReceiverState.Done.optInPermissions] entry for that package — are ever passed
     * to the granter. That offered set is planner-derived (`captured ∩ targetDeclared ∩ OPT_IN`, which
     * already excludes the NEVER/signature-system list and the auto-granted DEFAULT_SAFE set), so this
     * method can only ever NARROW it, never widen it. A request naming an unknown package, an un-offered
     * permission, or arriving in any non-Done state grants nothing. The granter re-validates pkg/perm at
     * the `ShellArgs` wire boundary as the final belt.
     *
     * On success the confirmed perms move optIn → restored in the surfaced state so the row updates in
     * place; a failed/empty grant (e.g. no live bridge) simply leaves them offered.
     */
    fun grantOptIn(packageName: String, permissions: List<String>) {
        val offered = (_state.value as? ReceiverState.Done)
            ?.optInPermissions
            ?.firstOrNull { it.packageName == packageName }
            ?.permissions
            ?: return
        // BELT: drop anything not in the offered opt-in set for this package, preserving request order.
        val requested = permissions.filter { it in offered }
        if (requested.isEmpty()) return
        viewModelScope.launch {
            // Hold the lock across the bridge round-trip so overlapping Done-screen taps never share the
            // single process-scoped bridge (see [optInGrantMutex]).
            val granted = optInGrantMutex.withLock { optInPermissionGranter.grant(packageName, requested) }
            // Only count what we asked for AND the granter confirmed (belt against a granter returning extra).
            val moved = requested.filter { it in granted }
            if (moved.isEmpty()) return@launch // best-effort: nothing changed, leave it offered
            moveOptInToRestored(packageName, moved)
        }
    }

    /**
     * Restore ONE captured default-app role, on an explicit user tap (#122).
     *
     * This is the only code path that changes a role, and it is deliberately narrow:
     *  - The (role, package) pair MUST already be an OFFERED candidate on the LIVE Done state. A
     *    caller cannot restore a role the transfer never carried, or point a role at a package the
     *    apply provider did not validate. Anything else is dropped silently.
     *  - The package must STILL be installed at tap time, re-read here rather than trusted from the
     *    surfaced list. The offered set is built from an installed-set read that may be seconds or
     *    minutes old (the user can uninstall, or cancel a pending install, while Done is up), and
     *    pointing a role at a package that has since gone would ask the platform to hand a system
     *    capability to something absent. This is the last check before the privileged call.
     *  - The bridge round-trip is held under the same [optInGrantMutex] as the opt-in grants, so
     *    overlapping Done-screen taps never race on the single process-scoped bridge.
     *  - Only [RoleRestorer.Outcome.RESTORED] updates the UI. REJECTED (the app does not qualify),
     *    UNAVAILABLE (no live bridge) and a THROWING restorer all leave the row offered rather than
     *    claiming success — portage must not report a default it did not actually set.
     *
     * There is no "restore all" convenience here, on purpose: each role is a separate consent.
     */
    fun restoreRole(role: RestorableRole, packageName: String) {
        val offered = (_state.value as? ReceiverState.Done)
            ?.roleCandidates
            ?.firstOrNull { it.role == role && it.packageName == packageName }
            ?: return
        if (offered.packageName !in liveInstalledPackages()) return
        // Duplicate-tap guard, set SYNCHRONOUSLY so two rapid taps cannot both get past it. The
        // belt above runs before the launch, so without this every tap enqueued its own coroutine
        // and they serialized on optInGrantMutex — three taps with the bridge unreachable meant
        // minutes of queued work and no visible change.
        if (_roleAttempts.value[role] == ReceiverState.RoleAttempt.IN_FLIGHT) return
        publishRoleAttempt(role, ReceiverState.RoleAttempt.IN_FLIGHT)
        viewModelScope.launch {
            // A restorer that THROWS must not reach viewModelScope's handler — an uncaught throw
            // there takes the process down, turning "the bridge is unhappy" into a crash on the
            // Done screen with the transfer's results still on it. Same shape as [applyStaged].
            val outcome = try {
                optInGrantMutex.withLock { roleRestorer.restore(offered.role, offered.packageName) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                RoleRestorer.Outcome.UNAVAILABLE
            }
            if (outcome != RoleRestorer.Outcome.RESTORED) {
                // Say so. A failed restore used to change NOTHING on screen, which made the two
                // failures indistinguishable from each other AND from a tap that did nothing at
                // all. The distinction is actionable: REJECTED means this app cannot hold the role,
                // UNAVAILABLE means setup is not ready and retrying later may work.
                publishRoleAttempt(
                    offered.role,
                    when (outcome) {
                        RoleRestorer.Outcome.REJECTED -> ReceiverState.RoleAttempt.REJECTED
                        else -> ReceiverState.RoleAttempt.UNAVAILABLE
                    },
                )
                return@launch
            }
            // Re-read the LIVE Done snapshot AFTER the suspend call: the user may have left the
            // screen mid-restore, and a stale write would resurrect a dead state (same discipline
            // as moveOptInToRestored).
            val done = _state.value as? ReceiverState.Done ?: return@launch
            // update {} , not value= : two roles restored concurrently each read-modify-write these
            // flows, and a plain assignment loses whichever write lands first.
            _restoredRoles.update { (it + offered.role).distinct() }
            _carriedRoles.update { carried -> carried.filterNot { it.role == offered.role } }
            // Success needs no status: the row moves to restored and leaves the offered list.
            _roleAttempts.update { it - offered.role }
            _state.value = done.copy(
                roleCandidates = restorableCandidates(),
                restoredRoles = _restoredRoles.value,
                roleAttempts = _roleAttempts.value,
            )
        }
    }

    /** Record a role's attempt status and re-emit Done so the row reflects it immediately. */
    private fun publishRoleAttempt(role: RestorableRole, attempt: ReceiverState.RoleAttempt) {
        _roleAttempts.update { it + (role to attempt) }
        val done = _state.value as? ReceiverState.Done ?: return
        _state.value = done.copy(roleAttempts = _roleAttempts.value)
    }

    /**
     * Re-surface the role offers against a FRESH installed-set read (#122).
     *
     * Called on every resume, which is what makes the headline case work: Tier-0 APK installs are
     * user-confirmed system dialogs, so the apps this transfer carried usually arrive AFTER the Done
     * screen first rendered. Without this, a role whose app was still installing would sit
     * permanently unrestorable behind a stale filter. Also drops a row whose app was uninstalled
     * while Done was up. No-op outside Done; touches nothing but the offered list.
     */
    fun refreshRoleCandidates() {
        val done = _state.value as? ReceiverState.Done ?: return
        val fresh = restorableCandidates()
        // Drop stale statuses for roles no longer offered (the app was uninstalled), so a failure
        // message cannot outlive the row it described.
        val attempts = _roleAttempts.value.filterKeys { role -> fresh.any { it.role == role } }
        _roleAttempts.value = attempts
        if (fresh != done.roleCandidates || attempts != done.roleAttempts) {
            _state.value = done.copy(roleCandidates = fresh, roleAttempts = attempts)
        }
    }

    /** Carried roles minus those whose target app is not installed here, read live. */
    private fun restorableCandidates(): List<RoleRestoreCandidate> {
        val installed = liveInstalledPackages()
        return _carriedRoles.value.filter { it.packageName in installed }
    }

    /** Never throws: a failed enumeration offers no roles rather than taking the screen down. */
    private fun liveInstalledPackages(): Set<String> =
        runCatching { installedPackages() }.getOrDefault(emptySet())

    /**
     * Reflect a confirmed opt-in grant: drop [granted] from [packageName]'s opt-in entry (removing the
     * entry when emptied) and fold them into its restored entry, then re-emit the Done state so the row
     * updates in place. Computed from — and gated on — the LIVE Done snapshot (read AFTER the suspend
     * grant), which is the screen's source of truth. The Done guard is FIRST and deliberate: if the user
     * left the Done screen during the grant (a [reset] returning Home cancels nothing on viewModelScope),
     * this is a no-op — it must NOT repopulate the reset-cleared flows with a stale entry that would leak
     * into the next transfer. Concurrent per-app grants compose because each re-reads the live snapshot.
     */
    private fun moveOptInToRestored(packageName: String, granted: List<String>) {
        val done = _state.value as? ReceiverState.Done ?: return
        val grantedSet = granted.toSet()
        val newOptIn = done.optInPermissions.mapNotNull { entry ->
            if (entry.packageName != packageName) {
                entry
            } else {
                entry.copy(permissions = entry.permissions.filterNot { it in grantedSet })
                    .takeIf { it.permissions.isNotEmpty() }
            }
        }
        val newRestored = if (done.restoredPermissions.any { it.packageName == packageName }) {
            done.restoredPermissions.map {
                if (it.packageName == packageName) it.copy(permissions = (it.permissions + granted).distinct()) else it
            }
        } else {
            done.restoredPermissions + RestoredPermissions(packageName, granted)
        }
        // Keep the standalone flows in lockstep with the Done snapshot (both are cleared together on reset).
        _optInPermissions.value = newOptIn
        _restoredPermissions.value = newRestored
        _state.value = done.copy(optInPermissions = newOptIn, restoredPermissions = newRestored)
    }

    fun reset() {
        channel?.close()
        channel = null
        // Abandon any sealed-but-uncommitted PackageInstaller sessions from this run before clearing
        // the prompt list — a user who never tapped install and hits Home must not leave APK bytes
        // lingering in uncommitted sessions (fix 5). Best-effort: abandonUncommittedSessions is
        // wrapped in runCatching inside the adapter so this can never throw.
        abandonSessions()
        _installActions.value = emptyList()
        _repairEntries.value = emptyList()
        _relayPrompts.value = emptyList()
        _apkInstallPrompts.value = emptyList()
        _restoredPermissions.value = emptyList()
        _optInPermissions.value = emptyList()
        // Default-app role flows (#122) MUST be cleared here like every other Done-scoped flow.
        // Leaving them is not cosmetic: the Done state is built from these values, and
        // [restoreRole]'s belt validates against that live Done — so a candidate retained from a
        // PREVIOUS transfer would still validate, and "a caller cannot restore a role the transfer
        // never carried" would be false across a reset. The append in the sink also uses
        // distinctBy { it.role }, which keeps the FIRST occurrence, so a retained stale candidate
        // would SHADOW the next transfer's legitimate one for that role.
        clearRoleState()
        _state.value = ReceiverState.Idle
        // Returning Home is a chance to clear (or surface) a leftover default-SMS strand.
        refreshSmsRoleStrand()
    }

    /**
     * Reconcile the in-app restore affordance with the real default-SMS state — called at startup,
     * when returning Home, and on every resume (so it clears the moment the role is handed back).
     * If nothing is stranded, also clears the persistent ledger marker.
     */
    fun refreshSmsRoleStrand() {
        val strand = smsRoleCoordinator.currentStrand()
        _smsRoleStrand.value = strand
        if (strand == null) smsRoleCoordinator.onRoleRestored()
    }

    /** User tapped "restore my texting app": re-fire the system change-default prompt. */
    fun restoreSmsRole() {
        val target = _smsRoleStrand.value?.priorPackage
        viewModelScope.launch {
            smsRoleCoordinator.relinquishTo(target)
            // The real clear happens on the next refreshSmsRoleStrand() once the role returns.
        }
    }

    /** Fail-closed terminal transition: close the live channel, then surface the reason. */
    private fun fail(reason: String) {
        channel?.close()
        channel = null
        _state.value = ReceiverState.Failed(reason)
    }

    override fun onCleared() {
        channel?.close()
        super.onCleared()
    }
}

/**
 * Raised per-item ceiling for the APP_BACKUP_RELAY kind ONLY (PRP-06 §5). Signal backups can run to
 * multiple GiB; 2 GiB is a documented, finite ceiling — large enough for real backups, small enough
 * to keep the staging write bounded. This is applied via [ItemStreamReceiver.maxBytesByKind] and
 * MUST NOT be the default cap: Tier-0/PII items keep the 64 MiB DEFAULT_MAX_ITEM_BYTES.
 */
private const val MAX_RELAY_ITEM_BYTES = 2L * 1024 * 1024 * 1024
