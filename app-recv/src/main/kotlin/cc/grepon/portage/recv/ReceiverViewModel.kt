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
import cc.grepon.portage.model.ProtocolMessage
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

/**
 * Drives the receiver flow: scan → pair (real [SecureChannel.Factory.connectAsReceiver]) →
 * receive manifest → checklist → confirm. The item *application* layer (providers +
 * settings-catalog) plugs in at [onConfirm]; today it stops at sending SELECT.
 */
class ReceiverViewModel(
    private val pairingCodec: PairingCodec = PairingCodecImpl(),
    private val channelFactory: SecureChannel.Factory = NoiseSecureChannelFactory(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val appVersion: String = "0.1.0",
    private val osFingerprint: String = android.os.Build.FINGERPRINT,
) : ViewModel() {

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

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
        val selected = ReceiverChecklist.selectedIds(current.groups)
        if (selected.isEmpty()) return
        _state.value = ReceiverState.Transferring(completed = 0, total = selected.size)
        viewModelScope.launch {
            try {
                val ch = channel ?: error("no channel")
                ch.send(ProtocolMessage.Select(selected.toList()))
                // TODO(providers): receive ITEM_BEGIN/DATA/END, stage, verify sha256, and
                // apply via Tier0Provider + settings-catalog. moved/skipped are PLACEHOLDERS
                // until ITEM_ACK results are wired — they count requested items, not applied.
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

    fun reset() {
        channel?.close()
        channel = null
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
