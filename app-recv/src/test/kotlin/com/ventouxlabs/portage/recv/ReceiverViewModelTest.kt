/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.PairingPayload
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.model.TransferManifest
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ApplyProviderRegistry
import com.ventouxlabs.portage.providers.bluetooth.BtPairingRoster
import com.ventouxlabs.portage.providers.bluetooth.BondedDevice
import com.ventouxlabs.portage.providers.bluetooth.BtPairingsApplyProvider
import com.ventouxlabs.portage.providers.bluetooth.BtRosterCodec
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.providers.inventory.AppInventoryApplyProvider
import com.ventouxlabs.portage.providers.inventory.AppInventoryExportProvider
import com.ventouxlabs.portage.providers.inventory.AppRecord
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.inventory.InstallStore
import com.ventouxlabs.portage.providers.inventory.InventorySource
import com.ventouxlabs.portage.providers.relay.AppBackupRelayApplyProvider
import com.ventouxlabs.portage.providers.relay.RelayApp
import com.ventouxlabs.portage.providers.relay.RelayCodec
import com.ventouxlabs.portage.providers.relay.RelayHeader
import com.ventouxlabs.portage.providers.sms.SmsApplyProvider
import com.ventouxlabs.portage.providers.sms.SmsRecord
import com.ventouxlabs.portage.providers.sms.SmsRoleGateway
import com.ventouxlabs.portage.providers.sms.SmsStore
import com.ventouxlabs.portage.recv.sms.SmsRoleCoordinator
import com.ventouxlabs.portage.recv.sms.SmsRoleStrand
import com.ventouxlabs.portage.recv.transfer.ItemStreamReceiver
import com.ventouxlabs.portage.transport.PairingCodec
import com.ventouxlabs.portage.transport.SecureChannel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
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
import kotlin.coroutines.ContinuationInterceptor

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

    /**
     * Dispatcher the stream loop itself ran on, sampled on every read. This fake does NOT hop
     * contexts (the real NoiseSecureChannel wraps its socket in Dispatchers.IO, but that is the
     * transport's business), so whatever this records is the context ItemStreamReceiver.run was
     * driven on. Used by `the data phase runs off the main dispatcher` to pin run() DIRECTLY
     * rather than inferring it from where an apply happened to land.
     */
    val receiveInterceptors = mutableListOf<ContinuationInterceptor?>()

    override suspend fun send(message: ProtocolMessage) { sent += message }
    override suspend fun receive(): ProtocolMessage? {
        receiveInterceptors += currentCoroutineContext()[ContinuationInterceptor]
        return if (queue.isEmpty()) null else queue.removeFirst()
    }
    override fun close() { closed = true }
}

private class FakeFactory(private val channel: SecureChannel) : SecureChannel.Factory {
    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = channel
    override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel =
        throw UnsupportedOperationException("receiver tests never listen")
}

/**
 * Delivers [incoming] in order, then SUSPENDS forever — an authenticated sender that goes quiet
 * mid-stream. Models the slow-drip the aggregate data-phase cap is meant to bound (#53 MEDIUM-1):
 * without the cap the launched coroutine never completes and the transfer hangs. The suspension is
 * `awaitCancellation()` (cooperative), so this exercises the cap's timeout → null → Failed path on
 * virtual time; in production `receive()` is a blocking native socket read whose escape hatch is the
 * per-read `soTimeout` (covered by the transport's own tests), not coroutine cancellation.
 */
