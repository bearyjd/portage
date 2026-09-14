/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send

import androidx.lifecycle.ViewModelStore
import com.ventouxlabs.portage.lineage.LineageBusyException
import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemResult
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.PairingPayload
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.model.ReceiptPhase
import com.ventouxlabs.portage.model.PairingMode
import com.ventouxlabs.portage.lineage.LineageRepository
import com.ventouxlabs.portage.lineage.CredentialState
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.apk.InstalledApkFile
import com.ventouxlabs.portage.providers.apk.InstalledApp
import com.ventouxlabs.portage.providers.apk.InstalledAppSource
import com.ventouxlabs.portage.providers.inventory.AppRecord
import com.ventouxlabs.portage.providers.inventory.InventorySource
import com.ventouxlabs.portage.providers.relay.RelayApp
import com.ventouxlabs.portage.send.relay.RelayFile
import com.ventouxlabs.portage.send.relay.RelayRestoreNotes
import com.ventouxlabs.portage.send.userfile.PickedUserFile
import com.ventouxlabs.portage.providers.userfile.UserFileHeader
import com.ventouxlabs.portage.transport.PairingCodecImpl
import com.ventouxlabs.portage.transport.SecureChannel
import com.ventouxlabs.portage.transport.TransportException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
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
    var afterSend: ((ProtocolMessage) -> Unit)? = null
    var cancelReply: ProtocolMessage? = null
    override suspend fun send(message: ProtocolMessage) {
        yield()
        sent += message
        if (message is ProtocolMessage.LineageInit) queue.addFirst(ProtocolMessage.LineageAck(message.lineageId))
        if (message is ProtocolMessage.LineageResume) queue.addFirst(ProtocolMessage.LineageAck(message.lineageId))
        if (message is ProtocolMessage.Cancel) cancelReply?.let { queue.addFirst(it) }
        afterSend?.invoke(message)
    }
    override suspend fun receive(): ProtocolMessage? {
        yield()
        val message = if (queue.isEmpty()) null else queue.removeFirst()
        // These UI-flow fixtures express verdicts by integer index; attach the exact
        // advertised occurrence and receipt phase like a protocol-v6 peer.
        fun identified(result: ItemResult): ItemResult {
            val manifest = sent.filterIsInstance<ProtocolMessage.Manifest>().last().manifest
            return result.copy(occurrenceId = manifest.items.single { it.itemId == result.itemId }.occurrenceId)
        }
        return when (message) {
            is ProtocolMessage.ItemAck -> ProtocolMessage.ItemAck(identified(message.result).let {
                if (it.status == ItemStatus.OK) it.copy(phase = ReceiptPhase.RECEIVED_VERIFIED) else it
            })
            is ProtocolMessage.BatchAck -> ProtocolMessage.BatchAck(message.results.map(::identified))
            else -> message
        }
    }
    override fun close() { closed = true }
}

/**
 * Delivers [incoming] in order, then SUSPENDS forever — an authenticated receiver that completes
 * the handshake then goes quiet. Models the slow-drip the aggregate data-phase cap bounds (#53
 * MEDIUM-1): without the cap the launched coroutine never completes and the sender hangs. The
 * suspension is `awaitCancellation()` (cooperative), so this exercises the cap's timeout → null →
 * Failed path on virtual time; in production `receive()` is a blocking native socket read whose
 * escape hatch is the per-read `soTimeout` (covered by the transport's own tests), not cancellation.
 */
private class StallingChannel(vararg incoming: ProtocolMessage) : SecureChannel {
    private val queue = ArrayDeque(incoming.toList())
    val sent = mutableListOf<ProtocolMessage>()
    var closed = false
    override suspend fun send(message: ProtocolMessage) {
        sent += message
        if (message is ProtocolMessage.LineageInit) queue.addFirst(ProtocolMessage.LineageAck(message.lineageId))
    }
    override suspend fun receive(): ProtocolMessage? =
        if (queue.isNotEmpty()) queue.removeFirst() else awaitCancellation()
    override fun close() { closed = true }
}

/** Fake inventory seam reporting a fixed installed-package set for relay detection. */
private class FakeInventorySource(private val packages: Set<String>) : InventorySource {
    override fun installedUserApps(): List<AppRecord> = emptyList()
    override fun installedPackageNames(): Set<String> = packages
}

/** Fake installed-app seam reporting a fixed app list for the "apps to carry" selection (ADR-006 1b). */
private class FakeInstalledAppSource(private val apps: List<InstalledApp>) : InstalledAppSource {
    override fun installedUserApps(): List<InstalledApp> = apps
}

/** A single-base on-disk app fixture: a real base.apk so the default opener can read it at staging. */
private fun onDiskApp(dir: java.io.File, packageName: String, label: String, size: Int = 16): InstalledApp {
    val base = java.io.File(dir, "$packageName-base.apk").apply { writeBytes(ByteArray(size) { it.toByte() }) }
    return InstalledApp(
        packageName = packageName,
        label = label,
        versionCode = 1L,
        files = listOf(InstalledApkFile("base.apk", base.absolutePath, base.length())),
    )
}

private class FakeFactory(
    private val channel: SecureChannel? = null,
    private val acceptError: Throwable? = null,
) : SecureChannel.Factory {
    var acceptedPayload: PairingPayload? = null
    var resumeCredential: ByteArray? = null
    var resumedLineageId: String? = null
    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel =
        throw UnsupportedOperationException("sender tests never dial")
    override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel {
        acceptedPayload = payload
        yield() // the real listener parks here awaiting the handshake
        acceptError?.let { throw it }
        return checkNotNull(channel)
    }
    override suspend fun acceptAsSender(payload: PairingPayload, resumeCredential: ByteArray?, lineageId: String?): SecureChannel {
        this.resumeCredential = resumeCredential?.copyOf()
        resumedLineageId = lineageId
        return acceptAsSender(payload)
    }
}

/** Records start/stop calls so tests can pin the keep-alive lifecycle around the data phase (#85). */
private class FakeKeepAlive : TransferKeepAlive {
    var starts = 0
    var stops = 0
    override fun start() { starts++ }
    override fun stop() { stops++ }
}

/** Deterministically holds dispatched IO work until a lifecycle event has cancelled its caller. */
private class PausableDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    private val queued = ArrayDeque<Pair<CoroutineContext, Runnable>>()
    private var paused = false

    @Synchronized fun pause() {
        paused = true
    }

    fun resume() {
        val pending = synchronized(this) {
            paused = false
            buildList {
                while (queued.isNotEmpty()) add(queued.removeFirst())
            }
        }
        pending.forEach { (context, block) -> delegate.dispatch(context, block) }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val held = synchronized(this) {
            if (paused) queued.addLast(context to block)
            paused
        }
        if (!held) delegate.dispatch(context, block)
    }
}

private class TaggedKeepAlive(
    private val tag: String,
    private val events: MutableList<String>,
) : TransferKeepAlive {
    override fun start() {
        events += "start-$tag"
    }

    override fun stop() {
        events += "stop-$tag"
    }
}

