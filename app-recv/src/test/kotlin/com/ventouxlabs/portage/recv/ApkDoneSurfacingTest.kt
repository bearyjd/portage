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
import com.ventouxlabs.portage.model.PairingPayload
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.model.TransferManifest
import com.ventouxlabs.portage.providers.ApplyProviderRegistry
import com.ventouxlabs.portage.providers.apk.ApkApplyProvider
import com.ventouxlabs.portage.providers.apk.ApkCodec
import com.ventouxlabs.portage.providers.apk.ApkContainerHeader
import com.ventouxlabs.portage.providers.apk.ApkFileEntry
import com.ventouxlabs.portage.providers.apk.ApkFileRole
import com.ventouxlabs.portage.providers.apk.ApkInstallAction
import com.ventouxlabs.portage.providers.apk.ApkInstallResult
import com.ventouxlabs.portage.providers.apk.ApkSilentInstaller
import com.ventouxlabs.portage.providers.apk.ApkSourceFile
import com.ventouxlabs.portage.providers.apk.ApkTargetConfig
import com.ventouxlabs.portage.providers.apk.InstalledPackageVersions
import com.ventouxlabs.portage.providers.inventory.AppRecord
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt
import com.ventouxlabs.portage.transport.PairingCodec
import com.ventouxlabs.portage.transport.SecureChannel
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
import java.security.MessageDigest

/**
 * End-to-end Done-state surfacing of the three APK apply outcomes (ADR-006 D3/D6): a Tier-0 install
 * prompt, an already-installed skip (no prompt), and an incompatible→store-fallback (an install action).
 * Drives the real receiver flow with a real [ApkCodec] container item through a real [ApkApplyProvider]
 * wired to fake seams — no Android, no `:adb-bridge`.
 */
class ApkDoneSurfacingTest {

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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val payload = PairingPayload(
        psk = ByteArray(PairingPayload.PSK_BYTES),
        sid = ByteArray(PairingPayload.SID_BYTES),
        ip = listOf("192.168.1.2"),
        port = 7777,
        expiresAtEpochSeconds = 9_999_999_999,
    )

    private inner class FakeCodec : PairingCodec {
        override fun encode(p: PairingPayload): String = "portage1:unused"
        override fun decode(qr: String, nowEpochSeconds: Long): Result<PairingPayload> =
            if (qr == "good-qr") Result.success(payload) else Result.failure(IllegalArgumentException("bad"))
    }

    private inner class FakeChannel(vararg incoming: ProtocolMessage) : SecureChannel {
        private val queue = ArrayDeque(incoming.toList())
        override suspend fun send(message: ProtocolMessage) = Unit
        override suspend fun receive(): ProtocolMessage? = if (queue.isEmpty()) null else queue.removeFirst()
        override fun close() = Unit
    }

    private inner class FakeFactory(private val channel: SecureChannel) : SecureChannel.Factory {
        override suspend fun connectAsReceiver(p: PairingPayload): SecureChannel = channel
        override suspend fun acceptAsSender(p: PairingPayload): SecureChannel =
            throw UnsupportedOperationException()
    }

    /** Build a real APK container item's wire bytes (base + the given config splits). */
    private fun apkBytes(
        packageName: String = "com.example.app",
        versionCode: Long = 7L,
        splits: List<ApkFileEntry> = emptyList(),
    ): ByteArray {
        val baseEntry = ApkFileEntry("base", ApkFileRole.BASE, length = 4)
        val entries = listOf(baseEntry) + splits
        val header = ApkContainerHeader(packageName, versionCode, entries.size)
        val files = entries.map { e -> ApkSourceFile(e) { ByteArrayInputStream(ByteArray(e.length.toInt())) } }
        return ByteArrayOutputStream().use { out ->
            ApkCodec.writeContainer(out, header, files)
            out.toByteArray()
        }
    }

    private fun abiSplit(name: String) =
        ApkFileEntry("split_config.$name", ApkFileRole.CONFIG, abi = name, length = 4)

    private val pixelTarget = ApkTargetConfig(
        supportedAbis = listOf("arm64_v8a"),
        densityBucket = "xxhdpi",
        locales = listOf("en"),
    )

    private fun channelFor(apk: ByteArray): SecureChannel {
        val meta = ItemMeta(9, ItemKind.APK, apk.size.toLong(), sha256(apk), "Example", "Apps")
        return FakeChannel(
            ProtocolMessage.Manifest(TransferManifest("old phone", listOf(meta), apk.size.toLong())),
            ProtocolMessage.ItemBegin(9, ItemKind.APK, meta.size, apk.size),
            ProtocolMessage.ItemData(9, 0, apk),
            ProtocolMessage.ItemEnd(9, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(9), "done"),
        )
    }

