/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemMeta
import cc.grepon.portage.model.ItemResult
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.ProtocolMessage
import cc.grepon.portage.privileged.PrivilegedAccess
import cc.grepon.portage.privileged.PrivilegedOps
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProviderRegistry
import cc.grepon.portage.providers.inventory.InstallAction
import cc.grepon.portage.recv.checklist.ReceiverChecklist
import cc.grepon.portage.recv.shizuku.ShizukuAccessStrand
import cc.grepon.portage.recv.shizuku.strandFor
import cc.grepon.portage.recv.sms.SmsRoleCoordinator
import cc.grepon.portage.recv.sms.SmsRoleStrand
import cc.grepon.portage.recv.transfer.ItemStreamReceiver
import cc.grepon.portage.transport.NoiseSecureChannelFactory
import cc.grepon.portage.transport.PairingCodec
import cc.grepon.portage.transport.PairingCodecImpl
import cc.grepon.portage.transport.SecureChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val appVersion: String = "0.1.0",
    private val osFingerprint: String = android.os.Build.FINGERPRINT,
    // Deliberately NO default: staged payloads are plaintext PII, so the staging location
    // must be wired explicitly (production: app-private cacheDir via the factory).
    private val stagingDir: File,
    // Inert by default: without a real coordinator (or its manifest role components) SMS
    // can never be granted, so the apply path always self-skips.
    private val smsRoleCoordinator: SmsRoleCoordinator = SmsRoleCoordinator.Inert,
    // Inert by default: with no Shizuku bridge the optional secure-settings unlock simply reports
    // "not installed" and the Tier-1 keys self-skip (the apply path uses PrivilegedOps separately).
    private val privilegedAccess: PrivilegedAccess = PrivilegedAccess.Inert,
    applyRegistryFactory: ((List<InstallAction>) -> Unit) -> ApplyProviderRegistry =
        { ApplyProviderRegistry(emptyList()) },
) : ViewModel() {

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    /** Reinstall checklist produced by the app-inventory apply (one user tap per app). */
    private val _installActions = MutableStateFlow<List<InstallAction>>(emptyList())
    val installActions: StateFlow<List<InstallAction>> = _installActions.asStateFlow()

    /**
     * Non-null ⇒ portage is still the default SMS app from an interrupted handoff (process death,
     * dismissed restore prompt). Drives an in-app one-tap restore — the persistent backstop to the
     * `finally` relinquish, which cannot survive a kill (DEVILS_ADVOCATE.md Q4 §3).
     */
    private val _smsRoleStrand = MutableStateFlow<SmsRoleStrand?>(null)
    val smsRoleStrand: StateFlow<SmsRoleStrand?> = _smsRoleStrand.asStateFlow()

    /**
     * Status of the OPTIONAL secure-settings unlock (ADR-001) — drives the secondary affordance on
     * the Home screen. Derived from the live Shizuku bridge; [ShizukuAccessStrand.UNLOCKED] once the
     * one-shot grant has landed. Independent of the transfer flow (a Tier-0 transfer ignores it).
     */
    private val _shizukuAccess = MutableStateFlow(ShizukuAccessStrand.NOT_INSTALLED)
    val shizukuAccess: StateFlow<ShizukuAccessStrand> = _shizukuAccess.asStateFlow()

    private val applyRegistry: ApplyProviderRegistry =
        applyRegistryFactory { actions -> _installActions.value = actions }

    private var channel: SecureChannel? = null

    init {
        refreshSmsRoleStrand()
        refreshShizukuAccess()
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
        _state.value = ReceiverState.Transferring(
            items = selected.map { ItemProgress(it.itemId, it.displayName) },
        )
        val needsSmsRole = selected.any { it.kind == ItemKind.SMS }
        viewModelScope.launch {
            try {
                val ch = channel ?: error("no channel")
                val results = withSmsRoleIfNeeded(needsSmsRole) {
                    ch.send(ProtocolMessage.Select(selected.map { it.itemId }))
                    ItemStreamReceiver(stagingDir).run(
                        channel = ch,
                        expected = selected.associateBy { it.itemId },
                        apply = ::applyStaged,
                        onEvent = ::onReceiveEvent,
                    )
                }
                ensureActive() // a reset() mid-run must not be overwritten by Done
                val moved = results.count { it.status == ItemStatus.OK }
                _state.value = ReceiverState.Done(
                    moved = moved,
                    skipped = results.size - moved,
                    installActions = _installActions.value,
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
    private fun onReceiveEvent(event: ItemStreamReceiver.Event) {
        when (event) {
            is ItemStreamReceiver.Event.ItemStarted ->
                updateItem(event.itemId) { it.copy(phase = ItemPhase.RECEIVING) }

            is ItemStreamReceiver.Event.ItemProgressed -> Unit // byte ticks not surfaced per-row in v1

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

    fun reset() {
        channel?.close()
        channel = null
        _installActions.value = emptyList()
        _state.value = ReceiverState.Idle
        // Returning Home is a chance to clear (or surface) a leftover default-SMS strand, and to
        // re-derive the optional secure-settings unlock state for the Home affordance.
        refreshSmsRoleStrand()
        refreshShizukuAccess()
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

    /**
     * Reconcile the optional secure-settings unlock affordance with the live bridge — called at
     * startup, when returning Home, and on every resume (so it reflects the user installing,
     * starting, or authorizing Shizuku OUTSIDE the app). Never clobbers an in-flight unlock:
     * [unlockSecureSettings] owns the [ShizukuAccessStrand.UNLOCKING] window and finalizes it itself.
     */
    fun refreshShizukuAccess() {
        if (_shizukuAccess.value == ShizukuAccessStrand.UNLOCKING) return
        _shizukuAccess.value = derivedShizukuStrand()
    }

    /**
     * User tapped "Unlock secure settings": authorize Shizuku if needed, then run the one-shot
     * WRITE_SECURE_SETTINGS grant — a single gesture covers both steps. A no-op unless currently
     * actionable ([ShizukuAccessStrand.LOCKED], or a [ShizukuAccessStrand.GRANT_FAILED] retry), so a
     * double-tap or a tap while showing guidance does nothing. Fails closed back to the derived
     * strand whenever authorization or the grant doesn't complete.
     */
    fun unlockSecureSettings() {
        val strand = _shizukuAccess.value
        if (strand != ShizukuAccessStrand.LOCKED && strand != ShizukuAccessStrand.GRANT_FAILED) return
        _shizukuAccess.value = ShizukuAccessStrand.UNLOCKING
        viewModelScope.launch {
            val authorized = when (privilegedAccess.availability()) {
                PrivilegedOps.Availability.LIVE -> true
                PrivilegedOps.Availability.PERMISSION_DENIED -> privilegedAccess.requestAccess()
                PrivilegedOps.Availability.INSTALLED_NOT_RUNNING,
                PrivilegedOps.Availability.NOT_INSTALLED,
                PrivilegedOps.Availability.OUTDATED,
                -> false
            }
            if (!authorized) {
                _shizukuAccess.value = derivedShizukuStrand()
                return@launch
            }
            _shizukuAccess.value = when (privilegedAccess.ensureWriteSecureSettingsGranted()) {
                PrivilegedOps.GrantOutcome.GRANTED -> ShizukuAccessStrand.UNLOCKED
                PrivilegedOps.GrantOutcome.GRANT_REJECTED -> ShizukuAccessStrand.GRANT_FAILED
                PrivilegedOps.GrantOutcome.BRIDGE_UNAVAILABLE -> derivedShizukuStrand()
            }
        }
    }

    /** Unguarded point-in-time read (the unlock coroutine finalizes through this, bypassing the guard). */
    private fun derivedShizukuStrand(): ShizukuAccessStrand {
        // One snapshot per derivation — avoids a second bridge read and a (benign) TOCTOU between them.
        val availability = privilegedAccess.availability()
        return strandFor(availability, privilegedAccess.canWriteSecureSettings())
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
