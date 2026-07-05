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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    // Called on reset to abandon sealed-but-uncommitted PackageInstaller sessions from this run.
    // No-op default; production wires PackageInstallerApkInstaller.abandonUncommittedSessions (fix 5).
    abandonSessions: () -> Unit = {},
    // POST-Done opt-in dangerous-permission grant (ADR-006 D5, Phase 5d-2). DISTINCT from the apply
    // provider's auto-grant seam: that one runs INSIDE the silent-install apply, belt-filtered to
    // DEFAULT_SAFE, and has already disconnected by the time Done shows. THIS one is user-driven — it
    // fires only when the user expands "Advanced permissions" on the Done screen and taps grant, and only
    // for perms portage itself offered as opt-in (the belt in [grantOptIn]). Best-effort/non-fatal. Default
    // NoOp; production wires AdbRuntimePermissionGranter(adbBridge) — the same process-scoped bridge, which
    // is idle once the transfer is done.
    private val optInPermissionGranter: RuntimePermissionGranter = RuntimePermissionGranter.NoOp,
    // Keeps the process at foreground importance + the CPU awake for the item stream so a screen-off
    // can't reset the streaming socket mid-frame (#85). NoOp default (tests/previews); production
    // wires a foreground-service-backed implementation. Driven start-before-phase / stop-in-finally.
    private val transferKeepAlive: TransferKeepAlive = TransferKeepAlive.NoOp,
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
     * Non-null ⇒ portage is still the default SMS app from an interrupted handoff (process death,
     * dismissed restore prompt). Drives an in-app one-tap restore — the persistent backstop to the
     * `finally` relinquish, which cannot survive a kill (DEVILS_ADVOCATE.md Q4 §3).
     */
    private val _smsRoleStrand = MutableStateFlow<SmsRoleStrand?>(null)
    val smsRoleStrand: StateFlow<SmsRoleStrand?> = _smsRoleStrand.asStateFlow()

    /**
     * Called on [reset] (return-home) to abandon any sealed-but-uncommitted `PackageInstaller`
     * sessions from this run. Injected from `:app-recv` so the ViewModel stays Android-free (fix 5).
     * Default is a no-op; production wires [PackageInstallerApkInstaller.abandonUncommittedSessions].
     */
    private val onAbandonSessions: () -> Unit = abandonSessions

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
            _state.value = ReceiverState.Scanning
        }
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
                // Cap the WHOLE data phase, not just each read. withTimeoutOrNull (NOT withTimeout)
                // returns null on ITS OWN timeout, so a stalled peer becomes a visible Failed rather
                // than a re-thrown cancellation. null strictly means "this budget elapsed": the block
                // always returns a non-null List, and a concurrent reset() here CLOSES THE CHANNEL
                // (the receiver has no transferJob to cancel) — which surfaces as a transport error in
                // catch(t), never as null. Effective ceiling is dataPhaseTimeoutMs PLUS up to one
                // per-read soTimeout: coroutine cancellation can't interrupt a parked native read, so
                // the read unblocks via the socket soTimeout, then the elapsed budget converts it to
                // null and the block's finally clauses (staging sweep / SMS-role relinquish) run before
                // we fail. See [DATA_PHASE_TIMEOUT_MS].
                val results = withTimeoutOrNull(dataPhaseTimeoutMs) {
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
                        ).run(
                            channel = ch,
                            expected = selected.associateBy { it.itemId },
                            apply = ::applyStaged,
                            onEvent = ::onReceiveEvent,
                        )
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
                val moved = results.count { it.status == ItemStatus.OK }
                val nameById = (_state.value as? ReceiverState.Transferring)
                    ?.items?.associate { it.itemId to it.displayName }
                    ?: emptyMap()
                val failedItems = results
                    .filter { it.status != ItemStatus.OK }
                    .map { r -> FailedItem(r.itemId, nameById[r.itemId] ?: "#${r.itemId}", r.status, r.detail) }
                _state.value = ReceiverState.Done(
                    moved = moved,
                    skipped = results.size - moved,
                    installActions = _installActions.value,
                    repairEntries = _repairEntries.value,
                    relayPrompts = _relayPrompts.value,
                    apkInstallPrompts = _apkInstallPrompts.value,
                    restoredPermissions = _restoredPermissions.value,
                    optInPermissions = _optInPermissions.value,
                    failedItems = failedItems,
                )
                channel?.close()
                channel = null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // reset() cancels and closes the channel underneath this coroutine; the
                // resulting IO error must not flip the user's Home back to Failed.
                ensureActive()
                fail(t.message ?: "Transfer failed")
            } finally {
                // Always release the keep-alive — done, fail, timeout, or a reset() cancellation
                // unwinding through here. Idempotent.
                transferKeepAlive.stop()
            }
        }
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
        onAbandonSessions()
        _installActions.value = emptyList()
        _repairEntries.value = emptyList()
        _relayPrompts.value = emptyList()
        _apkInstallPrompts.value = emptyList()
        _restoredPermissions.value = emptyList()
        _optInPermissions.value = emptyList()
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
 * The Done-screen sinks the apply providers feed (ADR-006). All are checklists/prompts/summaries the
 * user works through on the Done screen, NEVER silent side effects: [onInstallActions] the app-inventory
 * reinstall list, [onRepairEntries] the bonded-Bluetooth re-pair list (PRP-07 Phase 1 — display only,
 * never createBond), [onRelayPrompt] the per-app-backup re-link reminder (PRP-06 — portage hands off the
 * OPAQUE file and never imports it), [onApkInstallPrompt] the Tier-0 install rows, [onPermissionsRestored]
 * the auto-granted default-safe perms summary (ADR-006 D5), [onOptInPermissions] the opt-in dangerous-perm
 * surface (ADR-006 D5, Phase 5d — DATA ONLY, nothing granted from it).
 *
 * Bundled into one holder so adding a sink is a one-line change here, not a positional-param churn across
 * every [ApplyRegistryFactory] call site (production + tests).
 */
data class DoneSinks(
    val onInstallActions: (List<InstallAction>) -> Unit,
    val onRepairEntries: (List<RePairEntry>) -> Unit,
    val onRelayPrompt: (RelayRestorePrompt) -> Unit,
    val onApkInstallPrompt: (ApkInstallPrompt) -> Unit,
    val onPermissionsRestored: (packageName: String, permissions: List<String>) -> Unit,
    val onOptInPermissions: (packageName: String, permissions: List<String>) -> Unit,
)

/** Builds the compiled apply registry, wired to the receiver's Done-screen [DoneSinks]. A `fun interface`
 *  so production wires real providers while tests pass a trivial lambda. */
fun interface ApplyRegistryFactory {
    fun create(sinks: DoneSinks): ApplyProviderRegistry
}

/**
 * Raised per-item ceiling for the APP_BACKUP_RELAY kind ONLY (PRP-06 §5). Signal backups can run to
 * multiple GiB; 2 GiB is a documented, finite ceiling — large enough for real backups, small enough
 * to keep the staging write bounded. This is applied via [ItemStreamReceiver.maxBytesByKind] and
 * MUST NOT be the default cap: Tier-0/PII items keep the 64 MiB DEFAULT_MAX_ITEM_BYTES.
 */
private const val MAX_RELAY_ITEM_BYTES = 2L * 1024 * 1024 * 1024
