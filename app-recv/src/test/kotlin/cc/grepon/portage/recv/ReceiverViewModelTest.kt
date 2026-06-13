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
import cc.grepon.portage.providers.bluetooth.BtPairingRoster
import cc.grepon.portage.providers.bluetooth.BondedDevice
import cc.grepon.portage.providers.bluetooth.BtPairingsApplyProvider
import cc.grepon.portage.providers.bluetooth.BtRosterCodec
import cc.grepon.portage.providers.bluetooth.RePairEntry
import cc.grepon.portage.providers.inventory.AppInventoryApplyProvider
import cc.grepon.portage.providers.inventory.AppInventoryExportProvider
import cc.grepon.portage.providers.inventory.AppRecord
import cc.grepon.portage.providers.inventory.InstallAction
import cc.grepon.portage.providers.inventory.InstallStore
import cc.grepon.portage.providers.inventory.InventorySource
import cc.grepon.portage.providers.relay.AppBackupRelayApplyProvider
import cc.grepon.portage.providers.relay.RelayApp
import cc.grepon.portage.providers.relay.RelayCodec
import cc.grepon.portage.providers.relay.RelayHeader
import cc.grepon.portage.providers.sms.SmsApplyProvider
import cc.grepon.portage.providers.sms.SmsRecord
import cc.grepon.portage.providers.sms.SmsRoleGateway
import cc.grepon.portage.providers.sms.SmsStore
import cc.grepon.portage.recv.sms.SmsRoleCoordinator
import cc.grepon.portage.recv.sms.SmsRoleStrand
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
import java.io.ByteArrayOutputStream
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

