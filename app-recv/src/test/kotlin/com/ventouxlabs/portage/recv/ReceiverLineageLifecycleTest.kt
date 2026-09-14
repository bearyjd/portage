package com.ventouxlabs.portage.recv

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.lineage.CheckpointKey
import com.ventouxlabs.portage.lineage.LineageRepository
import com.ventouxlabs.portage.lineage.LineageBusyException
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
import com.ventouxlabs.portage.transport.TransportException
import com.ventouxlabs.portage.recv.ui.failureRecoveryMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReceiverLineageLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()
    // Constructing the test fixture must not consult Main before @Before installs it.
    private val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())
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

    @Test fun `reset revokes saved lineage immediately while a suspended provider still fences the next move`() = runTest(dispatcher) {
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
            assertThat(finishA.isCompleted).isFalse()
            assertThat(store.active()).isNull()
            assertThat(runCatching { store.credentialForResume() }.isFailure).isTrue()
            assertThat(store.tombstones().single().lineageId).isEqualTo(lineageA)
            assertThat(store.stagingDir.listFiles().orEmpty()).isEmpty()
            val snapshot = File(store.stagingDir.parentFile, "lineage.json").readText()
            assertThat(snapshot).contains("\"active\":null")
            assertThat(snapshot).doesNotContain("07".repeat(32))
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

    @Test fun `failed final batch acknowledgement offers resume and preserves applied lineage until explicit cancellation`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            fun failingAck(messages: List<ProtocolMessage>) = object : Channel(messages) {
                override suspend fun send(message: ProtocolMessage) {
                    if (message is ProtocolMessage.BatchAck) throw TransportException("final acknowledgement connection lost")
                    super.send(message)
                }
            }
            val initial = failingAck(bootstrap(lineageA) + frames())
            val resumed = failingAck(listOf(
                ProtocolMessage.LineageResume(lineageA), ProtocolMessage.Manifest(manifest(lineageA)),
            ) + frames(sendBytes = false))
            var applies = 0
            val receiver = vm(listOf(initial, resumed), store, ApplyRegistryFactory {
                ApplyProviderRegistry(listOf(object : ApplyProvider {
                    override val kind = item.kind
                    override suspend fun apply(source: InputStream): ApplyOutcome {
                        assertThat(source.readBytes()).isEqualTo(bytes)
                        applies++
                        return ApplyOutcome(ItemStatus.OK)
                    }
                }))
            })
            receiver.startScanning(); receiver.onQrScanned("new"); runCurrent()
            receiver.onConfirm(); advanceUntilIdle()
            val failure = receiver.state.value as ReceiverState.Failed
            assertThat(failure.canResumeSavedMove).isTrue()
            assertThat(failure.mayHaveAppliedChanges).isTrue()
            assertThat(failureRecoveryMessage(failure)).contains("may already have been applied")
            assertThat(failureRecoveryMessage(failure)).doesNotContain("Nothing was changed")
            val key = CheckpointKey.from(lineageA, item)
            assertThat(store.checkpoint(key)?.phase).isEqualTo(ReceiptPhase.APPLIED_DURABLE)
            assertThat(applies).isEqualTo(1)
            val snapshot = File(store.stagingDir.parentFile, "lineage.json")
            val savedBeforeResume = snapshot.readBytes()

            receiver.resumeSavedMove()
            assertThat(receiver.state.value).isEqualTo(ReceiverState.Scanning)
            assertThat(store.active()?.id).isEqualTo(lineageA)
            assertThat(store.credentialForResume()).isEqualTo(ByteArray(32) { 7 })
            assertThat(store.checkpoint(key)?.phase).isEqualTo(ReceiptPhase.APPLIED_DURABLE)
            assertThat(store.verifiedStaged(key)?.readBytes()).isEqualTo(bytes)
            assertThat(snapshot.readBytes()).isEqualTo(savedBeforeResume)
            assertThat(store.tombstones()).isEmpty()

            receiver.onQrScanned("resume"); runCurrent()
            receiver.onConfirm(); advanceUntilIdle()
            assertThat((receiver.state.value as ReceiverState.Failed).canResumeSavedMove).isTrue()
            assertThat(applies).isEqualTo(2)
            assertThat(resumed.sent.filterIsInstance<ProtocolMessage.Select>().single().resume.single().offset).isEqualTo(item.size)
            receiver.reset(); advanceUntilIdle() // only the explicit Cancel saved move action deletes
            assertThat(store.active()).isNull()
            assertThat(runCatching { store.credentialForResume() }.isFailure).isTrue()
            assertThat(store.stagingDir.listFiles().orEmpty()).isEmpty()
            assertThat(store.tombstones().single().reason).isEqualTo("cancelled")
        }
    }

    @Test fun `failure before lineage establishment offers pairing retry without a saved resume action`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            val receiver = vm(listOf(Channel(emptyList())), store)
            receiver.startScanning(); receiver.onQrScanned("new"); advanceUntilIdle()
            val failure = receiver.state.value as ReceiverState.Failed
            assertThat(failure.canResumeSavedMove).isFalse()
            assertThat(failure.mayHaveAppliedChanges).isFalse()
            assertThat(failureRecoveryMessage(failure)).doesNotContain("may already have been applied")
            receiver.startScanning()
            assertThat(receiver.state.value).isEqualTo(ReceiverState.Scanning)
            assertThat(store.active()).isNull()
            assertThat(store.tombstones()).isEmpty()
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

    @Test fun `new pairing cannot cancel an existing move before proving the saved bootstrap credential`() = runTest(dispatcher) {
        LineageRepository(tmp.newFolder()).use { store ->
            store.acceptInitial(lineageA, ByteArray(32) { 7 })
            val original = store.active()
            val channel = Channel(listOf(ProtocolMessage.Cancel(lineageA)))
            val vm = vm(listOf(channel), store)
            vm.startScanning(); vm.onQrScanned("new"); advanceUntilIdle()
            assertThat(store.active()).isEqualTo(original)
            assertThat(store.credentialForResume()).isEqualTo(ByteArray(32) { 7 })
            assertThat(store.tombstones()).isEmpty()
            assertThat(channel.sent.filterIsInstance<ProtocolMessage.CancelAck>()).isEmpty()
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Failed::class.java)
        }
    }

    @Test fun `new ViewModel retries a busy saved store after the previous owner unwinds`() = runTest(dispatcher) {
        val directory = tmp.newFolder()
        val repository = LineageRepository(directory)
        val finishProvider = CompletableDeferred<Unit>()
        val first = vm(listOf(Channel(bootstrap(lineageA) + frames())), repository, ApplyRegistryFactory {
            ApplyProviderRegistry(listOf(object : ApplyProvider {
                override val kind = item.kind
                override suspend fun apply(source: InputStream): ApplyOutcome = withContext(NonCancellable) {
                    finishProvider.await()
                    ApplyOutcome(ItemStatus.OK)
                }
            }))
        })
        val oldOwner = ViewModelStore().apply { put("receiver", first) }
        first.startScanning(); first.onQrScanned("new"); runCurrent()
        first.onConfirm(); runCurrent()
        oldOwner.clear(); runCurrent()

        var reopened: LineageRepository? = null
        val second = ReceiverViewModel(
            appVersion = "test", osFingerprint = "test", stagingDir = repository.stagingDir,
            lineageRepositoryFactory = { LineageRepository(directory).also { reopened = it } },
            ioDispatcher = dispatcher,
        )
        val newOwner = ViewModelStore().apply { put("receiver", second) }
        runCurrent()
        assertThat(second.state.value).isEqualTo(ReceiverState.OpeningSavedMove)
        assertThat(reopened).isNull()
        finishProvider.complete(Unit); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertThat(second.state.value).isEqualTo(ReceiverState.Idle)
        assertThat(reopened?.active()?.id).isEqualTo(lineageA)
        assertThat(reopened?.checkpoint(CheckpointKey.from(lineageA, item))?.phase)
            .isEqualTo(ReceiptPhase.UNKNOWN_INTERRUPTED)
        second.startScanning()
        assertThat(second.state.value).isEqualTo(ReceiverState.Scanning)
        newOwner.clear(); runCurrent()
    }

    @Test fun `reset returns while resume validation is paused and cancels before another file read`() = runBlocking {
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val teardownComplete = CountDownLatch(1)
        val pauseReads = AtomicBoolean(false)
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(ui)
        var mainThread: Thread? = null
        var teardownThread: Thread? = null
        var runningReceiver: ReceiverViewModel? = null
        val directory = tmp.newFolder()
        val repository = LineageRepository(directory, beforeStagedRead = {
            if (pauseReads.get()) {
                paused.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "validation was not released" }
            }
        })
        try {
            repository.acceptInitial(lineageA, ByteArray(32) { 7 })
            repository.saveManifest(manifest(lineageA))
            repository.stagingDir.mkdirs()
            val staged = File(repository.stagingDir, "${item.occurrenceId}.bin").apply { writeBytes(bytes) }
            repository.transition(CheckpointKey.from(lineageA, item), ReceiptPhase.PREPARED,
                ReceiptPhase.RECEIVED_VERIFIED, stagedFile = staged)
            val channel = Channel(listOf(
                ProtocolMessage.LineageResume(lineageA), ProtocolMessage.Manifest(manifest(lineageA)),
            ) + frames(sendBytes = false))
            val receiver = ReceiverViewModel(
                pairingCodec = codec,
                channelFactory = object : SecureChannel.Factory {
                    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = channel
                    override suspend fun connectAsReceiver(payload: PairingPayload, resumeCredential: ByteArray?, lineageId: String?): SecureChannel = channel
                    override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel = error("receiver only")
                },
                appVersion = "test", osFingerprint = "test", stagingDir = repository.stagingDir,
                lineageRepository = repository, ioDispatcher = io,
                abandonSessions = { teardownThread = Thread.currentThread(); teardownComplete.countDown() },
            ).also { runningReceiver = it }
            withContext(ui) {
                mainThread = Thread.currentThread()
                receiver.startScanning()
                receiver.onQrScanned("resume")
            }
            withTimeout(5_000) { receiver.state.first { it is ReceiverState.Reviewing } }
            pauseReads.set(true)
            withContext(ui) { receiver.onConfirm() }
            assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue()
            // The validation worker remains blocked: reset must not wait for its store/read.
            val resetStarted = System.nanoTime()
            withContext(ui) { receiver.reset() }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - resetStarted)).isLessThan(500)
            assertThat(receiver.state.value).isEqualTo(ReceiverState.Idle)
            assertThat(release.count).isEqualTo(1)
            assertThat(teardownComplete.count).isEqualTo(1)
            release.countDown()
            assertThat(teardownComplete.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(teardownThread).isNotEqualTo(mainThread)
            assertThat(repository.active()).isNull()
            assertThat(channel.sent.filterIsInstance<ProtocolMessage.Select>()).isEmpty()
            // abandonSessions runs before the teardown job returns to Main. Wait for the
            // actual session fence to clear before replacing Main or closing its dispatcher.
            withTimeout(5_000) {
                while (true) {
                    val stopped = withContext(ui) {
                        receiver.startScanning()
                        receiver.state.value is ReceiverState.Scanning
                    }
                    if (stopped) break
                    delay(1)
                }
            }
        } finally {
            release.countDown()
            withContext(ui) {
                runningReceiver?.let { ViewModelStore().apply { put("receiver", it) }.clear() }
            }
            // onCleared joins every owner before releasing the writer lock. Reacquiring it
            // proves that no ViewModel work can dispatch to Main after this test resets Main.
            withTimeout(5_000) {
                while (true) {
                    try {
                        withContext(io) { LineageRepository(directory).close() }
                        break
                    } catch (_: LineageBusyException) {
                        delay(1)
                    }
                }
            }
            Dispatchers.setMain(dispatcher)
            ui.close()
            io.close()
            repository.close()
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

    @Test fun `corrupt saved move startup reports bounded generic error without exposing snapshot`() {
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
