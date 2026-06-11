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

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemMeta
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.PairingPayload
import cc.grepon.portage.model.ProtocolMessage
import cc.grepon.portage.model.TransferManifest
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProvider
import cc.grepon.portage.providers.ApplyProviderRegistry
import cc.grepon.portage.providers.inventory.InstallAction
import cc.grepon.portage.providers.inventory.InstallStore
import cc.grepon.portage.transport.PairingCodec
import cc.grepon.portage.transport.SecureChannel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

private val PAYLOAD = PairingPayload(
    psk = ByteArray(PairingPayload.PSK_BYTES),
    sid = ByteArray(PairingPayload.SID_BYTES),
    ip = listOf("192.168.1.2"),
    port = 7777,
    expiresAtEpochSeconds = 9_999_999_999,
)

private class FakeCodec : PairingCodec {
    override fun encode(payload: PairingPayload): String = "portage1:unused"
    override fun decode(qr: String, nowEpochSeconds: Long): Result<PairingPayload> =
        if (qr == "good-qr") Result.success(PAYLOAD)
        else Result.failure(IllegalArgumentException("Invalid pairing QR"))
}

private class FakeChannel(vararg incoming: ProtocolMessage) : SecureChannel {
    private val queue = ArrayDeque(incoming.toList())
    val sent = mutableListOf<ProtocolMessage>()
    var closed = false

    override suspend fun send(message: ProtocolMessage) { sent += message }
    override suspend fun receive(): ProtocolMessage? = queue.removeFirstOrNull()
    override fun close() { closed = true }
}

private class FakeFactory(private val channel: SecureChannel) : SecureChannel.Factory {
    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = channel
    override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel =
        throw UnsupportedOperationException("receiver tests never listen")
}

private class FakeApply(
    override val kind: ItemKind,
    private val outcome: ApplyOutcome = ApplyOutcome(ItemStatus.OK, "applied 1, skipped 0"),
) : ApplyProvider {
    var calls = 0
    override suspend fun apply(source: InputStream): ApplyOutcome {
        calls++
        return outcome
    }
}

class ReceiverViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val manifest = TransferManifest(
        senderName = "old phone",
        items = listOf(
            ItemMeta(1, ItemKind.CONTACTS_VCF, 10, "h1", "Contacts", "People"),
            ItemMeta(2, ItemKind.CALL_LOG, 10, "h2", "Call history", "History"),
        ),
        totalBytes = 20,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        channel: SecureChannel = FakeChannel(ProtocolMessage.Manifest(manifest)),
        registryFactory: ((List<InstallAction>) -> Unit) -> ApplyProviderRegistry =
            { ApplyProviderRegistry(emptyList()) },
    ) = ReceiverViewModel(
        pairingCodec = FakeCodec(),
        channelFactory = FakeFactory(channel),
        nowEpochSeconds = { 1_000 },
        appVersion = "test",
        osFingerprint = "test-fingerprint",
        applyRegistryFactory = registryFactory,
    )

    @Test
    fun `scan to reviewing happy path sends HELLO and builds the checklist`() = runTest(dispatcher) {
        val channel = FakeChannel(ProtocolMessage.Manifest(manifest))
        val vm = viewModel(channel)

        vm.startScanning()
        vm.onQrScanned("good-qr")
        assertThat(vm.state.value).isEqualTo(ReceiverState.Pairing)
        advanceUntilIdle()

        val reviewing = vm.state.value as ReceiverState.Reviewing
        assertThat(reviewing.senderName).isEqualTo("old phone")
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.Hello>()).hasSize(1)
    }

    @Test
    fun `a malformed QR fails visibly, not with a crash`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startScanning()
        vm.onQrScanned("garbage")
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(ReceiverState.Failed::class.java)
    }

    @Test
    fun `confirm enters per-item Transferring with every selected item PENDING`() = runTest(dispatcher) {
        val channel = FakeChannel(ProtocolMessage.Manifest(manifest))
        val vm = viewModel(channel)
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onConfirm()

        val transferring = vm.state.value as ReceiverState.Transferring
        assertThat(transferring.items.map { it.displayName })
            .containsExactly("Contacts", "Call history").inOrder()
        assertThat(transferring.items.map { it.phase }).containsExactly(ItemPhase.PENDING, ItemPhase.PENDING)

        advanceUntilIdle()
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.Select>().single().want)
            .containsExactly(1, 2).inOrder()
    }

    @Test
    fun `applyStaged routes the payload to the provider registered for the kind`() = runTest(dispatcher) {
        val contacts = FakeApply(ItemKind.CONTACTS_VCF)
        val vm = viewModel(registryFactory = { ApplyProviderRegistry(listOf(contacts)) })
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()
        vm.onConfirm() // enter Transferring so progress rows exist

        val outcome = vm.applyStaged(manifest.items[0], ByteArrayInputStream(ByteArray(0)))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(contacts.calls).isEqualTo(1)
        val item = (vm.state.value as ReceiverState.Transferring).items.first { it.itemId == 1 }
        assertThat(item.phase).isEqualTo(ItemPhase.DONE)
        assertThat(item.detail).isEqualTo("applied 1, skipped 0")
    }

    @Test
    fun `applyStaged on an unregistered kind is UNKNOWN_KIND and marks the item FAILED`() = runTest(dispatcher) {
        val vm = viewModel() // empty registry
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()
        vm.onConfirm()

        val outcome = vm.applyStaged(manifest.items[1], ByteArrayInputStream(ByteArray(0)))

        assertThat(outcome.status).isEqualTo(ItemStatus.UNKNOWN_KIND)
        val item = (vm.state.value as ReceiverState.Transferring).items.first { it.itemId == 2 }
        assertThat(item.phase).isEqualTo(ItemPhase.FAILED)
    }

    @Test
    fun `install actions surfaced by the inventory provider reach the UI flow`() = runTest(dispatcher) {
        var sink: ((List<InstallAction>) -> Unit)? = null
        val vm = viewModel(registryFactory = { onActions ->
            sink = onActions
            ApplyProviderRegistry(emptyList())
        })

        val action = InstallAction("org.fossify.gallery", "Gallery", InstallStore.FDROID,
            "https://f-droid.org/packages/org.fossify.gallery")
        sink?.invoke(listOf(action))

        assertThat(vm.installActions.value).containsExactly(action)
    }
}
