package com.ventouxlabs.portage.recv

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.lineage.CheckpointKey
import com.ventouxlabs.portage.lineage.LineageRepository
import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.PairingMode
import com.ventouxlabs.portage.model.PairingPayload
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.model.ReceiptPhase
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ApplyProviderRegistry
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.transport.PairingCodec
import com.ventouxlabs.portage.transport.SecureChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReceiverLineageLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val bytes = "restored contacts".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val item = testItemMeta(1, ItemKind.CONTACTS_VCF, bytes.size.toLong(), hash, "Contacts", "People")
    private val lineageA = "a".repeat(32)
    private val lineageB = "b".repeat(32)
    private val payload = PairingPayload(
        psk = ByteArray(32), sid = ByteArray(16), ip = listOf("192.168.1.2"), port = 7777,
        expiresAtEpochSeconds = 9_999_999_999,
    )
    private val codec = object : PairingCodec {
        override fun encode(payload: PairingPayload) = "unused"
        override fun decode(qr: String, nowEpochSeconds: Long) = Result.success(
            payload.copy(mode = if (qr == "resume") PairingMode.RESUME else PairingMode.NEW),
        )
    }

    private open class Channel(messages: List<ProtocolMessage>) : SecureChannel {
        val incoming = ArrayDeque(messages)
        val sent = mutableListOf<ProtocolMessage>()
        var closed = false
        override suspend fun send(message: ProtocolMessage) { sent += message }
        override suspend fun receive(): ProtocolMessage? = incoming.removeFirstOrNull()
        override fun close() { closed = true }
    }

    private fun manifest(id: String) = testManifest("old phone", listOf(item), item.size, id)
    private fun bootstrap(id: String) = listOf(
        ProtocolMessage.LineageInit(id, ByteArray(32) { 7 }), ProtocolMessage.Manifest(manifest(id)),
    )
    private fun frames(sendBytes: Boolean = true) = buildList {
        add(ProtocolMessage.ItemBegin(item.itemId, item.kind, item.size, 128))
        if (sendBytes) add(ProtocolMessage.ItemData(item.itemId, 0, bytes))
        add(ProtocolMessage.ItemEnd(item.itemId, item.sha256))
        add(ProtocolMessage.BatchEnd(listOf(item.itemId), "done"))
    }

    private fun vm(
        channels: List<Channel>,
        store: LineageRepository,
        registry: ApplyRegistryFactory = ApplyRegistryFactory { ApplyProviderRegistry(emptyList()) },
    ): ReceiverViewModel {
        val pending = ArrayDeque(channels)
        return ReceiverViewModel(
            pairingCodec = codec,
            channelFactory = object : SecureChannel.Factory {
                override suspend fun connectAsReceiver(payload: PairingPayload) = pending.removeFirst()
                override suspend fun connectAsReceiver(
                    payload: PairingPayload, resumeCredential: ByteArray?, lineageId: String?,
                ): SecureChannel {
                    if (payload.mode == PairingMode.RESUME) {
                        assertThat(resumeCredential).isEqualTo(ByteArray(32) { 7 })
                        assertThat(lineageId).isEqualTo(lineageA)
                    }
                    return pending.removeFirst()
                }
                override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel = error("receiver only")
            },
            appVersion = "test", osFingerprint = "test", stagingDir = store.stagingDir,
            lineageRepository = store, applyRegistryFactory = registry, ioDispatcher = dispatcher,
        )
    }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `cancelled pairing must finish before a replacement channel can be accepted`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            val finishConnect = CompletableDeferred<Unit>()
            val a = Channel(bootstrap(lineageA))
            val b = Channel(bootstrap(lineageB))
            var attempts = 0
            val vm = ReceiverViewModel(
                pairingCodec = codec,
                channelFactory = object : SecureChannel.Factory {
                    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel {
                        attempts++
                        return if (attempts == 1) withContext(NonCancellable) { finishConnect.await(); a } else b
                    }
                    override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel = error("receiver only")
                },
                appVersion = "test", osFingerprint = "test", stagingDir = store.stagingDir,
                lineageRepository = store, ioDispatcher = dispatcher,
            )
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            vm.reset(); runCurrent()
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            assertThat(attempts).isEqualTo(1)
            finishConnect.complete(Unit); runCurrent()
            assertThat(a.closed).isTrue()
            assertThat(a.sent).isEmpty()
            assertThat(store.active()).isNull()
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            assertThat(attempts).isEqualTo(2)
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Reviewing::class.java)
            assertThat(store.active()?.id).isEqualTo(lineageB)
            assertThat(b.closed).isFalse()
        }
    }

    @Test fun `reset waits for suspended provider A before lineage B can begin`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            val a = Channel(bootstrap(lineageA) + frames())
            val b = Channel(bootstrap(lineageB) + frames())
            val finishA = CompletableDeferred<Unit>()
            val finishB = CompletableDeferred<Unit>()
            var calls = 0
            val vm = vm(listOf(a, b), store, ApplyRegistryFactory { sinks ->
                ApplyProviderRegistry(listOf(object : ApplyProvider {
                    override val kind = item.kind
                    override suspend fun apply(source: InputStream): ApplyOutcome {
                        calls++
                        if (calls == 1) withContext(NonCancellable) {
                            finishA.await()
                            sinks.onRepairEntries(listOf(RePairEntry("AA:BB:CC:DD:EE:FF", "old device", 1, 1)))
                        } else finishB.await()
                        return ApplyOutcome(ItemStatus.OK)
                    }
                }))
            })
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            vm.onConfirm(); runCurrent()
            assertThat(store.checkpoint(CheckpointKey.from(lineageA, item))?.phase).isEqualTo(ReceiptPhase.APPLYING)
            vm.reset(); runCurrent()
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            assertThat(vm.state.value).isEqualTo(ReceiverState.Idle)
            assertThat(b.sent).isEmpty()
            advanceTimeBy(5_000); runCurrent()
            vm.startScanning()
            assertThat(vm.state.value).isEqualTo(ReceiverState.Idle)
            finishA.complete(Unit); runCurrent()
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            vm.onConfirm(); runCurrent()
            assertThat(store.active()?.id).isEqualTo(lineageB)
            assertThat(store.checkpoint(CheckpointKey.from(lineageB, item))?.phase).isEqualTo(ReceiptPhase.APPLYING)
            assertThat(b.closed).isFalse()
            assertThat(vm.repairEntries.value).isEmpty()
            finishB.complete(Unit); advanceUntilIdle()
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Done::class.java)
            assertThat(store.checkpoint(CheckpointKey.from(lineageB, item))?.phase).isEqualTo(ReceiptPhase.APPLIED_DURABLE)
        }
    }

    @Test fun `receiver Done can reconnect when sender loses final batch acknowledgement`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            // A successful local send can still be lost before the peer consumes the frame.
            val first = object : Channel(bootstrap(lineageA) + frames()) {
                override suspend fun send(message: ProtocolMessage) {
                    if (message !is ProtocolMessage.BatchAck) super.send(message)
                }
            }
            val resumed = Channel(listOf(
                ProtocolMessage.LineageResume(lineageA), ProtocolMessage.Manifest(manifest(lineageA)),
            ) + frames(sendBytes = false))
            var applies = 0
            val vm = vm(listOf(first, resumed), store, ApplyRegistryFactory {
                ApplyProviderRegistry(listOf(object : ApplyProvider {
                    override val kind = item.kind
                    override suspend fun apply(source: InputStream): ApplyOutcome {
                        applies++
                        assertThat(source.readBytes()).isEqualTo(bytes)
                        return ApplyOutcome(ItemStatus.OK)
                    }
                }))
            })
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            vm.onConfirm(); advanceUntilIdle()
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Done::class.java)
            assertThat(first.sent.filterIsInstance<ProtocolMessage.BatchAck>()).isEmpty()
            vm.resumeSavedMove()
            assertThat(vm.state.value).isEqualTo(ReceiverState.Scanning)
            assertThat(store.active()?.id).isEqualTo(lineageA)
            vm.onQrScanned("resume"); runCurrent()
            vm.onConfirm(); advanceUntilIdle()
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Done::class.java)
            assertThat(resumed.sent.filterIsInstance<ProtocolMessage.Select>().single().resume.single().offset).isEqualTo(item.size)
            assertThat(resumed.sent.last()).isInstanceOf(ProtocolMessage.BatchAck::class.java)
            assertThat(applies).isEqualTo(2) // replay still requires apply without provider-specific proof
            vm.reset(); advanceUntilIdle()
            assertThat(store.active()).isNull()
            assertThat(store.tombstones().single().reason).isEqualTo("finished")
        }
    }

    @Test fun `peer cancel removes Confirm before its acknowledgement can suspend`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            val ack = CompletableDeferred<Unit>()
            val channel = object : Channel(bootstrap(lineageA) + ProtocolMessage.Cancel(lineageA)) {
                override suspend fun send(message: ProtocolMessage) {
                    if (message is ProtocolMessage.CancelAck) {
                        assertThat(store.active()).isNull()
                        ack.await()
                    }
                    super.send(message)
                }
            }
            val vm = vm(listOf(channel), store)
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Failed::class.java)
            assertThat(runCatching { vm.onConfirm() }.isSuccess).isTrue()
            assertThat(channel.sent.filterIsInstance<ProtocolMessage.Select>()).isEmpty()
            ack.complete(Unit); advanceUntilIdle()
            assertThat(channel.sent.last()).isEqualTo(ProtocolMessage.CancelAck(lineageA))
        }
    }

    @Test fun `cancel after initial lineage acknowledgement revokes before manifest`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            val channel = object : Channel(listOf(
                ProtocolMessage.LineageInit(lineageA, ByteArray(32) { 7 }), ProtocolMessage.Cancel(lineageA),
            )) {
                override suspend fun send(message: ProtocolMessage) {
                    if (message is ProtocolMessage.CancelAck) assertThat(store.active()).isNull()
                    super.send(message)
                }
            }
            val vm = vm(listOf(channel), store)
            vm.startScanning(); vm.onQrScanned("new"); advanceUntilIdle()
            assertThat(store.tombstones().single().lineageId).isEqualTo(lineageA)
            assertThat(channel.sent.last()).isEqualTo(ProtocolMessage.CancelAck(lineageA))
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Failed::class.java)
        }
    }

    @Test fun `lost initial acknowledgement can retry the same initial secret before a manifest was saved`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            val first = Channel(listOf(ProtocolMessage.LineageInit(lineageA, ByteArray(32) { 7 })))
            val retry = Channel(bootstrap(lineageA))
            val vm = vm(listOf(first, retry), store)
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Failed::class.java)
            assertThat(store.active()?.manifest).isNull()
            vm.startScanning(); vm.onQrScanned("new"); runCurrent()
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Reviewing::class.java)
            assertThat(store.credentialForResume()).isEqualTo(ByteArray(32) { 7 })
            assertThat(retry.sent.filterIsInstance<ProtocolMessage.LineageAck>()).hasSize(1)
        }
    }

    @Test fun `authenticated resume cancellation before lineage resume revokes the saved move`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            store.acceptInitial(lineageA, ByteArray(32) { 7 })
            store.saveManifest(manifest(lineageA))
            val channel = Channel(listOf(ProtocolMessage.Cancel(lineageA)))
            val vm = vm(listOf(channel), store)
            vm.startScanning(); vm.onQrScanned("resume"); advanceUntilIdle()
            assertThat(store.active()).isNull()
            assertThat(channel.sent.last()).isEqualTo(ProtocolMessage.CancelAck(lineageA))
            assertThat(store.tombstones().single().lineageId).isEqualTo(lineageA)
        }
    }

    @Test fun `cancel before initial lineage adoption acknowledges absence without saving supplied identity`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            val channel = Channel(listOf(ProtocolMessage.Cancel(lineageA)))
            val vm = vm(listOf(channel), store)
            vm.startScanning(); vm.onQrScanned("new"); advanceUntilIdle()
            assertThat(store.active()).isNull()
            assertThat(store.tombstones()).isEmpty()
            assertThat(channel.sent.last()).isEqualTo(ProtocolMessage.CancelAck(lineageA))
            assertThat(channel.closed).isTrue()
        }
    }

    @Test fun `corrupt saved move startup reports bounded generic error without exposing snapshot`() = runTest(dispatcher) {
        val directory = tmp.newFolder()
        val secret = "secret-contact-and-credential-value"
        File(directory, "lineage.json").writeText("{broken:$secret}")
        val vm = ReceiverViewModel(
            stagingDir = File(directory, "staging"), appVersion = "test", osFingerprint = "test",
            lineageRepositoryFactory = { LineageRepository(directory) }, ioDispatcher = dispatcher,
        )
        val failure = vm.state.value as ReceiverState.Failed
        assertThat(failure.reason.length).isLessThan(180)
        assertThat(failure.reason).doesNotContain(secret)
        vm.startScanning(); vm.reset()
        assertThat(vm.state.value).isEqualTo(failure)
        assertThat(File(directory, "lineage.json").readText()).contains(secret)
    }
}