private class StallingChannel(vararg incoming: ProtocolMessage) : SecureChannel {
    private val queue = ArrayDeque(incoming.toList())
    val sent = mutableListOf<ProtocolMessage>()
    var closed = false
    override suspend fun send(message: ProtocolMessage) { sent += message }
    override suspend fun receive(): ProtocolMessage? =
        if (queue.isNotEmpty()) queue.removeFirst() else awaitCancellation()
    override fun close() { closed = true }
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

/**
 * Records which dispatcher the role handoff ran on. Separate from [FakeSmsRoleCoordinator] so the
 * existing role-behaviour tests keep their simpler fake; this one exists only to pin the #158
 * withContext placement (see `the SMS role handoff stays on the main dispatcher …`).
 */
private class RecordingSmsRoleCoordinator : SmsRoleCoordinator {
    var acquireRanOn: ContinuationInterceptor? = null
    var relinquishRanOn: ContinuationInterceptor? = null
    override fun priorDefaultPackage(): String? = "com.example.messages"
    override suspend fun acquireRole(): Boolean {
        acquireRanOn = currentCoroutineContext()[ContinuationInterceptor]
        return true
    }
    override suspend fun relinquishTo(priorPackage: String?) {
        relinquishRanOn = currentCoroutineContext()[ContinuationInterceptor]
    }
    override fun currentStrand(): SmsRoleStrand? = null
    override fun onRoleRestored() = Unit
}

/** Records start/stop calls so tests can pin the keep-alive lifecycle around the data phase (#85). */
private class FakeKeepAlive : TransferKeepAlive {
    var starts = 0
    var stops = 0
    override fun start() { starts++ }
    override fun stop() { stops++ }
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
        applyRegistryFactory = ApplyRegistryFactory { _ -> ApplyProviderRegistry(listOf(sms)) },
        ioDispatcher = dispatcher,
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
            ApplyRegistryFactory { _ -> ApplyProviderRegistry(emptyList()) },
        keepAlive: TransferKeepAlive = TransferKeepAlive.NoOp,
    ) = ReceiverViewModel(
        pairingCodec = FakeCodec(),
        channelFactory = FakeFactory(channel),
        nowEpochSeconds = { 1_000 },
        appVersion = "test",
        osFingerprint = "test-fingerprint",
        stagingDir = tmp.root,
        applyRegistryFactory = registryFactory,
        transferKeepAlive = keepAlive,
        // Production runs the data phase on Dispatchers.IO (#158). Tests hand it the same
        // StandardTestDispatcher the rest of the suite runs on, so the item stream stays on
        // virtual time — otherwise the deadline tests would race real wall-clock.
        ioDispatcher = dispatcher,
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
            ItemKind.CALENDAR_ICS, ItemKind.SMS, ItemKind.MMS, ItemKind.APP_INVENTORY,
            ItemKind.SETTINGS, ItemKind.WALLPAPER, ItemKind.SOUND_FILE,
            ItemKind.SOUND_SELECTION, ItemKind.BLUETOOTH_DEVICES, ItemKind.APP_BACKUP_RELAY,
            ItemKind.USER_FILE,
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
    fun `item progress surfaces the live received-byte ticks per row`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onConfirm() // state == Transferring, items PENDING; transfer coroutine queued (not advanced)
        // Feed the per-chunk stream event the receiver already emits (v1 dropped it) and assert the
        // row now carries it — the byte-surfacing this change exists to add.
        vm.onReceiveEvent(ItemStreamReceiver.Event.ItemProgressed(contactsMeta.itemId, 5L, contactsMeta.size))
        val mid = vm.state.value as ReceiverState.Transferring
        val row = mid.items.first { it.itemId == contactsMeta.itemId }
        assertThat(row.bytesReceived).isEqualTo(5L)
        assertThat(row.totalBytes).isEqualTo(contactsMeta.size)
        advanceUntilIdle() // drain the queued transfer so runTest sees no dangling job
    }

