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

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemResult
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.PairingPayload
import cc.grepon.portage.model.ProtocolMessage
import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.providers.inventory.AppRecord
import cc.grepon.portage.providers.inventory.InventorySource
import cc.grepon.portage.providers.relay.RelayApp
import cc.grepon.portage.send.relay.RelayFile
import cc.grepon.portage.send.relay.RelayRestoreNotes
import cc.grepon.portage.transport.PairingCodecImpl
import cc.grepon.portage.transport.SecureChannel
import cc.grepon.portage.transport.TransportException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.security.SecureRandom

private class BytesExport(
    override val kind: ItemKind,
    private val payload: ByteArray?,
    private val throwOnAvailable: Boolean = false,
) : ExportProvider {
    override val displayName = kind.wire
    override val group = "G"
    override suspend fun available(): Boolean {
        if (throwOnAvailable) throw IllegalStateException("boom")
        return payload != null
    }
    override suspend fun exportTo(sink: OutputStream) = sink.write(payload ?: ByteArray(0))
}

/** Scripted peer. Suspends (yield) at every call so StateFlow collectors see intermediates. */
private class ScriptedChannel(vararg incoming: ProtocolMessage?) : SecureChannel {
    private val queue = ArrayDeque(incoming.toList())
    val sent = mutableListOf<ProtocolMessage>()
    var closed = false
    override suspend fun send(message: ProtocolMessage) {
        yield()
        sent += message
    }
    override suspend fun receive(): ProtocolMessage? {
        yield()
        return if (queue.isEmpty()) null else queue.removeFirst()
    }
    override fun close() { closed = true }
}

/** Fake inventory seam reporting a fixed installed-package set for relay detection. */
private class FakeInventorySource(private val packages: Set<String>) : InventorySource {
    override fun installedUserApps(): List<AppRecord> = emptyList()
    override fun installedPackageNames(): Set<String> = packages
}

private class FakeFactory(
    private val channel: SecureChannel? = null,
    private val acceptError: Throwable? = null,
) : SecureChannel.Factory {
    var acceptedPayload: PairingPayload? = null
    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel =
        throw UnsupportedOperationException("sender tests never dial")
    override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel {
        acceptedPayload = payload
        yield() // the real listener parks here awaiting the handshake
        acceptError?.let { throw it }
        return checkNotNull(channel)
    }
}

class SenderViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun happyChannel() = ScriptedChannel(
        ProtocolMessage.Hello("0.1.0", "recv"),
        ProtocolMessage.Select(want = listOf(1)),
        ProtocolMessage.ItemAck(ItemResult(1, ItemStatus.OK)),
        ProtocolMessage.BatchAck(listOf(ItemResult(1, ItemStatus.OK))),
    )

    private fun viewModel(
        factory: SecureChannel.Factory,
        providers: List<ExportProvider> = listOf(BytesExport(ItemKind.CONTACTS_VCF, "vcard".toByteArray())),
        hints: List<String> = listOf("192.168.1.2"),
        inventorySource: InventorySource? = null,
    ) = SenderViewModel(
        providers = providers,
        stagingDir = tmp.root,
        senderName = "old phone",
        channelFactory = factory,
        pairingCodec = PairingCodecImpl(),
        random = SecureRandom(),
        addressHints = { hints },
        portFinder = { 40123 },
        nowEpochSeconds = { 1_000 },
        inventorySource = inventorySource,
        // Resolve relay picks on the SAME test dispatcher so advanceUntilIdle() drives the off-main
        // resolution deterministically (in production this is Dispatchers.IO, off the UI thread).
        relayResolveDispatcher = dispatcher,
    )

    private fun signalPick(
        pickId: Long = 1L,
        bytes: ByteArray = "signal-backup-bytes".toByteArray(),
        releaseGrant: () -> Unit = {},
        openStream: () -> java.io.InputStream = { java.io.ByteArrayInputStream(bytes) },
    ) = RelayFile(
        pickId = pickId,
        app = RelayApp.SIGNAL,
        targetPackage = "org.thoughtcrime.securesms",
        originalName = "signal.backup",
        restoreNote = RelayRestoreNotes.defaultFor(RelayApp.SIGNAL),
        byteLength = bytes.size.toLong(),
        openStream = openStream,
        releaseGrant = releaseGrant,
    )

    @Test
    fun `start runs prepare → QR → linked → sending → done`() = runTest(dispatcher) {
        val channel = happyChannel()
        val factory = FakeFactory(channel)
        val vm = viewModel(factory)

        vm.onStartTransfer()
        assertThat(vm.state.value).isEqualTo(SenderState.Preparing)
        advanceUntilIdle()

        val done = vm.state.value as SenderState.Done
        assertThat(done.sent).isEqualTo(1)
        assertThat(done.failed).isEqualTo(0)
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.Manifest>()).hasSize(1)
        assertThat(channel.closed).isTrue()
    }

    @Test
    fun `the QR encodes the listener's real coordinates and a fresh PSK`() = runTest(dispatcher) {
        val factory = FakeFactory(happyChannel())
        val vm = viewModel(factory)
        val seenQr = mutableListOf<SenderState.ShowingQr>()
        // Capture the intermediate QR state as it flies by (backgroundScope auto-cancels).
        backgroundScope.launch {
            vm.state.collect { if (it is SenderState.ShowingQr) seenQr += it }
        }

        vm.onStartTransfer()
        advanceUntilIdle()

        val qr = seenQr.first()
        assertThat(qr.itemCount).isEqualTo(1)
        val payload = PairingCodecImpl().decode(qr.qrText, nowEpochSeconds = 1_000).getOrThrow()
        assertThat(payload.ip).containsExactly("192.168.1.2")
        assertThat(payload.port).isEqualTo(40123)
        assertThat(payload.psk.any { it != 0.toByte() }).isTrue() // CSPRNG, not blank
        assertThat(payload.expiresAtEpochSeconds).isEqualTo(1_000 + PairingPayload.DEFAULT_TTL_SECONDS)
        // The exact payload handed to the listener is the one in the QR.
        assertThat(factory.acceptedPayload?.port).isEqualTo(40123)
    }

    @Test
    fun `no receiver before the deadline fails visibly, not hanging`() = runTest(dispatcher) {
        val factory = FakeFactory(acceptError = TransportException("no peer completed the handshake within the deadline"))
        val vm = viewModel(factory)

        vm.onStartTransfer()
        advanceUntilIdle()

        val failed = vm.state.value as SenderState.Failed
        assertThat(failed.reason).contains("deadline")
    }

    @Test
    fun `nothing exportable fails gracefully before any listening`() = runTest(dispatcher) {
        val factory = FakeFactory(happyChannel())
        val vm = viewModel(
            factory,
            providers = listOf(
                BytesExport(ItemKind.CONTACTS_VCF, payload = null),
                BytesExport(ItemKind.CALL_LOG, payload = "x".toByteArray(), throwOnAvailable = true),
            ),
        )

        vm.onStartTransfer()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(SenderState.Failed::class.java)
        assertThat(factory.acceptedPayload).isNull() // never even listened
    }

    @Test
    fun `no LAN address fails before showing a useless QR`() = runTest(dispatcher) {
        val factory = FakeFactory(happyChannel())
        val vm = viewModel(factory, hints = emptyList())

        vm.onStartTransfer()
        advanceUntilIdle()

        val failed = vm.state.value as SenderState.Failed
        assertThat(failed.reason).contains("Wi-Fi")
        assertThat(factory.acceptedPayload).isNull()
    }

    @Test
    fun `sending tracks the receiver's picks per item`() = runTest(dispatcher) {
        val factory = FakeFactory(happyChannel())
        val vm = viewModel(factory)
        val sendingStates = mutableListOf<SenderState.Sending>()
        backgroundScope.launch {
            vm.state.collect { if (it is SenderState.Sending) sendingStates += it }
        }

        vm.onStartTransfer()
        advanceUntilIdle()

        assertThat(sendingStates.first().items.map { it.itemId }).containsExactly(1)
        val last = sendingStates.last().items.single()
        assertThat(last.phase).isEqualTo(SendPhase.ACKED)
        assertThat(last.bytesSent).isEqualTo("vcard".length.toLong())
    }

    @Test
    fun `reset cleans staging and returns Home`() = runTest(dispatcher) {
        val vm = viewModel(FakeFactory(happyChannel()))
        vm.onStartTransfer()
        advanceUntilIdle()

        vm.reset()

        assertThat(vm.state.value).isEqualTo(SenderState.Home)
        assertThat(tmp.root.listFiles().orEmpty()).isEmpty()
    }

    // ---- app-backup relay (PRP-06): detection + user-driven staging into the manifest ----

    @Test
    fun `relay detection populates candidates from the inventory seam`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeFactory(happyChannel()),
            inventorySource = FakeInventorySource(
                setOf("org.thoughtcrime.securesms", "com.unrelated.app"),
            ),
        )
        assertThat(vm.relayCandidates.value.map { it.app }).containsExactly(RelayApp.SIGNAL)
    }

    @Test
    fun `relay candidates are empty when no inventory seam is wired`() = runTest(dispatcher) {
        val vm = viewModel(FakeFactory(happyChannel()))
        assertThat(vm.relayCandidates.value).isEmpty()
    }

    @Test
    fun `a picked relay file rides into the manifest as a distinct APP_BACKUP_RELAY item`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(FakeFactory(channel))
        vm.onRelayFilePicked(signalPick())

        vm.onStartTransfer()
        advanceUntilIdle()

        val manifest = channel.sent.filterIsInstance<ProtocolMessage.Manifest>().single().manifest
        // The base contacts item plus the relay item — distinct ids, the relay kind present.
        assertThat(manifest.items.map { it.kind })
            .containsExactly(ItemKind.CONTACTS_VCF, ItemKind.APP_BACKUP_RELAY)
        assertThat(manifest.items.map { it.itemId }.toSet()).hasSize(2)
    }

    @Test
    fun `two picked relays produce two distinct relay items in the manifest`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(FakeFactory(channel))
        vm.onRelayFilePicked(signalPick(pickId = 1L, bytes = "first".toByteArray()))
        vm.onRelayFilePicked(signalPick(pickId = 2L, bytes = "second".toByteArray()))

        vm.onStartTransfer()
        advanceUntilIdle()

        val manifest = channel.sent.filterIsInstance<ProtocolMessage.Manifest>().single().manifest
        val relayItems = manifest.items.filter { it.kind == ItemKind.APP_BACKUP_RELAY }
        assertThat(relayItems).hasSize(2)
        assertThat(relayItems.map { it.itemId }.toSet()).hasSize(2)
    }

    @Test
    fun `removing a pick keeps it out of the manifest`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(FakeFactory(channel))
        vm.onRelayFilePicked(signalPick(pickId = 7L))
        vm.removeRelayPick(7L)

        vm.onStartTransfer()
        advanceUntilIdle()

        val manifest = channel.sent.filterIsInstance<ProtocolMessage.Manifest>().single().manifest
        assertThat(manifest.items.map { it.kind }).containsExactly(ItemKind.CONTACTS_VCF)
    }

    @Test
    fun `reset clears picked relay files`() = runTest(dispatcher) {
        val vm = viewModel(FakeFactory(happyChannel()))
        vm.onRelayFilePicked(signalPick())
        assertThat(vm.relayPicks.value).hasSize(1)

        vm.reset()

        assertThat(vm.relayPicks.value).isEmpty()
    }

    // ---- robustness: SAF grant release, off-main resolve, expired backstop, clear-on-success ----

    @Test
    fun `removing a pick releases its persistable SAF grant`() = runTest(dispatcher) {
        var released = false
        val vm = viewModel(FakeFactory(happyChannel()))
        vm.onRelayFilePicked(signalPick(pickId = 9L, releaseGrant = { released = true }))

        vm.removeRelayPick(9L)

        assertThat(released).isTrue()
    }

    @Test
    fun `reset releases the SAF grant of every picked file`() = runTest(dispatcher) {
        var releasedA = false
        var releasedB = false
        val vm = viewModel(FakeFactory(happyChannel()))
        vm.onRelayFilePicked(signalPick(pickId = 1L, releaseGrant = { releasedA = true }))
        vm.onRelayFilePicked(signalPick(pickId = 2L, releaseGrant = { releasedB = true }))

        vm.reset()

        assertThat(releasedA).isTrue()
        assertThat(releasedB).isTrue()
    }

    @Test
    fun `a successful transfer clears picks and releases their SAF grants`() = runTest(dispatcher) {
        var released = false
        val channel = happyChannel()
        val vm = viewModel(FakeFactory(channel))
        vm.onRelayFilePicked(signalPick(releaseGrant = { released = true }))

        vm.onStartTransfer()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(SenderState.Done::class.java)
        assertThat(vm.relayPicks.value).isEmpty()
        assertThat(released).isTrue()
    }

    @Test
    fun `a relay pick whose stream cannot open is marked expired and excluded from the manifest`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(FakeFactory(channel))
        // Grant gone after process death / revoke: opening the picked file throws at transfer time.
        vm.onRelayFilePicked(
            signalPick(openStream = { throw java.io.IOException("grant revoked") }),
        )

        vm.onStartTransfer()
        advanceUntilIdle()

        // The relay item NEVER silently ships — the manifest carries only the non-relay item.
        val manifest = channel.sent.filterIsInstance<ProtocolMessage.Manifest>().single().manifest
        assertThat(manifest.items.map { it.kind }).containsExactly(ItemKind.CONTACTS_VCF)
        // The pick survives, flagged expired, so the user sees it did NOT ship and can re-pick.
        val pick = vm.relayPicks.value.single()
        assertThat(pick.expired).isTrue()
    }

    @Test
    fun `a healthy relay pick is never flagged expired`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(FakeFactory(channel))
        vm.onRelayFilePicked(signalPick())

        vm.onStartTransfer()
        advanceUntilIdle()

        // It shipped, so it (and its grant) is cleared on success — no lingering expired flag.
        assertThat(vm.relayPicks.value).isEmpty()
    }

    @Test
    fun `resolveAndAddRelayPick resolves off the calling thread and appends the file`() = runTest(dispatcher) {
        val vm = viewModel(FakeFactory(happyChannel()))
        val resolved = signalPick(pickId = 5L)

        // The resolve lambda stands in for AndroidRelayFileResolver.resolve (a large-file read that
        // must not run on the main thread). The VM dispatches it; the test dispatcher runs it.
        vm.resolveAndAddRelayPick { resolved }
        advanceUntilIdle()

        assertThat(vm.relayPicks.value.map { it.pickId }).containsExactly(5L)
    }

    @Test
    fun `resolveAndAddRelayPick drops a null resolution without adding a pick`() = runTest(dispatcher) {
        val vm = viewModel(FakeFactory(happyChannel()))

        vm.resolveAndAddRelayPick { null }
        advanceUntilIdle()

        assertThat(vm.relayPicks.value).isEmpty()
    }
}
