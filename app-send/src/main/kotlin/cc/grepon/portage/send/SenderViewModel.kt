/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.PairingPayload
import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.send.pairing.LanAddresses
import cc.grepon.portage.send.transfer.ManifestBuilder
import cc.grepon.portage.send.transfer.StagedManifest
import cc.grepon.portage.send.transfer.TransferEngine
import cc.grepon.portage.transport.NoiseSecureChannelFactory
import cc.grepon.portage.transport.PairingCodec
import cc.grepon.portage.transport.PairingCodecImpl
import cc.grepon.portage.transport.SecureChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * Drives the sender flow: stage exports → show the pairing QR (the trust anchor) → accept
 * exactly ONE handshake ([SecureChannel.Factory.acceptAsSender], 120 s budget) → run the
 * [TransferEngine] → done summary. Every failure is a visible [SenderState.Failed], never
 * a hang: the listener enforces its own deadline, and prepare-stage problems (nothing to
 * carry, no LAN) fail before a useless QR is ever shown.
 *
 * The QR text contains the one-time PSK — it is held only in [SenderState.ShowingQr] for
 * rendering, never logged; the factory wipes the payload copy after the handshake.
 */
class SenderViewModel(
    private val providers: List<ExportProvider>,
    private val stagingDir: File,
    private val senderName: String,
    private val channelFactory: SecureChannel.Factory = NoiseSecureChannelFactory(),
    private val pairingCodec: PairingCodec = PairingCodecImpl(),
    private val engine: TransferEngine = TransferEngine(),
    private val random: SecureRandom = SecureRandom(),
    private val addressHints: () -> List<String> = { LanAddresses.hints(LanAddresses.enumerate()) },
    private val portFinder: () -> Int = ::findFreePort,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private val _state = MutableStateFlow<SenderState>(SenderState.Home)
    val state: StateFlow<SenderState> = _state.asStateFlow()

    private var channel: SecureChannel? = null
    private var staged: StagedManifest? = null
    private var transferJob: Job? = null

    fun onStartTransfer() {
        if (_state.value !is SenderState.Home && _state.value !is SenderState.Failed) return
        _state.value = SenderState.Preparing
        transferJob = viewModelScope.launch {
            try {
                val built = ManifestBuilder(providers, stagingDir, senderName).build()
                    .also { staged = it }
                if (built.items.isEmpty()) {
                    fail("Nothing to carry yet — grant the read permissions on this phone")
                    return@launch
                }
                val hints = addressHints()
                if (hints.isEmpty()) {
                    fail("No LAN address — join the same Wi-Fi as the new phone")
                    return@launch
                }

                val payload = PairingPayload(
                    psk = randomBytes(PairingPayload.PSK_BYTES),
                    sid = randomBytes(PairingPayload.SID_BYTES),
                    ip = hints,
                    port = portFinder(),
                    expiresAtEpochSeconds = nowEpochSeconds() + PairingPayload.DEFAULT_TTL_SECONDS,
                )
                _state.value = SenderState.ShowingQr(
                    qrText = pairingCodec.encode(payload),
                    itemCount = built.items.size,
                    totalBytes = built.manifest.totalBytes,
                )

                val ch = channelFactory.acceptAsSender(payload).also { channel = it }
                _state.value = SenderState.Linked

                val results = engine.run(ch, built) { event -> onEngineEvent(built, event) }
                val ok = results.count { it.status == ItemStatus.OK }
                _state.value = SenderState.Done(sent = ok, failed = results.size - ok)
                closeChannel()
                cleanupStaging()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(t.message ?: "Transfer failed")
            }
        }
    }

    private fun onEngineEvent(built: StagedManifest, event: TransferEngine.Event) {
        when (event) {
            is TransferEngine.Event.SelectReceived ->
                _state.value = SenderState.Sending(
                    items = built.items
                        .filter { it.meta.itemId in event.want }
                        .map { SendProgress(it.meta.itemId, it.meta.displayName, it.meta.size) },
                )

            is TransferEngine.Event.ItemStarted ->
                updateItem(event.itemId) { it.copy(phase = SendPhase.SENDING) }

            is TransferEngine.Event.ItemProgressed ->
                updateItem(event.itemId) { it.copy(bytesSent = event.bytesSent) }

            is TransferEngine.Event.ItemAcked ->
                updateItem(event.result.itemId) {
                    it.copy(
                        phase = if (event.result.status == ItemStatus.OK) SendPhase.ACKED else SendPhase.FAILED,
                        detail = event.result.detail,
                    )
                }
        }
    }

    private fun updateItem(itemId: Int, transform: (SendProgress) -> SendProgress) {
        val current = _state.value as? SenderState.Sending ?: return
        _state.value = current.copy(
            items = current.items.map { if (it.itemId == itemId) transform(it) else it },
        )
    }

    fun reset() {
        transferJob?.cancel()
        transferJob = null
        closeChannel()
        cleanupStaging()
        _state.value = SenderState.Home
    }

    /** Fail-closed terminal transition: release the listener/channel, surface the reason. */
    private fun fail(reason: String) {
        closeChannel()
        cleanupStaging()
        _state.value = SenderState.Failed(reason)
    }

    private fun closeChannel() {
        channel?.let { runCatching { it.close() } }
        channel = null
    }

    /** Staged payloads hold personal data — delete them the moment they're not needed. */
    private fun cleanupStaging() {
        staged?.cleanup()
        staged = null
    }

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun onCleared() {
        closeChannel()
        cleanupStaging()
        super.onCleared()
    }

    private companion object {
        /** Probe-and-release; acceptAsSender rebinds with SO_REUSEADDR so the race is benign. */
        fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