    @Test
    fun `confirm streams, applies, and reports real done counts`() = runTest(dispatcher) {
        val contacts = FakeApply(ItemKind.CONTACTS_VCF)
        val calls = FakeApply(ItemKind.CALL_LOG)
        val channel = happyChannel()
        val vm = viewModel(channel, registryFactory = ApplyRegistryFactory { _ -> ApplyProviderRegistry(listOf(contacts, calls)) })
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onConfirm()
        val transferring = vm.state.value as ReceiverState.Transferring
        assertThat(transferring.items.map { it.displayName })
            .containsExactly("Contacts", "Call history").inOrder()
        assertThat(transferring.items.map { it.phase })
            .containsExactly(ItemPhase.PENDING, ItemPhase.PENDING)
        // Per-item byte progress (portage U2): totalBytes is seeded from the manifest up front;
        // bytesReceived then ticks up live via the ItemProgressed stream event (surfaced per row).
        assertThat(transferring.items.map { it.totalBytes })
            .containsExactly(contactsMeta.size, callsMeta.size).inOrder()
        assertThat(transferring.items.map { it.bytesReceived }).containsExactly(0L, 0L)
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

    /**
     * #158 regression guard. The data phase must run on the injected IO dispatcher, never on
     * viewModelScope's Main. The socket was never the issue (NoiseSecureChannel already wraps
     * send/receive in Dispatchers.IO); what ran on Main was everything between the frames — the
     * per-chunk sha256, the staging writes, and every apply — which put Main on the ack loop's
     * critical path. On hardware an 859-event calendar stalled it until the sender hung up.
     *
     * Every other test here hands the VM the SAME dispatcher it installs as Main, which keeps
     * virtual time simple but makes them all blind to this: delete the `withContext(ioDispatcher)`
     * in onConfirm() and they stay green (verified by mutation). So this one passes a SECOND
     * dispatcher — a distinct object sharing the one [kotlinx.coroutines.test.TestCoroutineScheduler],
     * so the clock stays virtual.
     *
     * It pins the STREAM LOOP, not just the applies. Asserting only on the apply's dispatcher was
     * not enough: narrowing the withContext to wrap just `applyRegistry.apply(...)` — which would
     * put the sha256, the staging writes and the ITEM_ACK sends back on Main, most of the original
     * stall — still passed. So the fake channel samples its own context on every read, and the
     * assertion covers both.
     */
    @Test
    fun `the data phase runs off the main dispatcher`() = runTest(dispatcher) {
        val io = StandardTestDispatcher(dispatcher.scheduler)
        var applyRanOn: ContinuationInterceptor? = null
        val recordingContacts = object : ApplyProvider {
            override val kind = ItemKind.CONTACTS_VCF
            override suspend fun apply(source: InputStream): ApplyOutcome {
                applyRanOn = currentCoroutineContext()[ContinuationInterceptor]
                return ApplyOutcome(ItemStatus.OK, "applied 1, skipped 0")
            }
        }
        val channel = happyChannel()
        val vm = ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channel),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test-fingerprint",
            stagingDir = tmp.root,
            applyRegistryFactory = ApplyRegistryFactory { _ ->
                ApplyProviderRegistry(listOf(recordingContacts, FakeApply(ItemKind.CALL_LOG)))
            },
            ioDispatcher = io,
        )
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()
        // Drop the HELLO/manifest reads — that handshake legitimately runs on Main. Only the reads
        // after onConfirm() are the data phase.
        channel.receiveInterceptors.clear()