    /** A receiver wired to a real ApkApplyProvider; [installed]/[silent]/[hasSilent] tune the branch. */
    private fun viewModel(
        channel: SecureChannel,
        installed: InstalledPackageVersions = InstalledPackageVersions.None,
        silent: ApkSilentInstaller = ApkSilentInstaller.Deferred,
        hasSilent: () -> Boolean = { false },
    ): ReceiverViewModel {
        val apkStaging = tmp.newFolder("apk-splits")
        return ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channel),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test",
            stagingDir = tmp.newFolder("staging"),
            applyRegistryFactory = ApplyRegistryFactory { onInstallActions, _, _, onApkInstallPrompt ->
                ApplyProviderRegistry(
                    listOf(
                        ApkApplyProvider(
                            stagingDir = apkStaging,
                            targetConfig = { pixelTarget },
                            installedVersions = installed,
                            silentInstaller = silent,
                            hasSilentInstall = hasSilent,
                            // Surface a Done-screen install prompt: a fake "session id" stands in for the
                            // sealed PackageInstaller session the Android adapter would create.
                            onApkInstall = { action: ApkInstallAction ->
                                onApkInstallPrompt(ApkInstallPrompt(action.packageName, action.label, 42))
                            },
                            onStoreFallback = { pkg, label ->
                                InstallAction.from(AppRecord(pkg, 0L, null, label))
                                    ?.let { onInstallActions(listOf(it)) }
                            },
                        ),
                    ),
                )
            },
        )
    }

    @Test
    fun `reset abandons any sealed-but-uncommitted sessions via the injected seam (fix 5)`() = runTest(dispatcher) {
        // The abandonSessions seam must be called on reset so orphan PackageInstaller sessions are
        // cleaned up when the user returns Home without tapping install. This pins fix 5b.
        var abandonCalled = false
        val apk = apkBytes(splits = listOf(abiSplit("arm64_v8a")))
        val apkStaging = tmp.newFolder("apk-splits-reset")
        val vm = ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channelFor(apk)),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test",
            stagingDir = tmp.newFolder("staging-reset"),
            abandonSessions = { abandonCalled = true },
            applyRegistryFactory = ApplyRegistryFactory { _, _, _, onApkInstallPrompt ->
                ApplyProviderRegistry(
                    listOf(
                        ApkApplyProvider(
                            stagingDir = apkStaging,
                            targetConfig = { pixelTarget },
                            onApkInstall = { action: ApkInstallAction ->
                                onApkInstallPrompt(ApkInstallPrompt(action.packageName, action.label, 42))
                            },
                        ),
                    ),
                )
            },
        )
        TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(abandonCalled).isFalse() // not called yet — user is on Done screen
        vm.reset()
        assertThat(abandonCalled).isTrue() // called on return-home
    }

    /** Drive scan → review → toggle the APK item ON (it's Tier-1, opt-in) → confirm → done. */
    private fun TestScopeRun(vm: ReceiverViewModel, advance: () -> Unit): ReceiverState.Done {
        vm.startScanning()
        vm.onQrScanned("good-qr")
        advance()
        vm.onToggle(APK_ITEM_ID) // APK is Tier-1: opt-in, not pre-checked
        vm.onConfirm()
        advance()
        return vm.state.value as ReceiverState.Done
    }

    @Test
    fun `a reconciled APK surfaces a Tier-0 install prompt on the Done state`() = runTest(dispatcher) {
        val apk = apkBytes(splits = listOf(abiSplit("arm64_v8a")))
        val vm = viewModel(channelFor(apk))
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.moved).isEqualTo(1) // "ready to install" is an OK outcome
        assertThat(done.apkInstallPrompts.map { it.packageName }).containsExactly("com.example.app")
        assertThat(done.installActions).isEmpty()
    }

    @Test
    fun `an already-installed APK is a skip with no install prompt`() = runTest(dispatcher) {
        val apk = apkBytes(versionCode = 7L)
        val installed = InstalledPackageVersions { if (it == "com.example.app") 9L else null }
        val vm = viewModel(channelFor(apk), installed = installed)
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.skipped).isEqualTo(1)
        assertThat(done.apkInstallPrompts).isEmpty()
        assertThat(done.installActions).isEmpty()
    }

    @Test
    fun `an incompatible APK surfaces a store-fallback install action, no install prompt`() = runTest(dispatcher) {
        // Only an x86 split; this arm64 target can't install it → incompatible → store fallback.
        val apk = apkBytes(splits = listOf(abiSplit("x86_64")))
        val vm = viewModel(channelFor(apk))
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.skipped).isEqualTo(1)
        assertThat(done.apkInstallPrompts).isEmpty()
        assertThat(done.installActions.map { it.packageName }).containsExactly("com.example.app")
    }

    @Test
    fun `a silent install success moves the item with no Tier-0 prompt`() = runTest(dispatcher) {
        val apk = apkBytes(splits = listOf(abiSplit("arm64_v8a")))
        val silent = ApkSilentInstaller { _, _ -> ApkInstallResult.Installed }
        val vm = viewModel(channelFor(apk), silent = silent, hasSilent = { true })
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.moved).isEqualTo(1)
        assertThat(done.apkInstallPrompts).isEmpty()
    }

    private companion object {
        const val APK_ITEM_ID = 9
    }
}
