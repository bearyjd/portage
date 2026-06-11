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
import cc.grepon.portage.model.ItemMeta
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.ProtocolMessage
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProviderRegistry
import cc.grepon.portage.providers.inventory.InstallAction
import cc.grepon.portage.recv.checklist.ReceiverChecklist
import cc.grepon.portage.transport.NoiseSecureChannelFactory
import cc.grepon.portage.transport.PairingCodec
import cc.grepon.portage.transport.PairingCodecImpl
import cc.grepon.portage.transport.SecureChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * Drives the receiver flow: scan → pair (real [SecureChannel.Factory.connectAsReceiver]) →
 * receive manifest → checklist → confirm → per-item apply via [ApplyProviderRegistry].
 * The item byte-stream (ITEM_BEGIN/DATA/END staging + sha256 verify) plugs into
 * [applyStaged]; until the live loop lands, [onConfirm] stops at SELECT.
 */
class ReceiverViewModel(
    private val pairingCodec: PairingCodec = PairingCodecImpl(),
    private val channelFactory: SecureChannel.Factory = NoiseSecureChannelFactory(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val appVersion: String = "0.1.0",
    private val osFingerprint: String = android.os.Build.FINGERPRINT,
    applyRegistryFactory: ((List<InstallAction>) -> Unit) -> ApplyProviderRegistry =
        { ApplyProviderRegistry(emptyList()) },
) : ViewModel() {

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    /** Reinstall checklist produced by the app-inventory apply (one user tap per app). */
    private val _installActions = MutableStateFlow<List<InstallAction>>(emptyList())
    val installActions: StateFlow<List<InstallAction>> = _installActions.asStateFlow()

    private val applyRegistry: ApplyProviderRegistry =
        applyRegistryFactory { actions -> _installActions.value = actions }

    private var channel: SecureChannel? = null

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
        viewModelScope.launch {
            try {
                val ch = channel ?: error("no channel")
                ch.send(ProtocolMessage.Select(selected.map { it.itemId }))
                // TODO(Gap 3): drive ITEM_BEGIN/DATA/END → stage → verify sha256 →
                // applyStaged(meta, stream) per item → BATCH_END/BATCH_ACK. Until then
                // moved/skipped are PLACEHOLDERS counting requested items, not applied.
                _state.value = ReceiverState.Done(moved = selected.size, skipped = 0)
                channel?.close()
                channel = null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(t.message ?: "Transfer failed")
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