/** Protocol-layer fixture: a fresh receiver cannot complete the proof-of-possession handshake. */
private class PossessionFactory(
    private val receiver: LineageRepository,
    private val channel: SecureChannel,
) : SecureChannel.Factory {
    var payload: PairingPayload? = null
    override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = error("sender only")
    override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel = error("credential overload required")
    override suspend fun acceptAsSender(payload: PairingPayload, resumeCredential: ByteArray?, lineageId: String?): SecureChannel {
        this.payload = payload
        if (payload.mode != PairingMode.RESUME || receiver.active()?.id != lineageId || resumeCredential == null) {
            throw TransportException("resume proof unavailable")
        }
        val expected = receiver.credentialForResume()
        try {
            if (!java.security.MessageDigest.isEqual(expected, resumeCredential)) throw TransportException("resume proof rejected")
        } finally { expected.fill(0) }
        return channel
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
        installedAppSource: InstalledAppSource? = null,
        keepAlive: TransferKeepAlive = TransferKeepAlive.NoOp,
        repository: LineageRepository? = null,
        transferIoDispatcher: CoroutineDispatcher = dispatcher,
        repositoryFactory: (() -> LineageRepository)? = null,
    ): SenderViewModel {
        val vm = SenderViewModel(
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
            installedAppSource = installedAppSource,
            // Resolve relay picks on the SAME test dispatcher so advanceUntilIdle() drives the off-main
            // resolution deterministically (in production this is Dispatchers.IO, off the UI thread).
            relayResolveDispatcher = dispatcher,
            transferKeepAlive = keepAlive,
            lineageRepository = repository,
            transferIoDispatcher = transferIoDispatcher,
            lineageRepositoryFactory = repositoryFactory ?: {
                LineageRepository(File(tmp.root, "lineage"), random = SecureRandom())
            },
        )
        // Most legacy tests exercise transfer behavior, not cold-open scheduling. Preserve their
        // ready precondition; dedicated tests below keep the initial dispatcher paused.
        if (repository == null && repositoryFactory == null) dispatcher.scheduler.runCurrent()
        return vm
    }

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

    private fun userFile(
        pickId: Long = 20L,
        bytes: ByteArray = "document".toByteArray(),
        byteLength: Long = bytes.size.toLong(),
        releaseGrant: () -> Unit = {},
    ) = PickedUserFile(
        pickId = pickId,
        displayName = "notes.txt",
        mimeType = "text/plain",
        byteLength = byteLength,
        openStream = { java.io.ByteArrayInputStream(bytes) },
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
    fun `retryable receipt status survives recreation while oversize remains terminal`() = runTest(dispatcher) {
        listOf(
            ItemStatus.WRITE_ERROR to true,
            ItemStatus.HASH_MISMATCH to true,
            ItemStatus.OVERSIZE to false,
        ).forEach { (status, retryable) ->
            val directory = tmp.newFolder()
            val repository = LineageRepository(directory)
            val result = ItemResult(1, status, "receiver detail")
            val channel = ScriptedChannel(
                ProtocolMessage.Hello("0.1.0", "recv"),
                ProtocolMessage.Select(want = listOf(1)),
                ProtocolMessage.ItemAck(result),
                ProtocolMessage.BatchAck(listOf(result)),
            )
            val original = viewModel(FakeFactory(channel), repository = repository)

            original.onStartTransfer()
            advanceUntilIdle()

            val done = original.state.value as SenderState.Done
            assertThat(done.canResume).isEqualTo(retryable)
            assertThat(done.retryableFailed).isEqualTo(if (retryable) 1 else 0)
            assertThat(done.failed).isEqualTo(if (retryable) 0 else 1)
            val active = checkNotNull(repository.active())
            val checkpoint = repository.checkpoint(
                com.ventouxlabs.portage.lineage.CheckpointKey.from(
                    active.id,
                    checkNotNull(active.manifest).items.single(),
                ),
            )
            assertThat(checkpoint?.phase).isEqualTo(ReceiptPhase.FAILED)
            repository.close()

            val reopened = LineageRepository(directory)
            val restored = viewModel(FakeFactory(happyChannel()), providers = emptyList(), repository = reopened)
            val recovered = restored.state.value as SenderState.Failed
            assertThat(recovered.canResume).isEqualTo(retryable)
            assertThat(recovered.hasSavedMove).isTrue()
            if (retryable) {
                restored.onResumeTransfer()
                advanceUntilIdle()
                assertThat(restored.state.value).isEqualTo(SenderState.Done(1, 0))
            } else {
                // Even a direct method call cannot retry a terminal refusal.
                restored.onResumeTransfer()
                advanceUntilIdle()
                assertThat(restored.state.value).isEqualTo(recovered)
            }
            reopened.close()
        }
    }

    @Test
    fun `mixed resume normalizes every selected replay before receipts and leaves unselected truth`() = runTest(dispatcher) {
        val repository = LineageRepository(tmp.newFolder())
        val applied = ItemResult(1, ItemStatus.OK)
        val retryable = ItemResult(2, ItemStatus.WRITE_ERROR, "temporary write failure")
        val unselected = ItemResult(3, ItemStatus.OVERSIZE, "over receiver limit")
        val firstChannel = ScriptedChannel(
            ProtocolMessage.Hello("0.1.0", "recv"),
            ProtocolMessage.Select(want = listOf(1, 2, 3)),
            ProtocolMessage.ItemAck(applied),
            ProtocolMessage.ItemAck(retryable),
            ProtocolMessage.ItemAck(unselected),
            ProtocolMessage.BatchAck(listOf(applied, retryable, unselected)),
        )
        val replayChannel = ScriptedChannel(
            ProtocolMessage.Hello("0.1.0", "recv"),
            ProtocolMessage.Select(want = listOf(1, 2)),
            ProtocolMessage.ItemAck(ItemResult(1, ItemStatus.OK)),
            ProtocolMessage.ItemAck(ItemResult(2, ItemStatus.OK)),
            ProtocolMessage.BatchAck(listOf(
                ItemResult(1, ItemStatus.OK),
                ItemResult(2, ItemStatus.OK),
            )),
        )
        var normalizationObservedBeforeStream = false
        replayChannel.afterSend = { message ->
            if (message is ProtocolMessage.ItemBegin && !normalizationObservedBeforeStream) {
                val active = checkNotNull(repository.active())
                val phases = checkNotNull(active.manifest).items.associate { meta ->
                    meta.itemId to repository.checkpoint(
                        com.ventouxlabs.portage.lineage.CheckpointKey.from(active.id, meta),
                    )?.phase
                }
                assertThat(phases[1]).isEqualTo(ReceiptPhase.PREPARED)
                assertThat(phases[2]).isEqualTo(ReceiptPhase.PREPARED)
                assertThat(phases[3]).isEqualTo(ReceiptPhase.FAILED)
                normalizationObservedBeforeStream = true
            }
        }
        val channels = ArrayDeque(listOf<SecureChannel>(firstChannel, replayChannel))
        val factory = object : SecureChannel.Factory {
            override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = error("sender only")
            override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel = channels.removeFirst()
            override suspend fun acceptAsSender(
                payload: PairingPayload,
                resumeCredential: ByteArray?,
                lineageId: String?,
            ): SecureChannel = acceptAsSender(payload)
        }
        val vm = viewModel(
            factory,
            providers = listOf(
                BytesExport(ItemKind.CONTACTS_VCF, "vcard".toByteArray()),
                BytesExport(ItemKind.CALL_LOG, "calls".toByteArray()),
                BytesExport(ItemKind.SETTINGS, "settings".toByteArray()),
            ),
            repository = repository,
        )

        vm.onStartTransfer()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(
            SenderState.Done(sent = 1, failed = 1, retryableFailed = 1),
        )
        vm.onResumeTransfer()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(SenderState.Done(sent = 2, failed = 0))
        assertThat(normalizationObservedBeforeStream).isTrue()
        assertThat(replayChannel.sent.filterIsInstance<ProtocolMessage.ItemBegin>().map { it.itemId })
            .containsExactly(1, 2).inOrder()
        val active = checkNotNull(repository.active())
        val checkpoints = checkNotNull(active.manifest).items.associate { meta ->
            meta.itemId to repository.checkpoint(
                com.ventouxlabs.portage.lineage.CheckpointKey.from(active.id, meta),
            )
        }
        assertThat(checkpoints[1]?.phase).isEqualTo(ReceiptPhase.APPLIED_DURABLE)
        assertThat(checkpoints[2]?.phase).isEqualTo(ReceiptPhase.APPLIED_DURABLE)
        assertThat(checkpoints[3]?.phase).isEqualTo(ReceiptPhase.FAILED)
        assertThat(checkpoints[3]?.detail).contains(ItemStatus.OVERSIZE.name)
        repository.close()
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
    fun `a stalled data phase fails on the aggregate cap, not a hang`() = runTest(dispatcher) {
        // The receiver completes the handshake + HELLO, then never SELECTs — slow-drip (#53
        // MEDIUM-1). The per-read budget bounds one frame; only the aggregate cap fails the WHOLE
        // exchange. With a 1 s budget on virtual time, advanceUntilIdle drives the timeout.
        val channel = StallingChannel(ProtocolMessage.Hello("0.1.0", "recv"))
        val vm = SenderViewModel(
            providers = listOf(BytesExport(ItemKind.CONTACTS_VCF, "vcard".toByteArray())),
            stagingDir = tmp.root,
            senderName = "old phone",
            channelFactory = FakeFactory(channel),
            pairingCodec = PairingCodecImpl(),
            random = SecureRandom(),
            addressHints = { listOf("192.168.1.2") },
            portFinder = { 40123 },
            nowEpochSeconds = { 1_000 },
            dataPhaseTimeoutMs = 1_000L,
            relayResolveDispatcher = dispatcher,
        )

        runCurrent() // finish the asynchronous saved-move open before accepting user actions
        vm.onStartTransfer()
        advanceUntilIdle() // virtual time advances past the 1 s budget → timeout fires

        val failed = vm.state.value as SenderState.Failed
        assertThat(failed.reason).contains("timed out")
        // Pin the stall point: the engine got past HELLO into the data phase (sent the manifest) and
        // is parked at the wait-for-SELECT — i.e. the cap fired DURING the data phase, not earlier.
        assertThat(channel.sent.filterIsInstance<ProtocolMessage.Manifest>()).isNotEmpty()
        assertThat(channel.closed).isTrue()
        assertThat(File(tmp.root, "lineage/staging").listFiles().orEmpty()).isNotEmpty()
        assertThat(failed.canResume).isTrue()
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
        assertThat(File(tmp.root, "lineage/staging").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `constructor defers repository open and ignores actions until IO publishes it`() = runTest(dispatcher) {
        val directory = tmp.newFolder()
        val pausedIo = PausableDispatcher(dispatcher).also { it.pause() }
        var factoryCalls = 0
        val network = FakeFactory(happyChannel())
        val vm = SenderViewModel(
            providers = listOf(BytesExport(ItemKind.CONTACTS_VCF, "vcard".toByteArray())),
            stagingDir = tmp.root,
            senderName = "old phone",
            channelFactory = network,
            addressHints = { listOf("192.168.1.2") },
            portFinder = { 40123 },
            relayResolveDispatcher = dispatcher,
            transferIoDispatcher = pausedIo,
            lineageRepositoryFactory = {
                factoryCalls++
                LineageRepository(directory)
            },
        )
        val owner = ViewModelStore().apply { put("sender", vm) }

        assertThat(factoryCalls).isEqualTo(0)
        assertThat(vm.state.value).isEqualTo(SenderState.OpeningSavedMove)
        vm.onStartTransfer()
        runCurrent()
        assertThat(factoryCalls).isEqualTo(0)
        assertThat(network.acceptedPayload).isNull()

        pausedIo.resume()
        advanceUntilIdle()
        assertThat(factoryCalls).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(SenderState.Home)
        assertThat(network.acceptedPayload).isNull()

        owner.clear()
        advanceUntilIdle()
    }

    @Test
    fun `clear during repository factory handoff closes the newly published owner`() = runTest(dispatcher) {
        val directory = tmp.newFolder()
        lateinit var owner: ViewModelStore
        var factoryCalls = 0
        val vm = SenderViewModel(
            providers = emptyList(),
            stagingDir = tmp.root,
            senderName = "old phone",
            channelFactory = FakeFactory(happyChannel()),
            relayResolveDispatcher = dispatcher,
            transferIoDispatcher = dispatcher,
            lineageRepositoryFactory = {
                factoryCalls++
                LineageRepository(directory).also {
                    // Deterministically clear after the expensive constructor owns the lock but
                    // before withContext publishes its result back to the repository job.
                    owner.clear()
                }
            },
        )
        owner = ViewModelStore().apply { put("sender", vm) }

        assertThat(vm.state.value).isEqualTo(SenderState.OpeningSavedMove)
        assertThat(factoryCalls).isEqualTo(0)
        advanceUntilIdle()
        assertThat(factoryCalls).isEqualTo(1)

        // The independent onCleared join observes the published result and releases its lock.
        LineageRepository(directory).use { assertThat(it.active()).isNull() }
    }

    // ---- transfer keep-alive (#85): foreground-service lifecycle around the data phase ----

    @Test
    fun `corrupt saved move surfaces an error without replacement or lifecycle crash`() = runTest(dispatcher) {
        val saved = File(tmp.newFolder("lineage"), "lineage.json").apply { writeText("{broken") }
        val factory = FakeFactory(happyChannel())
        val vm = viewModel(factory)
        assertThat((vm.state.value as SenderState.Failed).reason).contains("could not be read")
        vm.onStartTransfer()
        advanceUntilIdle()
        assertThat(vm.state.value).isInstanceOf(SenderState.Failed::class.java)
        vm.reset()
        assertThat(vm.state.value).isInstanceOf(SenderState.Failed::class.java)
        vm.cancelTransfer()
        assertThat(vm.state.value).isInstanceOf(SenderState.Failed::class.java)
        assertThat(saved.readText()).isEqualTo("{broken")
        assertThat(factory.acceptedPayload).isNull()
        androidx.lifecycle.ViewModelStore().apply { put("sender", vm); clear() }
    }

    @Test
    fun `final acknowledgement timeout persists unknown instead of a completed move`() = runTest(dispatcher) {
        val repository = LineageRepository(tmp.newFolder())
        val incoming = Channel<ProtocolMessage>(Channel.UNLIMITED)
        incoming.send(ProtocolMessage.Hello("0.1", "recv"))
        val channel = object : SecureChannel {
            override suspend fun send(message: ProtocolMessage) {
                when (message) {
                    is ProtocolMessage.LineageInit -> incoming.send(ProtocolMessage.LineageAck(message.lineageId))
                    is ProtocolMessage.Manifest -> incoming.send(ProtocolMessage.Select(listOf(1)))
                    is ProtocolMessage.ItemEnd -> {
                        val meta = repository.active()!!.manifest!!.items.single()
                        incoming.send(ProtocolMessage.ItemAck(ItemResult(meta.itemId, ItemStatus.OK,
                            phase = ReceiptPhase.RECEIVED_VERIFIED, occurrenceId = meta.occurrenceId)))
                    }
                    else -> Unit
                }
            }
            override suspend fun receive(): ProtocolMessage? = incoming.receive()
            override fun close() { incoming.close() }
        }
        val vm = SenderViewModel(
            providers = listOf(BytesExport(ItemKind.CONTACTS_VCF, "vcard".toByteArray())),
            stagingDir = tmp.root,
            senderName = "sender",
            channelFactory = FakeFactory(channel),
            addressHints = { listOf("192.168.1.2") },
            portFinder = { 40123 },
            dataPhaseTimeoutMs = 1_000L,
            lineageRepository = repository,
            transferIoDispatcher = dispatcher,
        )
        vm.onStartTransfer()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SenderState.Done(0, 0, unknown = 1))
        val active = repository.active()!!
        val key = com.ventouxlabs.portage.lineage.CheckpointKey.from(active.id, active.manifest!!.items.single())
        assertThat(repository.checkpoint(key)?.phase).isEqualTo(ReceiptPhase.UNKNOWN_INTERRUPTED)
        assertThat(repository.stagedFile(key)).isNotNull()
        repository.close()
    }

    @Test
    fun `reopening after dropped final receipt preserves manifest bytes and resume credential`() = runTest(dispatcher) {
        val directory = tmp.newFolder()
        val repository = LineageRepository(directory)
        val lostAck = ScriptedChannel(ProtocolMessage.Hello("0.1", "recv"), ProtocolMessage.Select(listOf(1)),
            ProtocolMessage.ItemAck(ItemResult(1, ItemStatus.OK)), null)
        val original = viewModel(FakeFactory(lostAck), repository = repository)
        original.onStartTransfer()
        advanceUntilIdle()
        assertThat(original.state.value).isEqualTo(SenderState.Done(0, 0, unknown = 1))
        val manifest = checkNotNull(repository.active()).manifest
        val credential = repository.credentialForResume()
        repository.close()

        val reopened = LineageRepository(directory)
        val factory = FakeFactory(happyChannel())
        val restored = viewModel(factory, providers = emptyList(), repository = reopened)
        assertThat((restored.state.value as SenderState.Failed).canResume).isTrue()
        restored.onResumeTransfer()
        advanceUntilIdle()
        assertThat(restored.state.value).isEqualTo(SenderState.Done(1, 0))
        assertThat(factory.acceptedPayload?.mode).isEqualTo(PairingMode.RESUME)
        assertThat(factory.resumedLineageId).isEqualTo(manifest?.lineageId)
        assertThat(factory.resumeCredential).isEqualTo(credential)
        assertThat(reopened.active()?.manifest).isEqualTo(manifest)
        restored.reset()
        assertThat(reopened.active()).isNull()
        assertThat(reopened.tombstones().last().reason).isEqualTo("finished")
        reopened.close()
        credential.fill(0)
    }

    @Test
    fun `retry before first authentication preserves lineage and uses fresh initial pairing`() = runTest(dispatcher) {
        val repository = LineageRepository(tmp.newFolder())
        val failingFactory = FakeFactory(acceptError = TransportException("listener expired"))
        val vm = viewModel(failingFactory, repository = repository)
        vm.onStartTransfer()
        advanceUntilIdle()
        val prepared = repository.active()
        val firstQrSecret = failingFactory.acceptedPayload!!.psk.copyOf()
        vm.onResumeTransfer()
        advanceUntilIdle()
        assertThat(repository.active()).isEqualTo(prepared)
        assertThat(failingFactory.acceptedPayload?.mode).isEqualTo(PairingMode.NEW)
        assertThat(failingFactory.acceptedPayload?.psk).isNotEqualTo(firstQrSecret)
        repository.close()
    }

    @Test
    fun `restart manifest validation uses the injected IO dispatcher before pairing`() = runTest(dispatcher) {
        var ioDispatches = 0
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                ioDispatches++
                dispatcher.dispatch(context, block)
            }
        }
        val repository = LineageRepository(tmp.newFolder())
        val factory = FakeFactory(acceptError = TransportException("stop before handshake"))
        val vm = viewModel(factory, repository = repository, transferIoDispatcher = io)
        vm.onStartTransfer()
        advanceUntilIdle()
        val firstPassDispatches = ioDispatches
        assertThat(firstPassDispatches).isGreaterThan(0)
        vm.onResumeTransfer()
        advanceUntilIdle()
        // Pairing fails before engine work; this dispatch belongs to prepared-file restoration.
        assertThat(ioDispatches).isGreaterThan(firstPassDispatches)
        assertThat(repository.active()?.prepared).isTrue()
        repository.close()
    }

    @Test
    fun `pending bootstrap restart resumes only when original receiver persisted the credential`() = runTest(dispatcher) {
        for (crash in listOf("before-send", "before-peer-persist", "before-ack-processing")) {
            val directory = tmp.newFolder()
            val sender = LineageRepository(directory)
            val receiver = LineageRepository(tmp.newFolder())
            val brokenChannel = object : SecureChannel {
                var helloRead = false
                override suspend fun receive(): ProtocolMessage? {
                    if (!helloRead) {
                        helloRead = true
                        if (crash == "before-send") throw TransportException("process died before send")
                        return ProtocolMessage.Hello("0.1", "recv")
                    }
                    throw TransportException("process died before acknowledgement processing")
                }
                override suspend fun send(message: ProtocolMessage) {
                    if (message is ProtocolMessage.LineageInit && crash == "before-ack-processing") {
                        receiver.acceptInitial(message.lineageId, message.resumeCredential)
                    }
                }
                override fun close() = Unit
            }
            val original = viewModel(FakeFactory(brokenChannel), repository = sender)
            original.onStartTransfer()
            advanceUntilIdle()
            val active = checkNotNull(sender.active())
            assertThat(active.credentialState).isEqualTo(CredentialState.PENDING)
            val secret = sender.pendingBootstrapCredential(active.id)
            assertThat(runCatching { sender.credentialForResume() }.isFailure).isTrue()
            sender.close()

            val reopened = LineageRepository(directory)
            val retryChannel = happyChannel()
            val factory = PossessionFactory(receiver, retryChannel)
            val retry = viewModel(factory, providers = emptyList(), repository = reopened)
            retry.onResumeTransfer()
            advanceUntilIdle()
            assertThat(factory.payload?.mode).isEqualTo(PairingMode.RESUME)
            assertThat(retryChannel.sent.filterIsInstance<ProtocolMessage.LineageInit>()).isEmpty()
            assertThat(reopened.active()?.id).isEqualTo(active.id)
            if (crash == "before-ack-processing") {
                assertThat(reopened.active()?.credentialState).isEqualTo(CredentialState.ESTABLISHED)
                assertThat(reopened.credentialForResume()).isEqualTo(secret)
                assertThat(retry.state.value).isEqualTo(SenderState.Done(1, 0))
                assertThat(retryChannel.sent.first()).isEqualTo(ProtocolMessage.LineageResume(active.id))
            } else {
                assertThat(reopened.active()?.credentialState).isEqualTo(CredentialState.PENDING)
                assertThat(retryChannel.sent).isEmpty()
                val failed = retry.state.value as SenderState.Failed
                assertThat(failed.canResume).isTrue()
                assertThat(failed.reason).doesNotContain("Start a new move")
            }
            reopened.close()
            receiver.close()
            secret.fill(0)
        }
    }

    @Test
    fun `transient pending resume failure remains resumable and succeeds on second attempt`() = runTest(dispatcher) {
        val sender = LineageRepository(tmp.newFolder())
        val receiver = LineageRepository(tmp.newFolder())
        val successfulChannel = happyChannel()
        val interruptedChannel = object : SecureChannel {
            private var helloRead = false
            override suspend fun receive(): ProtocolMessage? {
                if (!helloRead) {
                    helloRead = true
                    return ProtocolMessage.Hello("0.1", "recv")
                }
                throw TransportException("temporary disconnect")
            }

            override suspend fun send(message: ProtocolMessage) {
                if (message is ProtocolMessage.LineageInit) {
                    receiver.acceptInitial(message.lineageId, message.resumeCredential)
                }
            }

            override fun close() = Unit
        }
        var attempts = 0
        val factory = object : SecureChannel.Factory {
            override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = error("sender only")
            override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel =
                error("credential overload required")

            override suspend fun acceptAsSender(
                payload: PairingPayload,
                resumeCredential: ByteArray?,
                lineageId: String?,
            ): SecureChannel {
                attempts++
                if (attempts == 1) return interruptedChannel
                assertThat(payload.mode).isEqualTo(PairingMode.RESUME)
                assertThat(lineageId).isEqualTo(receiver.active()?.id)
                val expected = receiver.credentialForResume()
                try {
                    assertThat(resumeCredential).isEqualTo(expected)
                } finally {
                    expected.fill(0)
                }
                return successfulChannel
            }
        }
        val vm = viewModel(factory, repository = sender)

        vm.onStartTransfer()
        advanceUntilIdle()

        val interrupted = vm.state.value as SenderState.Failed
        assertThat(interrupted.reason).contains("temporary disconnect")
        assertThat(interrupted.reason).doesNotContain("Start a new move")
        assertThat(interrupted.canResume).isTrue()
        assertThat(sender.active()?.credentialState).isEqualTo(CredentialState.PENDING)

        vm.onResumeTransfer()
        advanceUntilIdle()

        assertThat(attempts).isEqualTo(2)
        assertThat(vm.state.value).isEqualTo(SenderState.Done(1, 0))
        assertThat(sender.active()?.credentialState).isEqualTo(CredentialState.ESTABLISHED)
        sender.close()
        receiver.close()
    }

    @Test
    fun `second receiver with a fresh QR cannot obtain the first receivers pending credential`() = runTest(dispatcher) {
        val sender = LineageRepository(tmp.newFolder())
        val firstReceiver = LineageRepository(tmp.newFolder())
        val secondReceiver = LineageRepository(tmp.newFolder())
        val active = sender.startNewMove()
        val staged = com.ventouxlabs.portage.send.transfer.ManifestBuilder(
            listOf(BytesExport(ItemKind.CONTACTS_VCF, "vcard".toByteArray())),
            sender.stagingDir, "sender", active.id, ioDispatcher = dispatcher,
        ).build()
        sender.savePreparedManifest(staged.manifest, staged.items.associate { it.meta.itemId to it.file })
        val secret = sender.establishResumeCredential(active.id)
        firstReceiver.acceptInitial(active.id, secret)

        val attackerChannel = happyChannel()
        val attackerFactory = PossessionFactory(secondReceiver, attackerChannel)
        val attacked = viewModel(attackerFactory, providers = emptyList(), repository = sender)
        attacked.onResumeTransfer()
        advanceUntilIdle()
        assertThat(attackerFactory.payload?.mode).isEqualTo(PairingMode.RESUME)
        assertThat(attackerChannel.sent).isEmpty()
        assertThat(secondReceiver.active()).isNull()
        assertThat(sender.active()?.credentialState).isEqualTo(CredentialState.PENDING)

        val originalChannel = happyChannel()
        val originalFactory = PossessionFactory(firstReceiver, originalChannel)
        val original = viewModel(originalFactory, providers = emptyList(), repository = sender)
        original.onResumeTransfer()
        advanceUntilIdle()
        assertThat(original.state.value).isEqualTo(SenderState.Done(1, 0))
        assertThat(originalChannel.sent.filterIsInstance<ProtocolMessage.LineageInit>()).isEmpty()
        assertThat(sender.credentialForResume()).isEqualTo(secret)
        sender.close()
        firstReceiver.close()
        secondReceiver.close()
        secret.fill(0)
    }

    @Test
    fun `pending resume remains resumable after acknowledged promotion and later manifest failure`() = runTest(dispatcher) {
        val sender = LineageRepository(tmp.newFolder())
        val receiver = LineageRepository(tmp.newFolder())
        val active = sender.startNewMove()
        val staged = com.ventouxlabs.portage.send.transfer.ManifestBuilder(
            listOf(BytesExport(ItemKind.CONTACTS_VCF, "vcard".toByteArray())),
            sender.stagingDir, "sender", active.id, ioDispatcher = dispatcher,
        ).build()
        sender.savePreparedManifest(staged.manifest, staged.items.associate { it.meta.itemId to it.file })
        val secret = sender.establishResumeCredential(active.id)
        receiver.acceptInitial(active.id, secret)
        val channel = happyChannel().apply {
            afterSend = { if (it is ProtocolMessage.Manifest) throw TransportException("manifest send failed") }
        }
        val vm = viewModel(PossessionFactory(receiver, channel), providers = emptyList(), repository = sender)

        vm.onResumeTransfer()
        advanceUntilIdle()

        assertThat(sender.active()?.credentialState).isEqualTo(CredentialState.ESTABLISHED)
        assertThat(sender.active()?.prepared).isTrue()
        assertThat(sender.active()?.id).isEqualTo(active.id)
        assertThat((vm.state.value as SenderState.Failed).canResume).isTrue()
        assertThat((vm.state.value as SenderState.Failed).reason).contains("manifest send failed")
        sender.close()
        receiver.close()
        secret.fill(0)
    }

    @Test
    fun `incoming peer cancel never records peer deletion without its acknowledgement`() = runTest(dispatcher) {
        val repository = LineageRepository(tmp.newFolder())
        val incoming = Channel<ProtocolMessage>(Channel.UNLIMITED)
        incoming.send(ProtocolMessage.Hello("0.1", "recv"))
        val channel = object : SecureChannel {
            override suspend fun send(message: ProtocolMessage) {
                if (message is ProtocolMessage.LineageInit) incoming.send(ProtocolMessage.Cancel(message.lineageId))
            }
            override suspend fun receive(): ProtocolMessage? = incoming.receive()
            override fun close() { incoming.close() }
        }
        val vm = viewModel(FakeFactory(channel), repository = repository)
        vm.onStartTransfer()
        advanceUntilIdle()
        assertThat(repository.active()).isNull()
        assertThat(repository.tombstones().last().peerDeleted).isFalse()
        assertThat((vm.state.value as SenderState.Failed).reason).contains("other phone was not confirmed")
        repository.close()
    }

    @Test
    fun `one hundred explicit new moves each consume exactly sixteen lineage bytes`() = runTest(dispatcher) {
        val requests = mutableListOf<Int>()
        var value = 0
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                requests += bytes.size
                bytes.fill((++value).toByte())
            }
        }
        val repository = LineageRepository(tmp.newFolder(), random = random)
        val vm = viewModel(FakeFactory(happyChannel()), providers = emptyList(), repository = repository)
        assertThat(requests).isEmpty()
        val lineages = mutableSetOf<String>()
        repeat(100) {
            vm.onStartTransfer()
            advanceUntilIdle()
            lineages += checkNotNull(repository.active()).id
        }
        assertThat(lineages).hasSize(100)
        assertThat(requests).hasSize(100)
        assertThat(requests.toSet()).containsExactly(16)
        repository.close()
    }

    @Test
    fun `disconnected cancel revokes local secret without claiming peer deletion`() = runTest(dispatcher) {
        val repository = LineageRepository(tmp.newFolder())
        val vm = viewModel(FakeFactory(acceptError = TransportException("offline")), repository = repository)
        vm.onStartTransfer()
        advanceUntilIdle()
        vm.cancelTransfer()
        advanceUntilIdle()
        assertThat(repository.active()).isNull()
        assertThat(repository.tombstones().last().peerDeleted).isFalse()
        assertThat((vm.state.value as SenderState.Failed).reason).contains("not confirmed")
        assertThat(repository.stagingDir.listFiles().orEmpty()).isEmpty()
        repository.close()
    }

    @Test
    fun `cancel stops repository file validation before scheduling repository work off main`() = runTest(dispatcher) {
        val validating = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val ioJob = AtomicReference<Job>()
        val snapshotThreads = CopyOnWriteArrayList<String>()
        val repository = LineageRepository(tmp.newFolder(),
            beforeSnapshotReplace = { snapshotThreads += Thread.currentThread().name },
            beforeStagedRead = {
                validating.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS)) { "test did not release validation" }
            },
        )
        Executors.newSingleThreadExecutor { Thread(it, "sender-cancel-io") }.asCoroutineDispatcher().use { executor ->
            val io = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    ioJob.set(context[Job])
                    executor.dispatch(context, block)
                }
            }
            try {
                val factory = FakeFactory(happyChannel())
                val vm = viewModel(factory, repository = repository, transferIoDispatcher = io)
                vm.onStartTransfer()
                runCurrent()
                assertThat(validating.await(5, TimeUnit.SECONDS)).isTrue()
                val validationJob = checkNotNull(ioJob.get())
                val writesBeforeCancel = snapshotThreads.size
                vm.cancelTransfer()
                // No scheduler advance or repository call is needed to cancel the hashing job.
                assertThat(validationJob.isCancelled).isTrue()
                assertThat(snapshotThreads).hasSize(writesBeforeCancel)
                releaseRead.countDown()
                val stopped = vm.state.first { it is SenderState.Failed } as SenderState.Failed
                assertThat(stopped.reason).contains("not confirmed")
                assertThat(repository.active()).isNull()
                assertThat(snapshotThreads.drop(writesBeforeCancel).map { it.substringBefore(" @coroutine") }.toSet())
                    .containsExactly("sender-cancel-io")
                assertThat(factory.acceptedPayload).isNull()
            } finally {
                releaseRead.countDown()
                repository.close()
            }
        }
    }

    @Test
    fun `ViewModel clear cannot cancel a queued durable local cancellation`() = runTest(dispatcher) {
        val directory = tmp.newFolder()
        val pausedIo = PausableDispatcher(dispatcher)
        val opened = AtomicReference<LineageRepository>()
        val factory = object : SecureChannel.Factory {
            override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = error("sender only")
            override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel = awaitCancellation()
        }
        val vm = viewModel(
            factory,
            transferIoDispatcher = pausedIo,
            repositoryFactory = { LineageRepository(directory).also(opened::set) },
        )
        val owner = ViewModelStore().apply { put("sender", vm) }

        runCurrent()
        vm.onStartTransfer()
        runCurrent()
        assertThat(vm.state.value).isInstanceOf(SenderState.ShowingQr::class.java)
        assertThat(checkNotNull(opened.get()).active()).isNotNull()

        pausedIo.pause()
        vm.cancelTransfer()
        owner.clear()
        runCurrent()

        // Neither the queued NonCancellable write nor final close has run yet. The old owner must
        // retain the writer lock instead of allowing a replacement to observe live credentials.
        assertThrows(LineageBusyException::class.java) { LineageRepository(directory) }

        pausedIo.resume()
        advanceUntilIdle()

        LineageRepository(directory).use { reopened ->
            assertThat(reopened.active()).isNull()
            assertThat(reopened.tombstones()).hasSize(1)
            assertThat(reopened.tombstones().single().peerDeleted).isFalse()
        }
    }

    @Test
    fun `replacement retries busy repository and starts only after old keepalive stops`() = runTest(dispatcher) {
        val directory = tmp.newFolder()
        val releaseOldListener = CompletableDeferred<Unit>()
        var accepted = 0
        val factory = object : SecureChannel.Factory {
            override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = error("sender only")
            override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel {
                val owner = ++accepted
                try {
                    awaitCancellation()
                } finally {
                    if (owner == 1) withContext(NonCancellable) { releaseOldListener.await() }
                }
            }
        }
        val events = mutableListOf<String>()
        val first = viewModel(
            factory,
            keepAlive = TaggedKeepAlive("old", events),
            repositoryFactory = { LineageRepository(directory) },
        )
        val oldOwner = ViewModelStore().apply { put("sender", first) }
        runCurrent()
        first.onStartTransfer()
        runCurrent()
        assertThat(events).containsExactly("start-old").inOrder()

        oldOwner.clear()
        runCurrent()
        val second = viewModel(
            factory,
            keepAlive = TaggedKeepAlive("new", events),
            repositoryFactory = { LineageRepository(directory) },
        )
        val newOwner = ViewModelStore().apply { put("sender", second) }
        runCurrent()
        assertThat(second.state.value).isEqualTo(SenderState.OpeningSavedMove)
        assertThat(events).containsExactly("start-old").inOrder()

        releaseOldListener.complete(Unit)
        runCurrent()
        assertThat(events).containsExactly("start-old", "stop-old").inOrder()
        advanceTimeBy(100)
        runCurrent()
        val saved = second.state.value as SenderState.Failed
        assertThat(saved.canResume).isTrue()

        second.onResumeTransfer()
        runCurrent()
        assertThat(second.state.value).isInstanceOf(SenderState.ShowingQr::class.java)
        assertThat(events).containsExactly("start-old", "stop-old", "start-new").inOrder()

        newOwner.clear()
        advanceUntilIdle()
        assertThat(events).containsExactly("start-old", "stop-old", "start-new", "stop-new").inOrder()
    }

    @Test
    fun `connected cancel waits for matching peer acknowledgement`() = runTest(dispatcher) {
        val repository = LineageRepository(tmp.newFolder())
        val channel = happyChannel()
        val vm = viewModel(FakeFactory(channel), repository = repository)
        channel.afterSend = { message ->
            if (message is ProtocolMessage.ItemEnd) {
                channel.cancelReply = ProtocolMessage.CancelAck(checkNotNull(repository.active()).id)
                vm.cancelTransfer()
            }
        }
        vm.onStartTransfer()
        advanceUntilIdle()
        assertThat(repository.tombstones().last().peerDeleted).isTrue()
        assertThat((vm.state.value as SenderState.Failed).reason).contains("Both phones confirmed")
        assertThat(channel.closed).isTrue()
        repository.close()
    }

    @Test
    fun `connected cancel while waiting for selection exchanges acknowledgement`() = runTest(dispatcher) {
        val repository = LineageRepository(tmp.newFolder())
        val incoming = Channel<ProtocolMessage>(Channel.UNLIMITED)
        incoming.send(ProtocolMessage.Hello("0.1", "recv"))
        var manifestSent = false
        val channel = object : SecureChannel {
            override suspend fun send(message: ProtocolMessage) {
                when (message) {
                    is ProtocolMessage.LineageInit -> incoming.send(ProtocolMessage.LineageAck(message.lineageId))
                    is ProtocolMessage.Manifest -> manifestSent = true
                    is ProtocolMessage.Cancel -> incoming.send(ProtocolMessage.CancelAck(message.lineageId))
                    else -> Unit
                }
            }
            override suspend fun receive(): ProtocolMessage? = incoming.receive()
            override fun close() { incoming.close() }
        }
        val vm = viewModel(FakeFactory(channel), repository = repository)
        vm.onStartTransfer()
        runCurrent()
        assertThat(manifestSent).isTrue()
        vm.cancelTransfer()
        advanceUntilIdle()
        assertThat(repository.tombstones().last().peerDeleted).isTrue()
        assertThat((vm.state.value as SenderState.Failed).reason).contains("Both phones confirmed")
        repository.close()
    }

    @Test
    fun `keep-alive starts before the data phase and stops once on success`() = runTest(dispatcher) {
        val keepAlive = FakeKeepAlive()
        val vm = viewModel(FakeFactory(happyChannel()), keepAlive = keepAlive)

        vm.onStartTransfer()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(SenderState.Done::class.java)
        assertThat(keepAlive.starts).isEqualTo(1)
        assertThat(keepAlive.stops).isEqualTo(1)
    }

    @Test
    fun `QR cancel and reset fence replacement until old listener and keepalive are released`() = runTest(dispatcher) {
        for (reset in listOf(false, true)) {
            val repository = LineageRepository(tmp.newFolder())
            val releaseOldListener = CompletableDeferred<Unit>()
            var accepted = 0
            var released = 0
            val factory = object : SecureChannel.Factory {
                override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = error("sender only")
                override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel {
                    val owner = ++accepted
                    try {
                        awaitCancellation()
                    } finally {
                        // Model a cancelled accept that has not yet released native ownership.
                        if (owner == 1) withContext(NonCancellable) { releaseOldListener.await() }
                        released++
                    }
                }
            }
            val keepAlive = FakeKeepAlive()
            val vm = viewModel(factory, keepAlive = keepAlive, repository = repository)
            vm.onStartTransfer()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(SenderState.ShowingQr::class.java)
            assertThat(accepted).isEqualTo(1)

            if (reset) vm.reset() else vm.cancelTransfer()
            runCurrent()
            vm.onStartTransfer()
            runCurrent()
            assertThat(accepted).isEqualTo(1)
            assertThat(released).isEqualTo(0)
            assertThat(keepAlive.starts).isEqualTo(1)
            assertThat(keepAlive.stops).isEqualTo(0)

            releaseOldListener.complete(Unit)
            runCurrent()
            assertThat(released).isEqualTo(1)
            assertThat(keepAlive.stops).isEqualTo(1)
            vm.onStartTransfer()
            runCurrent()
            assertThat(accepted).isEqualTo(2)
            assertThat(keepAlive.starts).isEqualTo(2)
            assertThat(keepAlive.stops).isEqualTo(1)
            assertThat(vm.state.value).isInstanceOf(SenderState.ShowingQr::class.java)

            vm.reset()
            runCurrent()
            assertThat(released).isEqualTo(2)
            assertThat(keepAlive.stops).isEqualTo(2)
            repository.close()
        }
    }

    @Test
    fun `keep-alive is released when the data phase times out`() = runTest(dispatcher) {
        // Same slow-drip setup as the aggregate-cap test: handshake + HELLO, then never SELECT.
        val keepAlive = FakeKeepAlive()
        val vm = SenderViewModel(
            providers = listOf(BytesExport(ItemKind.CONTACTS_VCF, "vcard".toByteArray())),
            stagingDir = tmp.root,
            senderName = "old phone",
            channelFactory = FakeFactory(StallingChannel(ProtocolMessage.Hello("0.1.0", "recv"))),
            pairingCodec = PairingCodecImpl(),
            random = SecureRandom(),
            addressHints = { listOf("192.168.1.2") },
            portFinder = { 40123 },
            nowEpochSeconds = { 1_000 },
            dataPhaseTimeoutMs = 1_000L,
            relayResolveDispatcher = dispatcher,
            transferKeepAlive = keepAlive,
        )

        runCurrent() // finish the asynchronous saved-move open before accepting user actions
        vm.onStartTransfer()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(SenderState.Failed::class.java)
        // Started for the listen→stream window, then released in the finally despite the timeout.
        assertThat(keepAlive.starts).isEqualTo(1)
        assertThat(keepAlive.stops).isEqualTo(1)
    }

    @Test
    fun `keep-alive never starts when prepare fails before listening`() = runTest(dispatcher) {
        // No LAN ⇒ fail() before acceptAsSender ⇒ start() is never reached.
        val keepAlive = FakeKeepAlive()
        val vm = viewModel(FakeFactory(happyChannel()), hints = emptyList(), keepAlive = keepAlive)

        vm.onStartTransfer()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(SenderState.Failed::class.java)
        assertThat(keepAlive.starts).isEqualTo(0)
        assertThat(keepAlive.stops).isEqualTo(0)
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

    // ---- explicitly selected user files ----

    @Test
    fun `multiple selected files become distinct USER_FILE manifest items`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(FakeFactory(channel))
        vm.resolveAndAddUserFiles(
            listOf(
                { userFile(pickId = 20L, bytes = "one".toByteArray()) },
                { userFile(pickId = 21L, bytes = "two".toByteArray()) },
            ),
        )
        advanceUntilIdle()

        vm.onStartTransfer()
        advanceUntilIdle()

        val manifest = channel.sent.filterIsInstance<ProtocolMessage.Manifest>().single().manifest
        assertThat(manifest.items.count { it.kind == ItemKind.USER_FILE }).isEqualTo(2)
    }

    @Test
    fun `remove and reset release selected file grants`() = runTest(dispatcher) {
        var removedReleased = false
        var resetReleased = false
        val vm = viewModel(FakeFactory(happyChannel()))
        vm.resolveAndAddUserFiles(
            listOf(
                { userFile(pickId = 20L, releaseGrant = { removedReleased = true }) },
                { userFile(pickId = 21L, releaseGrant = { resetReleased = true }) },
            ),
        )
        advanceUntilIdle()

        vm.removeUserFile(20L)
        vm.reset()

        assertThat(removedReleased).isTrue()
        assertThat(resetReleased).isTrue()
        assertThat(vm.userFiles.value).isEmpty()
    }

    @Test
    fun `failed file resolutions are omitted while valid selections are retained`() = runTest(dispatcher) {
        val vm = viewModel(FakeFactory(happyChannel()))
        vm.resolveAndAddUserFiles(
            listOf(
                { throw java.io.IOException("grant denied") },
                { null },
                { userFile(pickId = 22L) },
            ),
        )
        advanceUntilIdle()

        assertThat(vm.userFiles.value.map { it.pickId }).containsExactly(22L)
    }

    @Test
    fun `selected user files are capped by count without resolving beyond the limit`() = runTest(dispatcher) {
        val vm = viewModel(FakeFactory(happyChannel()))
        var resolverCalls = 0
        vm.resolveAndAddUserFiles(
            (0..UserFileHeader.MAX_FILES_PER_TRANSFER).map { index ->
                {
                    resolverCalls += 1
                    userFile(pickId = index.toLong())
                }
            },
        )
        advanceUntilIdle()

        assertThat(vm.userFiles.value).hasSize(UserFileHeader.MAX_FILES_PER_TRANSFER)
        assertThat(resolverCalls).isEqualTo(UserFileHeader.MAX_FILES_PER_TRANSFER)
    }

    @Test
    fun `selected user files are capped by aggregate bytes and rejected grants are released`() = runTest(dispatcher) {
        var rejectedReleased = false
        val vm = viewModel(FakeFactory(happyChannel()))
        vm.resolveAndAddUserFiles(
            listOf(
                { userFile(pickId = 30L, byteLength = UserFileHeader.MAX_TOTAL_BYTES) },
                {
                    userFile(
                        pickId = 31L,
                        byteLength = 1L,
                        releaseGrant = { rejectedReleased = true },
                    )
                },
            ),
        )
        advanceUntilIdle()

        assertThat(vm.userFiles.value.map { it.pickId }).containsExactly(30L)
        assertThat(rejectedReleased).isTrue()
    }

    // ---- apps to carry (ADR-006 Phase 1b): selection + sender-side APK provider append ----

    @Test
    fun `available apps populate from the installed-app seam, none selected by default`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeFactory(happyChannel()),
            installedAppSource = FakeInstalledAppSource(
                listOf(
                    onDiskApp(tmp.root, "com.a.app", "Alpha"),
                    onDiskApp(tmp.root, "com.b.app", "Bravo"),
                ),
            ),
        )
        advanceUntilIdle() // enumeration is now off-main (init launch); drain it before asserting
        assertThat(vm.availableApps.value.map { it.packageName }).containsExactly("com.a.app", "com.b.app")
        assertThat(vm.selectedAppPackages.value).isEmpty()
    }

    @Test
    fun `available apps are empty when no installed-app seam is wired`() = runTest(dispatcher) {
        val vm = viewModel(FakeFactory(happyChannel()))
        assertThat(vm.availableApps.value).isEmpty()
    }

    @Test
    fun `toggleApp adds then removes a package from the selection`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeFactory(happyChannel()),
            installedAppSource = FakeInstalledAppSource(listOf(onDiskApp(tmp.root, "com.a.app", "Alpha"))),
        )

        vm.toggleApp("com.a.app")
        assertThat(vm.selectedAppPackages.value).containsExactly("com.a.app")

        vm.toggleApp("com.a.app")
        assertThat(vm.selectedAppPackages.value).isEmpty()
    }

    @Test
    fun `selectAllApps then clearAppSelection flip the whole set`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeFactory(happyChannel()),
            installedAppSource = FakeInstalledAppSource(
                listOf(onDiskApp(tmp.root, "com.a.app", "Alpha"), onDiskApp(tmp.root, "com.b.app", "Bravo")),
            ),
        )
        advanceUntilIdle() // selectAllApps reads availableApps, populated by the off-main init launch

        vm.selectAllApps()
        assertThat(vm.selectedAppPackages.value).containsExactly("com.a.app", "com.b.app")

        vm.clearAppSelection()
        assertThat(vm.selectedAppPackages.value).isEmpty()
    }

    @Test
    fun `onStartTransfer builds APK items only for selected apps`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(
            FakeFactory(channel),
            installedAppSource = FakeInstalledAppSource(
                listOf(onDiskApp(tmp.root, "com.a.app", "Alpha"), onDiskApp(tmp.root, "com.b.app", "Bravo")),
            ),
        )
        advanceUntilIdle() // drain the off-main enumeration so selectedApps() sees the populated set
        // Select ONLY Alpha — Bravo must NOT be staged/manifested.
        vm.toggleApp("com.a.app")

        vm.onStartTransfer()
        advanceUntilIdle()

        val manifest = channel.sent.filterIsInstance<ProtocolMessage.Manifest>().single().manifest
        // The base contacts item plus exactly ONE APK item (Alpha), not two.
        assertThat(manifest.items.map { it.kind })
            .containsExactly(ItemKind.CONTACTS_VCF, ItemKind.APK)
        val apkItem = manifest.items.single { it.kind == ItemKind.APK }
        assertThat(apkItem.displayName).isEqualTo("Alpha")
    }

    @Test
    fun `no selected apps means no APK items in the manifest`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(
            FakeFactory(channel),
            installedAppSource = FakeInstalledAppSource(listOf(onDiskApp(tmp.root, "com.a.app", "Alpha"))),
        )
        // Default: nothing selected.

        vm.onStartTransfer()
        advanceUntilIdle()

        val manifest = channel.sent.filterIsInstance<ProtocolMessage.Manifest>().single().manifest
        assertThat(manifest.items.map { it.kind }).containsExactly(ItemKind.CONTACTS_VCF)
    }

    @Test
    fun `two selected apps produce two distinct APK items`() = runTest(dispatcher) {
        val channel = happyChannel()
        val vm = viewModel(
            FakeFactory(channel),
            installedAppSource = FakeInstalledAppSource(
                listOf(onDiskApp(tmp.root, "com.a.app", "Alpha"), onDiskApp(tmp.root, "com.b.app", "Bravo")),
            ),
        )
        advanceUntilIdle() // selectAllApps reads availableApps, populated by the off-main init launch
        vm.selectAllApps()

        vm.onStartTransfer()
        advanceUntilIdle()

        val manifest = channel.sent.filterIsInstance<ProtocolMessage.Manifest>().single().manifest
        val apkItems = manifest.items.filter { it.kind == ItemKind.APK }
        assertThat(apkItems).hasSize(2)
        assertThat(apkItems.map { it.itemId }.toSet()).hasSize(2)
    }

    @Test
    fun `reset clears the app selection`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeFactory(happyChannel()),
            installedAppSource = FakeInstalledAppSource(listOf(onDiskApp(tmp.root, "com.a.app", "Alpha"))),
        )
        vm.toggleApp("com.a.app")
        assertThat(vm.selectedAppPackages.value).isNotEmpty()

        vm.reset()

        assertThat(vm.selectedAppPackages.value).isEmpty()
    }
}
