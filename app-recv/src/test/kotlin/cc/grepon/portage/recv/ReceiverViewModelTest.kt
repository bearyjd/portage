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
import cc.grepon.portage.providers.sms.SmsApplyProvider
import cc.grepon.portage.providers.sms.SmsRecord
import cc.grepon.portage.providers.sms.SmsRoleGateway
import cc.grepon.portage.providers.sms.SmsStore
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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

private class FakeChannel(vararg incoming: ProtocolMessage?) : SecureChannel {
    private val queue = ArrayDeque(incoming.toList())
    val sent = mutableListOf<ProtocolMessage>()
    var closed = false

    override suspend fun send(message: ProtocolMessage) { sent += message }
    override suspend fun receive(): ProtocolMessage? =
        if (queue.isEmpty()) null else queue.removeFirst()
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

    @get:Rule
    val tmp = TemporaryFolder()

    private val contactsBytes = "vcardvcard".toByteArray()
    private val callsBytes = "callscalls".toByteArray()

    private val contactsMeta =
        ItemMeta(1, ItemKind.CONTACTS_VCF, contactsBytes.size.toLong(), sha256(contactsBytes), "Contacts", "People")
    private val callsMeta =
        ItemMeta(2, ItemKind.CALL_LOG, callsBytes.size.toLong(), sha256(callsBytes), "Call history", "History")

    private val manifest = TransferManifest(
        senderName = "old phone",
        items = listOf(contactsMeta, callsMeta),
        totalBytes = contactsBytes.size.toLong() + callsBytes.size,
    )

    private fun itemFrames(meta: ItemMeta, bytes: ByteArray): List<ProtocolMessage> = listOf(
        ProtocolMessage.ItemBegin(meta.itemId, meta.kind, meta.size, bytes.size),
        ProtocolMessage.ItemData(meta.itemId, 0, bytes),
        ProtocolMessage.ItemEnd(meta.itemId, sha256(bytes)),
    )

    /** Manifest + the full live item stream for both items. */
    private fun happyChannel() = FakeChannel(
        *(
            listOf<ProtocolMessage>(ProtocolMessage.Manifest(manifest)) +
                itemFrames(contactsMeta, contactsBytes) +
                itemFrames(callsMeta, callsBytes) +
                ProtocolMessage.BatchEnd(listOf(1, 2), "done")
            ).toTypedArray(),
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
        channel: SecureChannel = happyChannel(),
        registryFactory: ((List<InstallAction>) -> Unit) -> ApplyProviderRegistry =
            { ApplyProviderRegistry(emptyList()) },
    ) = ReceiverViewModel(
        pairingCodec = FakeCodec(),
        channelFactory = FakeFactory(channel),
        nowEpochSeconds = { 1_000 },
        appVersion = "test",
        osFingerprint = "test-fingerprint",
        stagingDir = tmp.root,
        applyRegistryFactory = registryFactory,
    )

    @Test
    fun `scan to reviewing happy path sends HELLO and builds the checklist`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(channel)

        vm.startScanning()
        vm.onQrScanned("good-qr")
        assertThat(vm.state.value).isEqualTo(ReceiverState.Pairing)
        advanceUntilIdle()

