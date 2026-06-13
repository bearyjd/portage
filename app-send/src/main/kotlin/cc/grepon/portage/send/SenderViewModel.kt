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
import cc.grepon.portage.providers.inventory.InventorySource
import cc.grepon.portage.providers.relay.RelayAppDetector
import cc.grepon.portage.providers.relay.RelayCandidate
import cc.grepon.portage.send.pairing.LanAddresses
import cc.grepon.portage.send.relay.RelayFile
import cc.grepon.portage.send.relay.relayExportProviders
import cc.grepon.portage.send.transfer.ManifestBuilder
import cc.grepon.portage.send.transfer.StagedManifest
import cc.grepon.portage.send.transfer.TransferEngine
import cc.grepon.portage.transport.NoiseSecureChannelFactory
import cc.grepon.portage.transport.PairingCodec
import cc.grepon.portage.transport.PairingCodecImpl
import cc.grepon.portage.transport.SecureChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
 * rendering, never logged; the factory wipes the payload copy after the handshake. The
 * encoded String itself is not zeroizable (JVM immutability) and lives until GC after the
 * state leaves ShowingQr — accepted residual under THREAT_MODEL §1's "on-device process
 * compromise out of scope" boundary (security review 2026-06-11, MEDIUM, documented).
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
    // The PackageManager seam used to detect installed relay-capable apps (Signal/Molly/Aegis,
    // PRP-06). Null (default) ⇒ no relay suggestions, the flow is unaffected — relay is purely
    // additive. Detection only SUGGESTS which apps have a backup the user can relay; the user still
    // exports the file IN the app and picks it via SAF.
    private val inventorySource: InventorySource? = null,
    // Where relay picks are resolved. Resolution may stream a whole file to count bytes (SAF omits
    // SIZE), so it MUST stay off the main thread — defaults to IO; tests inject the test dispatcher.
    private val relayResolveDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<SenderState>(SenderState.Home)
    val state: StateFlow<SenderState> = _state.asStateFlow()

    /**
     * Installed relay-capable apps (Signal/Molly/Aegis) the user can offer to ferry a backup for
     * (PRP-06 §3). Detected once via the inventory seam; the Home screen renders one "pick your
     * exported file" affordance per candidate. Empty when no relay app is installed (or no seam).
     */
    private val _relayCandidates = MutableStateFlow<List<RelayCandidate>>(emptyList())
    val relayCandidates: StateFlow<List<RelayCandidate>> = _relayCandidates.asStateFlow()

    /**
     * The user-picked, app-encrypted backup files staged for relay (PRP-06 §4). Each is appended to
     * the export-provider list at [onStartTransfer] so [ManifestBuilder] stages it as its own item
     * (distinct id + file). portage holds only each pick's COORDINATES and a re-open seam — never the
     * opaque bytes, never the passphrase. The list is the UI source of truth for "files to carry".
     */
    private val _relayPicks = MutableStateFlow<List<RelayFile>>(emptyList())
    val relayPicks: StateFlow<List<RelayFile>> = _relayPicks.asStateFlow()

    private var channel: SecureChannel? = null
    private var staged: StagedManifest? = null
    private var transferJob: Job? = null

    init {
        inventorySource?.let { source ->
            _relayCandidates.value = runCatching { RelayAppDetector.detect(source) }.getOrDefault(emptyList())
        }
    }

    /**
     * Record a user-picked relay file. Called by the SAF picker layer after the user exported a
     * backup IN the app and pointed portage at the resulting file. Appended to [_relayPicks] so it
     * rides into the next manifest; a blank/empty pick still self-omits at staging time (the export
     * provider's available() gate). Re-picking the same content Uri adds a distinct entry (distinct
     * [RelayFile.pickId]) rather than collapsing — two backups for one app stay distinct.
     */
    fun onRelayFilePicked(file: RelayFile) {
        _relayPicks.value = _relayPicks.value + file
    }

    /**
     * Resolve a SAF pick OFF the main thread, then record it. [resolve] wraps the Android resolver +
     * Uri (it may stream the whole file to count bytes when SAF omits SIZE), so it runs on
     * [relayResolveDispatcher] — the SAF picker callback never blocks the UI on a large-file read. A
     * null resolution (unreadable/empty file) is dropped, exactly like the synchronous path.
     */
    fun resolveAndAddRelayPick(resolve: () -> RelayFile?) {
        viewModelScope.launch(relayResolveDispatcher) {
            val file = runCatching { resolve() }.getOrNull() ?: return@launch
            _relayPicks.value = _relayPicks.value + file
        }
    }

    /** Remove a previously-picked relay file (user changed their mind before starting). */
    fun removeRelayPick(pickId: Long) {
        val (removed, kept) = _relayPicks.value.partition { it.pickId == pickId }
        removed.forEach { releaseGrant(it) }
        _relayPicks.value = kept
    }

    fun onStartTransfer() {
        if (_state.value !is SenderState.Home && _state.value !is SenderState.Failed) return
        _state.value = SenderState.Preparing
        transferJob = viewModelScope.launch {
            try {
                // Probe each pick's stream BEFORE staging: if the grant was lost (process death /
                // revoke) the open throws, so we mark that pick expired and EXCLUDE it — but it stays
                // in the list flagged so the UI shows "Expired — re-pick this file". A relay item must
                // never silently ship-without-itself, and the user must always know it did not go.
                val livePicks = probeRelayPicks(_relayPicks.value)

                // Append the user-driven relay staging path (PRP-06 §4): each LIVE user-picked
                // app-backup file becomes an APP_BACKUP_RELAY export provider, so ManifestBuilder stages
                // it as its own item — distinct id + file — alongside the auto-detected Tier-0
                // providers. A half-finished pick self-omits (the provider's available() gate). This is
                // the single integration point that gives APP_BACKUP_RELAY a producer.
                val allProviders = providers + relayExportProviders(livePicks)
                val built = ManifestBuilder(allProviders, stagingDir, senderName).build()
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
                ensureActive() // a reset() mid-run must not be overwritten by Done
                val ok = results.count { it.status == ItemStatus.OK }
                _state.value = SenderState.Done(sent = ok, failed = results.size - ok)
                closeChannel()
                cleanupStaging()
                // Clear the picks that SHIPPED (and release their SAF grants) on success too, not only
                // on reset(). Picks flagged expired are KEPT so the user still sees "did not ship —
                // re-pick" on the Done screen; they never silently disappear.
                clearShippedRelayPicks()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // reset() cancels and tears the channel down under the coroutine; the
                // resulting IO error must not flip the user's Home back to Failed.
                ensureActive()
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
        // Drop the relay picks too (and release each SAF grant): returning Home is a fresh start; the
        // user re-picks from the live app export each session.
        clearRelayPicks()
        _state.value = SenderState.Home
    }

    /**
     * Probe each pick's stream once. Picks whose [RelayFile.openStream] throws (grant gone after
     * process death/revoke) are marked [RelayFile.expired] in [_relayPicks] so the UI surfaces a
     * re-pick prompt, and are returned EXCLUDED so they never silently ship without their bytes.
     */
    private fun probeRelayPicks(picks: List<RelayFile>): List<RelayFile> {
        if (picks.isEmpty()) return emptyList()
        val probed = picks.map { pick ->
            val opens = runCatching { pick.openStream().use { } }.isSuccess
            pick.copy(expired = !opens)
        }
        _relayPicks.value = probed
        return probed.filterNot { it.expired }
    }

    /** Hard clear (reset): release every pick's SAF grant and empty the list — a fresh start. */
    private fun clearRelayPicks() {
        _relayPicks.value.forEach { releaseGrant(it) }
        _relayPicks.value = emptyList()
    }

    /**
     * Success clear: drop the picks that SHIPPED (releasing their SAF grants) but KEEP any flagged
     * [RelayFile.expired] so the user still sees they did not ship and can re-pick — never a silent
     * disappearance.
     */
    private fun clearShippedRelayPicks() {
        val (expired, shipped) = _relayPicks.value.partition { it.expired }
        shipped.forEach { releaseGrant(it) }
        _relayPicks.value = expired
    }

    /** Best-effort release of one pick's persistable SAF grant; a release failure never throws up. */
    private fun releaseGrant(pick: RelayFile) {
        runCatching { pick.releaseGrant() }
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
