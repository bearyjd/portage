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
}