private class FakeSmsRoleCoordinator(
    private val acquireResult: Boolean,
    private val prior: String? = "com.example.messages",
    private val strand: SmsRoleStrand? = null,
) : SmsRoleCoordinator {
    var acquireCalls = 0
    val relinquished = mutableListOf<String?>()
    var roleRestoredCalls = 0
    override fun priorDefaultPackage(): String? = prior
    override suspend fun acquireRole(): Boolean {
        acquireCalls++
        return acquireResult
    }
    override suspend fun relinquishTo(priorPackage: String?) {
        relinquished += priorPackage
    }
    override fun currentStrand(): SmsRoleStrand? = strand
    override fun onRoleRestored() {
        roleRestoredCalls++
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

    private val smsBytes = """{"address":"+15551234567","body":"hi","dateMillis":1,"type":1}""".toByteArray()
    private val smsMeta =
        ItemMeta(3, ItemKind.SMS, smsBytes.size.toLong(), sha256(smsBytes), "Text messages", "History")

    private fun smsChannel() = FakeChannel(
        ProtocolMessage.Manifest(TransferManifest("old phone", listOf(smsMeta), smsBytes.size.toLong())),
        ProtocolMessage.ItemBegin(3, ItemKind.SMS, smsMeta.size, smsBytes.size),
        ProtocolMessage.ItemData(3, 0, smsBytes),
        ProtocolMessage.ItemEnd(3, smsMeta.sha256),
        ProtocolMessage.BatchEnd(listOf(3), "done"),
    )

    private fun smsViewModel(
        channel: SecureChannel,
        coordinator: SmsRoleCoordinator,
        sms: ApplyProvider,
    ) = ReceiverViewModel(
        pairingCodec = FakeCodec(),
        channelFactory = FakeFactory(channel),
        nowEpochSeconds = { 1_000 },
        appVersion = "test",
        osFingerprint = "test-fingerprint",
        stagingDir = tmp.root,
        smsRoleCoordinator = coordinator,
        applyRegistryFactory = ApplyRegistryFactory { _, _, _ -> ApplyProviderRegistry(listOf(sms)) },
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
        registryFactory: ApplyRegistryFactory =
            ApplyRegistryFactory { _, _, _ -> ApplyProviderRegistry(emptyList()) },
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
            ItemKind.WALLPAPER, ItemKind.SOUND_SELECTION, ItemKind.BLUETOOTH_DEVICES,
            ItemKind.APP_BACKUP_RELAY,
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
        val vm = viewModel(channel, registryFactory = ApplyRegistryFactory { _, _, _ -> ApplyProviderRegistry(listOf(contacts, calls)) })
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
        // A non-inventory transfer must leave installActions empty so the Done screen keeps
        // the original centered layout (code review 2026-06-11, MEDIUM: pin the branch).
        assertThat(done.installActions).isEmpty()
    }

    @Test
    fun `an item without a registered handler counts as skipped, not moved`() = runTest(dispatcher) {
        val contacts = FakeApply(ItemKind.CONTACTS_VCF)
        val vm = viewModel(happyChannel(), registryFactory = ApplyRegistryFactory { _, _, _ -> ApplyProviderRegistry(listOf(contacts)) })
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
        val vm = viewModel(registryFactory = ApplyRegistryFactory { _, _, _ -> ApplyProviderRegistry(listOf(contacts)) })
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
    fun `selecting SMS acquires the role, applies, then relinquishes to the prior holder`() = runTest(dispatcher) {
        val sms = FakeApply(ItemKind.SMS)
        val coordinator = FakeSmsRoleCoordinator(acquireResult = true)
        val vm = smsViewModel(smsChannel(), coordinator, sms)
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onToggle(3) // SMS is opt-in — not pre-checked
        vm.onConfirm()
        advanceUntilIdle()

        assertThat(coordinator.acquireCalls).isEqualTo(1)
        assertThat(coordinator.relinquished).containsExactly("com.example.messages")
        assertThat(sms.calls).isEqualTo(1)
        assertThat((vm.state.value as ReceiverState.Done).moved).isEqualTo(1)
    }

    @Test
    fun `a declined SMS role runs the transfer but never relinquishes — nothing was taken`() = runTest(dispatcher) {
        val coordinator = FakeSmsRoleCoordinator(acquireResult = false)
        val vm = smsViewModel(smsChannel(), coordinator, FakeApply(ItemKind.SMS))
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onToggle(3)
        vm.onConfirm()
        advanceUntilIdle()

        assertThat(coordinator.acquireCalls).isEqualTo(1)
        assertThat(coordinator.relinquished).isEmpty()
        assertThat(vm.state.value).isInstanceOf(ReceiverState.Done::class.java)
    }

    @Test
    fun `relinquish runs even when the transfer throws after the role was acquired`() = runTest(dispatcher) {
        val droppedChannel = FakeChannel(
            ProtocolMessage.Manifest(TransferManifest("old phone", listOf(smsMeta), smsBytes.size.toLong())),
            ProtocolMessage.ItemBegin(3, ItemKind.SMS, smsMeta.size, smsBytes.size),
            null, // connection lost mid-item → TransportException
        )
        val coordinator = FakeSmsRoleCoordinator(acquireResult = true)
        val vm = smsViewModel(droppedChannel, coordinator, FakeApply(ItemKind.SMS))
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onToggle(3)
        vm.onConfirm()
        advanceUntilIdle()

        assertThat(coordinator.relinquished).containsExactly("com.example.messages")
        assertThat(vm.state.value).isInstanceOf(ReceiverState.Failed::class.java)
    }

    @Test
    fun `a leftover default-SMS strand at startup surfaces a restore affordance`() = runTest(dispatcher) {
        // Process died (or the restore prompt was dismissed) with portage still the default SMS
        // app: the persistent backstop must surface it for a one-tap in-app restore.
        val coordinator = FakeSmsRoleCoordinator(
            acquireResult = true,
            strand = SmsRoleStrand("com.example.messages"),
        )
        val vm = smsViewModel(smsChannel(), coordinator, FakeApply(ItemKind.SMS))

        assertThat(vm.smsRoleStrand.value).isEqualTo(SmsRoleStrand("com.example.messages"))

        vm.restoreSmsRole()
        advanceUntilIdle()
        assertThat(coordinator.relinquished).containsExactly("com.example.messages")
    }

    @Test
    fun `no strand at startup means no affordance and the persistent marker is cleared`() = runTest(dispatcher) {
        val coordinator = FakeSmsRoleCoordinator(acquireResult = true, strand = null)
        val vm = smsViewModel(smsChannel(), coordinator, FakeApply(ItemKind.SMS))

        assertThat(vm.smsRoleStrand.value).isNull()
        assertThat(coordinator.roleRestoredCalls).isAtLeast(1) // ledger.disarm() ran during reconcile
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
        val vm = viewModel(registryFactory = ApplyRegistryFactory { _, _, _ ->
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
    fun `app inventory transfer surfaces the reinstall actions on the Done state`() = runTest(dispatcher) {
        val source = object : InventorySource {
            override fun installedUserApps() =
                listOf(AppRecord("org.fossify.gallery", 1, "org.fdroid.fdroid", "Gallery"))
            override fun installedPackageNames() = emptySet<String>()
        }
        val invBytes = ByteArrayOutputStream().also { AppInventoryExportProvider(source).exportTo(it) }.toByteArray()
        val invMeta = ItemMeta(5, ItemKind.APP_INVENTORY, invBytes.size.toLong(), sha256(invBytes), "App list", "Apps")
        val channel = FakeChannel(
            ProtocolMessage.Manifest(TransferManifest("old phone", listOf(invMeta), invBytes.size.toLong())),
            ProtocolMessage.ItemBegin(5, ItemKind.APP_INVENTORY, invMeta.size, invBytes.size),
            ProtocolMessage.ItemData(5, 0, invBytes),
            ProtocolMessage.ItemEnd(5, invMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(5), "done"),
        )
        val vm = ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channel),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test",
            stagingDir = tmp.root,
            applyRegistryFactory = ApplyRegistryFactory { onActions, _, _ ->
                ApplyProviderRegistry(listOf(AppInventoryApplyProvider(source, onActions)))
            },
        )
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()
        vm.onConfirm()
        advanceUntilIdle()

        val done = vm.state.value as ReceiverState.Done
        assertThat(done.moved).isEqualTo(1)
        assertThat(done.installActions.map { it.packageName }).containsExactly("org.fossify.gallery")
        assertThat(done.installActions.single().store).isEqualTo(InstallStore.FDROID)
    }

    @Test
    fun `install actions surfaced by the inventory provider reach the UI flow`() = runTest(dispatcher) {
        var sink: ((List<InstallAction>) -> Unit)? = null
        val vm = viewModel(registryFactory = ApplyRegistryFactory { onActions, _, _ ->
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

    @Test
    fun `bonded Bluetooth transfer surfaces the re-pair entries on the Done state`() = runTest(dispatcher) {
        val roster = BtPairingRoster(
            listOf(
                BondedDevice("AA:BB:CC:DD:EE:01", "WH-1000XM5", devType = 1, majorClass = 1024),
                BondedDevice("AA:BB:CC:DD:EE:02", "Pixel Watch", devType = 2, majorClass = 1792),
            ),
        )
        val btBytes = BtRosterCodec.encode(roster).toByteArray(Charsets.UTF_8)
        val btMeta = ItemMeta(6, ItemKind.BLUETOOTH_DEVICES, btBytes.size.toLong(), sha256(btBytes), "Paired Bluetooth devices", "Bluetooth")
        val channel = FakeChannel(
            ProtocolMessage.Manifest(TransferManifest("old phone", listOf(btMeta), btBytes.size.toLong())),
            ProtocolMessage.ItemBegin(6, ItemKind.BLUETOOTH_DEVICES, btMeta.size, btBytes.size),
            ProtocolMessage.ItemData(6, 0, btBytes),
            ProtocolMessage.ItemEnd(6, btMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(6), "done"),
        )
        val vm = ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channel),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test",
            stagingDir = tmp.root,
            applyRegistryFactory = ApplyRegistryFactory { _, onRepairEntries, _ ->
                ApplyProviderRegistry(listOf(BtPairingsApplyProvider(onRepairEntries)))
            },
        )
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()
        vm.onConfirm()
        advanceUntilIdle()

        val done = vm.state.value as ReceiverState.Done
        assertThat(done.moved).isEqualTo(1)
        assertThat(done.repairEntries.map { it.address })
            .containsExactly("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02").inOrder()
        assertThat(done.repairEntries.first().name).isEqualTo("WH-1000XM5")
    }

    @Test
    fun `re-pair entries surfaced by the bluetooth provider reach the UI flow`() = runTest(dispatcher) {
        var sink: ((List<RePairEntry>) -> Unit)? = null
        val vm = viewModel(registryFactory = ApplyRegistryFactory { _, onRepairEntries, _ ->
            sink = onRepairEntries
            ApplyProviderRegistry(emptyList())
        })

        val entry = RePairEntry("AA:BB:CC:DD:EE:01", "WH-1000XM5", devType = 1, majorClass = 1024)
        sink?.invoke(listOf(entry))

        assertThat(vm.repairEntries.value).containsExactly(entry)
    }

    @Test
    fun `an app-backup relay transfer surfaces a restore prompt on the Done state`() = runTest(dispatcher) {
        // Build a real relay item (header + opaque bytes) and run it end-to-end through the receiver.
        val opaque = "OPAQUE-SIGNAL-CIPHERTEXT".toByteArray()
        val relayBytes = ByteArrayOutputStream().use { out ->
            RelayCodec.writeTo(
                out,
                RelayHeader(
                    RelayApp.SIGNAL, "org.thoughtcrime.securesms", "signal.backup",
                    "Open Signal and restore from backup.", opaque.size.toLong(),
                ),
                opaque,
            )
            out.toByteArray()
        }
        val relayMeta = ItemMeta(
            6, ItemKind.APP_BACKUP_RELAY, relayBytes.size.toLong(), sha256(relayBytes),
            "signal.backup", "App backups",
        )
        val handedOff = mutableListOf<ByteArray>()
        val channel = FakeChannel(
            ProtocolMessage.Manifest(TransferManifest("old phone", listOf(relayMeta), relayBytes.size.toLong())),
            ProtocolMessage.ItemBegin(6, ItemKind.APP_BACKUP_RELAY, relayMeta.size, relayBytes.size),
            ProtocolMessage.ItemData(6, 0, relayBytes),
            ProtocolMessage.ItemEnd(6, relayMeta.sha256),
            ProtocolMessage.BatchEnd(listOf(6), "done"),
        )
        val vm = ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channel),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test",
            stagingDir = tmp.root,
            applyRegistryFactory = ApplyRegistryFactory { _, _, onRelayPrompt ->
                ApplyProviderRegistry(
                    listOf(
                        AppBackupRelayApplyProvider(
                            onPrompt = onRelayPrompt,
                            handoff = { _, source, declaredLen, _ ->
                                val buf = ByteArrayOutputStream()
                                RelayCodec.streamBlob(source, buf, declaredLen)
                                handedOff += buf.toByteArray()
                                true
                            },
                        ),
                    ),
                )
            },
        )
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()
        vm.onConfirm()
        advanceUntilIdle()

        val done = vm.state.value as ReceiverState.Done
        assertThat(done.moved).isEqualTo(1)
        assertThat(done.relayPrompts).hasSize(1)
        assertThat(done.relayPrompts.single().app).isEqualTo(RelayApp.SIGNAL)
        assertThat(done.relayPrompts.single().targetPackage).isEqualTo("org.thoughtcrime.securesms")
        // The OPAQUE bytes were handed off byte-exact — never imported, never interpreted.
        assertThat(handedOff.single()).isEqualTo(opaque)
    }
}