        val reviewing = vm.state.value as ReceiverState.Reviewing
        assertThat(reviewing.senderName).isEqualTo("old phone")
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.Hello>()).hasSize(1)
        // Kinds the sender did not advertise surface as disabled rows, not gaps.
        assertThat(reviewing.absentKinds).containsExactly(
            ItemKind.CALENDAR_ICS, ItemKind.SMS, ItemKind.APP_INVENTORY, ItemKind.SETTINGS,
        ).inOrder()
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
    fun `confirm streams, applies, and reports real done counts`() = runTest(dispatcher) {
        val contacts = FakeApply(ItemKind.CONTACTS_VCF)
        val calls = FakeApply(ItemKind.CALL_LOG)
        val channel = happyChannel()
        val vm = viewModel(channel, registryFactory = { ApplyProviderRegistry(listOf(contacts, calls)) })
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onConfirm()
        val transferring = vm.state.value as ReceiverState.Transferring
        assertThat(transferring.items.map { it.displayName })
            .containsExactly("Contacts", "Call history").inOrder()
        assertThat(transferring.items.map { it.phase })
            .containsExactly(ItemPhase.PENDING, ItemPhase.PENDING)
        advanceUntilIdle()

        assertThat(channel.sent.filterIsInstance<ProtocolMessage.Select>().single().want)
            .containsExactly(1, 2).inOrder()
        assertThat(contacts.calls).isEqualTo(1)
        assertThat(calls.calls).isEqualTo(1)
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.BatchAck>().single().results.map { it.status })
            .containsExactly(ItemStatus.OK, ItemStatus.OK)

        val done = vm.state.value as ReceiverState.Done
        assertThat(done.moved).isEqualTo(2)
        assertThat(done.skipped).isEqualTo(0)
        assertThat(channel.closed).isTrue()
    }

    @Test
    fun `an item without a registered handler counts as skipped, not moved`() = runTest(dispatcher) {
        val contacts = FakeApply(ItemKind.CONTACTS_VCF)
        val vm = viewModel(happyChannel(), registryFactory = { ApplyProviderRegistry(listOf(contacts)) })
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onConfirm()
        advanceUntilIdle()

        val done = vm.state.value as ReceiverState.Done
        assertThat(done.moved).isEqualTo(1) // contacts applied; call log UNKNOWN_KIND
        assertThat(done.skipped).isEqualTo(1)
    }

    @Test
    fun `a dropped connection mid-transfer is an error state, not a hang`() = runTest(dispatcher) {
        val channel = FakeChannel(
            ProtocolMessage.Manifest(manifest),
            ProtocolMessage.ItemBegin(1, ItemKind.CONTACTS_VCF, contactsMeta.size, 8),
            ProtocolMessage.ItemData(1, 0, contactsBytes.copyOf(4)),
            null, // connection dies
        )
        val vm = viewModel(channel)
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onConfirm()
        advanceUntilIdle()

        val failed = vm.state.value as ReceiverState.Failed
        assertThat(failed.reason).contains("connection lost")
        assertThat(tmp.root.listFiles().orEmpty()).isEmpty() // partial staging swept
    }

    @Test
    fun `applyStaged routes the payload to the provider registered for the kind`() = runTest(dispatcher) {
        val contacts = FakeApply(ItemKind.CONTACTS_VCF)
        val vm = viewModel(registryFactory = { ApplyProviderRegistry(listOf(contacts)) })
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        val outcome = vm.applyStaged(contactsMeta, ByteArrayInputStream(ByteArray(0)))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(contacts.calls).isEqualTo(1)
    }

    @Test
    fun `applyStaged on an unregistered kind is UNKNOWN_KIND`() = runTest(dispatcher) {
        val vm = viewModel() // empty registry
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        val outcome = vm.applyStaged(callsMeta, ByteArrayInputStream(ByteArray(0)))

        assertThat(outcome.status).isEqualTo(ItemStatus.UNKNOWN_KIND)
    }

    @Test
    fun `a registered SMS provider is inert end-to-end while the role is not held`() = runTest(dispatcher) {
        // The recv manifest deliberately declares no SMS role components yet (its own
        // comment: "treat SMS as its own mini-project"). Registering SmsApplyProvider is
        // safe ONLY because the role gate holds end-to-end — this test pins that.
        val store = object : SmsStore {
            var inserts = 0
            override fun count() = 0
            override fun readAll() = emptyList<SmsRecord>()
            override fun insert(record: SmsRecord): Boolean {
                inserts++
                return true
            }
        }
        val noRole = object : SmsRoleGateway {
            override fun isSelfDefault() = false
            override fun currentDefault(): String? = "com.example.messages"
            override fun launchRestore(priorHolderPackage: String?) = true
        }
        val vm = viewModel(registryFactory = {
            ApplyProviderRegistry(listOf(SmsApplyProvider(store, noRole)))
        })
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        val smsMeta = ItemMeta(9, ItemKind.SMS, 10, "h9", "Text messages", "History")
        val outcome = vm.applyStaged(smsMeta, ByteArrayInputStream(ByteArray(0)))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.inserts).isEqualTo(0)
    }

    @Test
    fun `install actions surfaced by the inventory provider reach the UI flow`() = runTest(dispatcher) {
        var sink: ((List<InstallAction>) -> Unit)? = null
        val vm = viewModel(registryFactory = { onActions ->
            sink = onActions
            ApplyProviderRegistry(emptyList())
        })

        val action = InstallAction(
            "org.fossify.gallery", "Gallery", InstallStore.FDROID,
            "https://f-droid.org/packages/org.fossify.gallery",
        )
        sink?.invoke(listOf(action))

        assertThat(vm.installActions.value).containsExactly(action)
    }
}