        vm.onConfirm()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(ReceiverState.Done::class.java)
        assertThat(applyRanOn).isSameInstanceAs(io)
        // The stream loop itself, not just the applies — and it must have actually read something.
        assertThat(channel.receiveInterceptors).isNotEmpty()
        assertThat(channel.receiveInterceptors.toSet()).containsExactly(io)
    }

    /**
     * The #158 withContext sits INSIDE withSmsRoleIfNeeded on purpose: acquireRole/relinquishTo
     * drive an interactive system dialog through ActivityResultLauncher, which is not thread-safe
     * and must stay on the caller's main context. That path is hardware-verified (#61).
     *
     * Nothing stopped a later "cleanup" from hoisting the withContext out to wrap the role handoff
     * too — the placement was the single most fragile decision in the change and had no test. This
     * pins both halves at once: the role handoff on Main, the apply on IO.
     */
    @Test
    fun `the SMS role handoff stays on the main dispatcher while the stream runs off it`() = runTest(dispatcher) {
        val io = StandardTestDispatcher(dispatcher.scheduler)
        var applyRanOn: ContinuationInterceptor? = null
        val coordinator = RecordingSmsRoleCoordinator()
        val sms = object : ApplyProvider {
            override val kind = ItemKind.SMS
            override suspend fun apply(source: InputStream): ApplyOutcome {
                applyRanOn = currentCoroutineContext()[ContinuationInterceptor]
                return ApplyOutcome(ItemStatus.OK, "applied 1, skipped 0")
            }
        }
        val vm = ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(smsChannel()),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test-fingerprint",
            stagingDir = tmp.root,
            smsRoleCoordinator = coordinator,
            applyRegistryFactory = ApplyRegistryFactory { _ -> ApplyProviderRegistry(listOf(sms)) },
            ioDispatcher = io,
        )
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()
        vm.onToggle(3) // SMS is opt-in — not pre-checked

        vm.onConfirm()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(ReceiverState.Done::class.java)
        // The dialog-driving calls stayed on Main. (The interceptor is Dispatchers.Main itself —
        // setMain installs `dispatcher` as its DELEGATE, so the context still reports the main
        // dispatcher. Comparing against io is the load-bearing half: it is what goes wrong if the
        // withContext is ever hoisted to wrap withSmsRoleIfNeeded.)
        assertThat(coordinator.acquireRanOn).isSameInstanceAs(Dispatchers.Main)
        assertThat(coordinator.relinquishRanOn).isSameInstanceAs(Dispatchers.Main)
        assertThat(coordinator.acquireRanOn).isNotSameInstanceAs(io)
        assertThat(coordinator.relinquishRanOn).isNotSameInstanceAs(io)
        // ...while the stream they wrap did not.
        assertThat(applyRanOn).isSameInstanceAs(io)
    }

    @Test
    fun `a failed item reaches Done with its display name, not its raw item id`() = runTest(dispatcher) {
        val contacts = FakeApply(ItemKind.CONTACTS_VCF)
        val calls = FakeApply(ItemKind.CALL_LOG, ApplyOutcome(ItemStatus.WRITE_ERROR, "no space left"))
        val channel = happyChannel()
        val vm = viewModel(
            channel,
            registryFactory = ApplyRegistryFactory { _ -> ApplyProviderRegistry(listOf(contacts, calls)) },
        )
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onConfirm()
        advanceUntilIdle()

        val done = vm.state.value as ReceiverState.Done
        assertThat(done.moved).isEqualTo(1)
        assertThat(done.skipped).isEqualTo(1)
        // The name is only reachable from the Transferring state, so doneStateFrom MUST read it
        // before the transition to Done. Read afterwards, nameById is empty and every failed row
        // silently degrades to "#2" — a user staring at "#2 NOT SAVED" learns nothing about which
        // of their things did not make it over.
        val failed = done.failedItems.single()
        assertThat(failed.itemId).isEqualTo(2)
        assertThat(failed.displayName).isEqualTo("Call history")
        assertThat(failed.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(failed.detail).isEqualTo("no space left")
    }

    // ---- transfer keep-alive (#85): foreground-service lifecycle around the item stream ----

    @Test
    fun `keep-alive starts before the item stream and stops once on success`() = runTest(dispatcher) {
        val keepAlive = FakeKeepAlive()
        val vm = viewModel(happyChannel(), keepAlive = keepAlive)
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onConfirm()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(ReceiverState.Done::class.java)
        assertThat(keepAlive.starts).isEqualTo(1)
        assertThat(keepAlive.stops).isEqualTo(1)
    }

    @Test
    fun `keep-alive is untouched before the user confirms`() = runTest(dispatcher) {
        // Pairing + manifest read happen WITHOUT the keep-alive — it scopes to the item stream only.
        val keepAlive = FakeKeepAlive()
        val vm = viewModel(happyChannel(), keepAlive = keepAlive)
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(ReceiverState.Reviewing::class.java)
        assertThat(keepAlive.starts).isEqualTo(0)
    }

    @Test
    fun `keep-alive is released when the data phase times out`() = runTest(dispatcher) {
        // Manifest arrives (→ Reviewing), then the sender goes quiet during the item stream.
        val keepAlive = FakeKeepAlive()
        val vm = ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(StallingChannel(ProtocolMessage.Manifest(manifest))),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test-fingerprint",
            stagingDir = tmp.root,
            dataPhaseTimeoutMs = 1_000L,
            transferKeepAlive = keepAlive,
            ioDispatcher = dispatcher,
        )
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()

        vm.onConfirm()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(ReceiverState.Failed::class.java)
        // Started for the item stream, then released in the finally despite the timeout.
        assertThat(keepAlive.starts).isEqualTo(1)
        assertThat(keepAlive.stops).isEqualTo(1)
    }

    @Test
    fun `an item without a registered handler counts as skipped, not moved`() = runTest(dispatcher) {
        val contacts = FakeApply(ItemKind.CONTACTS_VCF)
        val vm = viewModel(happyChannel(), registryFactory = ApplyRegistryFactory { _ -> ApplyProviderRegistry(listOf(contacts)) })
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
    fun `a stalled data phase fails on the aggregate cap, not a hang`() = runTest(dispatcher) {
        // An authenticated sender that delivers the manifest then goes quiet — slow-drip (#53
        // MEDIUM-1). The per-read socket budget bounds one frame; only the aggregate cap fails the
        // WHOLE phase. With a 1 s budget on virtual time, advanceUntilIdle drives the timeout.
        val channel = StallingChannel(ProtocolMessage.Manifest(manifest))
        val vm = ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channel),
            nowEpochSeconds = { 1_000 },
            dataPhaseTimeoutMs = 1_000L,
            appVersion = "test",
            osFingerprint = "test-fingerprint",
            stagingDir = tmp.root,
            ioDispatcher = dispatcher,
        )
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advanceUntilIdle()
        assertThat(vm.state.value).isInstanceOf(ReceiverState.Reviewing::class.java)

        vm.onConfirm()
        advanceUntilIdle() // virtual time advances past the 1 s budget → timeout fires

        val failed = vm.state.value as ReceiverState.Failed
        assertThat(failed.reason).contains("timed out")
        assertThat(channel.closed).isTrue()
        assertThat(tmp.root.listFiles().orEmpty()).isEmpty() // block cancelled → staging swept
    }

    @Test
    fun `applyStaged routes the payload to the provider registered for the kind`() = runTest(dispatcher) {
        val contacts = FakeApply(ItemKind.CONTACTS_VCF)
        val vm = viewModel(registryFactory = ApplyRegistryFactory { _ -> ApplyProviderRegistry(listOf(contacts)) })
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
        val vm = viewModel(registryFactory = ApplyRegistryFactory { _ ->
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
            applyRegistryFactory = ApplyRegistryFactory { sinks ->
                ApplyProviderRegistry(listOf(AppInventoryApplyProvider(source, sinks.onInstallActions)))
            },
            ioDispatcher = dispatcher,
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
        val vm = viewModel(registryFactory = ApplyRegistryFactory { sinks ->
            sink = sinks.onInstallActions
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
            applyRegistryFactory = ApplyRegistryFactory { sinks ->
                ApplyProviderRegistry(listOf(BtPairingsApplyProvider(sinks.onRepairEntries)))
            },
            ioDispatcher = dispatcher,
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
        val vm = viewModel(registryFactory = ApplyRegistryFactory { sinks ->
            sink = sinks.onRepairEntries
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
            applyRegistryFactory = ApplyRegistryFactory { sinks ->
                ApplyProviderRegistry(
                    listOf(
                        AppBackupRelayApplyProvider(
                            onPrompt = sinks.onRelayPrompt,
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
            ioDispatcher = dispatcher,
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
