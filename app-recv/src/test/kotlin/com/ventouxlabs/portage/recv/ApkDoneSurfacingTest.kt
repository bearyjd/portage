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
import com.ventouxlabs.portage.providers.apk.RuntimePermissionGranter
import com.ventouxlabs.portage.providers.apk.TargetDeclaredPermissions
import com.ventouxlabs.portage.providers.inventory.AppRecord
import com.ventouxlabs.portage.providers.permission.PermissionAllowlist
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt
import com.ventouxlabs.portage.transport.PairingCodec
import com.ventouxlabs.portage.transport.SecureChannel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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
        capturedPermissions: List<String> = emptyList(),
    ): ByteArray {
        val baseEntry = ApkFileEntry("base", ApkFileRole.BASE, length = 4)
        val entries = listOf(baseEntry) + splits
        val header = ApkContainerHeader(packageName, versionCode, entries.size, capturedPermissions)
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
        granter: RuntimePermissionGranter = RuntimePermissionGranter.NoOp,
        targetDeclared: TargetDeclaredPermissions = TargetDeclaredPermissions.None,
        optInGranter: RuntimePermissionGranter = RuntimePermissionGranter.NoOp,
    ): ReceiverViewModel {
        val apkStaging = tmp.newFolder("apk-splits")
        return ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channel),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test",
            stagingDir = tmp.newFolder("staging"),
            ioDispatcher = dispatcher,
            optInPermissionGranter = optInGranter,
            applyRegistryFactory =
                ApplyRegistryFactory { sinks ->
                    ApplyProviderRegistry(
                        listOf(
                            ApkApplyProvider(
                                stagingDir = apkStaging,
                                targetConfig = { pixelTarget },
                                installedVersions = installed,
                                silentInstaller = silent,
                                hasSilentInstall = hasSilent,
                                permissionGranter = granter,
                                targetDeclaredPermissions = targetDeclared,
                                onPermissionsRestored = sinks.onPermissionsRestored,
                                onOptInPermissions = sinks.onOptInPermissions,
                                // Surface a Done-screen install prompt: a fake "session id" stands in for the
                                // sealed PackageInstaller session the Android adapter would create.
                                onApkInstall = { action: ApkInstallAction ->
                                    sinks.onApkInstallPrompt(ApkInstallPrompt(action.packageName, action.label, 42))
                                },
                                onStoreFallback = { pkg, label ->
                                    InstallAction.from(AppRecord(pkg, 0L, null, label))
                                        ?.let { sinks.onInstallActions(listOf(it)) }
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
            ioDispatcher = dispatcher,
            abandonSessions = { abandonCalled = true },
            applyRegistryFactory = ApplyRegistryFactory { sinks ->
                ApplyProviderRegistry(
                    listOf(
                        ApkApplyProvider(
                            stagingDir = apkStaging,
                            targetConfig = { pixelTarget },
                            onApkInstall = { action: ApkInstallAction ->
                                sinks.onApkInstallPrompt(ApkInstallPrompt(action.packageName, action.label, 42))
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

    @Test
    fun `a silent install surfaces the re-granted runtime permissions on the Done state`() = runTest(dispatcher) {
        val apk = apkBytes(
            splits = listOf(abiSplit("arm64_v8a")),
            capturedPermissions = listOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS),
        )
        val silent = ApkSilentInstaller { _, _ -> ApkInstallResult.Installed }
        val granter = RuntimePermissionGranter { _, perms -> perms.toSet() } // the bridge grants all asked
        val target = TargetDeclaredPermissions {
            setOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS)
        }
        val vm = viewModel(
            channelFor(apk), silent = silent, hasSilent = { true }, granter = granter, targetDeclared = target,
        )
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.moved).isEqualTo(1)
        assertThat(done.restoredPermissions.map { it.packageName }).containsExactly("com.example.app")
        assertThat(done.restoredPermissions.single().permissions)
            .containsExactly(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS).inOrder()
    }

    @Test
    fun `reset clears restored permissions so they never leak into the next transfer`() = runTest(dispatcher) {
        val apk = apkBytes(
            splits = listOf(abiSplit("arm64_v8a")),
            capturedPermissions = listOf(PermissionAllowlist.INTERNET),
        )
        val silent = ApkSilentInstaller { _, _ -> ApkInstallResult.Installed }
        val granter = RuntimePermissionGranter { _, perms -> perms.toSet() }
        val target = TargetDeclaredPermissions { setOf(PermissionAllowlist.INTERNET) }
        val vm = viewModel(
            channelFor(apk), silent = silent, hasSilent = { true }, granter = granter, targetDeclared = target,
        )
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.restoredPermissions).isNotEmpty() // surfaced this transfer
        vm.reset()
        assertThat(vm.restoredPermissions.value).isEmpty() // cleared on return-Home — no leak into transfer #2
    }

    @Test
    fun `the Tier-0 fallback surfaces no re-granted permissions`() = runTest(dispatcher) {
        // No silent capability → Tier-0 emit; the grant call site never runs, so nothing is surfaced.
        val apk = apkBytes(
            splits = listOf(abiSplit("arm64_v8a")),
            capturedPermissions = listOf(PermissionAllowlist.INTERNET),
        )
        val granter = RuntimePermissionGranter { _, perms -> perms.toSet() }
        val target = TargetDeclaredPermissions { setOf(PermissionAllowlist.INTERNET) }
        val vm = viewModel(channelFor(apk), granter = granter, targetDeclared = target)
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.apkInstallPrompts).isNotEmpty() // Tier-0 path taken
        assertThat(done.restoredPermissions).isEmpty()
    }

    @Test
    fun `a silent install surfaces opt-in dangerous permissions on the Done state, ungranted`() = runTest(dispatcher) {
        val camera = "android.permission.CAMERA"
        val apk = apkBytes(
            splits = listOf(abiSplit("arm64_v8a")),
            capturedPermissions = listOf(PermissionAllowlist.INTERNET, camera),
        )
        val silent = ApkSilentInstaller { _, _ -> ApkInstallResult.Installed }
        val grantedPerms = mutableListOf<String>()
        val granter = RuntimePermissionGranter { _, perms -> grantedPerms += perms; perms.toSet() }
        val target = TargetDeclaredPermissions { setOf(PermissionAllowlist.INTERNET, camera) }
        val vm = viewModel(
            channelFor(apk), silent = silent, hasSilent = { true }, granter = granter, targetDeclared = target,
        )
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        // CAMERA is opt-in: surfaced for an explicit confirm, NOT auto-granted.
        assertThat(done.optInPermissions.map { it.packageName }).containsExactly("com.example.app")
        assertThat(done.optInPermissions.single().permissions).containsExactly(camera)
        // Only the default-safe perm was actually granted; the dangerous one was never sent to the granter.
        assertThat(grantedPerms).containsExactly(PermissionAllowlist.INTERNET)
        assertThat(done.restoredPermissions.single().permissions).containsExactly(PermissionAllowlist.INTERNET)
    }

    @Test
    fun `the Tier-0 fallback surfaces no opt-in permissions`() = runTest(dispatcher) {
        val camera = "android.permission.CAMERA"
        val apk = apkBytes(splits = listOf(abiSplit("arm64_v8a")), capturedPermissions = listOf(camera))
        val target = TargetDeclaredPermissions { setOf(camera) }
        val vm = viewModel(channelFor(apk), targetDeclared = target) // hasSilent=false → Tier-0
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.apkInstallPrompts).isNotEmpty()
        assertThat(done.optInPermissions).isEmpty()
    }

    @Test
    fun `reset clears opt-in permissions so they never leak into the next transfer`() = runTest(dispatcher) {
        val camera = "android.permission.CAMERA"
        val apk = apkBytes(splits = listOf(abiSplit("arm64_v8a")), capturedPermissions = listOf(camera))
        val silent = ApkSilentInstaller { _, _ -> ApkInstallResult.Installed }
        val target = TargetDeclaredPermissions { setOf(camera) }
        val vm = viewModel(channelFor(apk), silent = silent, hasSilent = { true }, targetDeclared = target)
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.optInPermissions).isNotEmpty()
        vm.reset()
        assertThat(vm.optInPermissions.value).isEmpty()
    }

    // ---- P5d-2: the user-driven opt-in grant on the Done screen (ADR-006 D5) -------------------------

    /** Records every grant call so a test can assert exactly what reached the bridge. */
    private class RecordingGranter(
        private val confirms: (List<String>) -> Set<String> = { it.toSet() },
    ) : RuntimePermissionGranter {
        val calls = mutableListOf<Pair<String, List<String>>>()
        override suspend fun grant(packageName: String, permissions: List<String>): Set<String> {
            calls += packageName to permissions
            return confirms(permissions)
        }
    }

    /** Build a Done state carrying [captured] dangerous perms as opt-in for com.example.app via silent install. */
    private fun doneWithOptIn(
        captured: List<String>,
        optInGranter: RuntimePermissionGranter,
    ): ReceiverViewModel {
        val apk = apkBytes(splits = listOf(abiSplit("arm64_v8a")), capturedPermissions = captured)
        val vm = viewModel(
            channelFor(apk),
            silent = ApkSilentInstaller { _, _ -> ApkInstallResult.Installed },
            hasSilent = { true },
            granter = RuntimePermissionGranter { _, perms -> perms.toSet() }, // auto path grants default-safe
            targetDeclared = TargetDeclaredPermissions { captured.toSet() },
            optInGranter = optInGranter,
        )
        return vm
    }

    @Test
    fun `grantOptIn grants an offered perm and moves it from opt-in to restored`() = runTest(dispatcher) {
        val granter = RecordingGranter()
        val vm = doneWithOptIn(listOf(PermissionAllowlist.INTERNET, CAMERA), granter)
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.optInPermissions.single().permissions).containsExactly(CAMERA)

        vm.grantOptIn("com.example.app", listOf(CAMERA))
        advanceUntilIdle()

        assertThat(granter.calls).containsExactly("com.example.app" to listOf(CAMERA))
        val after = vm.state.value as ReceiverState.Done
        assertThat(after.optInPermissions).isEmpty() // emptied entry dropped
        // INTERNET was auto-granted on install; CAMERA folds in after the opt-in confirm.
        assertThat(after.restoredPermissions.single().permissions)
            .containsExactly(PermissionAllowlist.INTERNET, CAMERA).inOrder()
        // The standalone flows stay in lockstep with the Done snapshot (no drift, cleared on reset).
        assertThat(vm.optInPermissions.value).isEmpty()
    }

    @Test
    fun `grantOptIn never passes a permission outside the offered opt-in set to the granter (belt)`() =
        runTest(dispatcher) {
            val granter = RecordingGranter()
            val vm = doneWithOptIn(listOf(CAMERA), granter) // only CAMERA is offered
            TestScopeRun(vm) { advanceUntilIdle() }

            // Ask for an un-offered dangerous perm alongside the offered one.
            vm.grantOptIn("com.example.app", listOf(RECORD_AUDIO, CAMERA))
            advanceUntilIdle()

            // Only CAMERA ever reached the bridge — the un-offered perm was filtered before any pm grant.
            assertThat(granter.calls).containsExactly("com.example.app" to listOf(CAMERA))
            val after = vm.state.value as ReceiverState.Done
            assertThat(after.optInPermissions).isEmpty()
            assertThat(after.restoredPermissions.flatMap { it.permissions }).doesNotContain(RECORD_AUDIO)
        }

    @Test
    fun `grantOptIn for an entirely un-offered request never touches the granter`() = runTest(dispatcher) {
        val granter = RecordingGranter()
        val vm = doneWithOptIn(listOf(CAMERA), granter)
        TestScopeRun(vm) { advanceUntilIdle() }

        vm.grantOptIn("com.example.app", listOf(RECORD_AUDIO)) // never offered
        advanceUntilIdle()

        assertThat(granter.calls).isEmpty()
        val after = vm.state.value as ReceiverState.Done
        assertThat(after.optInPermissions.single().permissions).containsExactly(CAMERA) // unchanged
    }

    @Test
    fun `grantOptIn for an unknown package never touches the granter`() = runTest(dispatcher) {
        val granter = RecordingGranter()
        val vm = doneWithOptIn(listOf(CAMERA), granter)
        TestScopeRun(vm) { advanceUntilIdle() }

        vm.grantOptIn("com.other.app", listOf(CAMERA)) // not the package we offered for
        advanceUntilIdle()

        assertThat(granter.calls).isEmpty()
        val after = vm.state.value as ReceiverState.Done
        assertThat(after.optInPermissions.single().permissions).containsExactly(CAMERA)
    }

    @Test
    fun `grantOptIn moves only the perms the granter confirms (partial result)`() = runTest(dispatcher) {
        val granter = RecordingGranter { setOf(CAMERA) } // bridge grants CAMERA, denies LOCATION
        val vm = doneWithOptIn(listOf(CAMERA, FINE_LOCATION), granter)
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.optInPermissions.single().permissions).containsExactly(CAMERA, FINE_LOCATION).inOrder()

        vm.grantOptIn("com.example.app", listOf(CAMERA, FINE_LOCATION))
        advanceUntilIdle()

        val after = vm.state.value as ReceiverState.Done
        assertThat(after.optInPermissions.single().permissions).containsExactly(FINE_LOCATION) // CAMERA moved out
        assertThat(after.restoredPermissions.single().permissions).containsExactly(CAMERA)
    }

    @Test
    fun `grantOptIn with an empty granter result leaves the perm offered (best-effort)`() = runTest(dispatcher) {
        val granter = RecordingGranter { emptySet() } // e.g. no live bridge
        val vm = doneWithOptIn(listOf(CAMERA), granter)
        TestScopeRun(vm) { advanceUntilIdle() }

        vm.grantOptIn("com.example.app", listOf(CAMERA))
        advanceUntilIdle()

        assertThat(granter.calls).containsExactly("com.example.app" to listOf(CAMERA)) // attempted
        val after = vm.state.value as ReceiverState.Done
        assertThat(after.optInPermissions.single().permissions).containsExactly(CAMERA) // still offered
        assertThat(after.restoredPermissions.flatMap { it.permissions }).doesNotContain(CAMERA)
    }

    @Test
    fun `two grants for the same app compose without clobbering each other`() = runTest(dispatcher) {
        // Both calls are issued before either coroutine runs, so each passes the belt against the SAME
        // (full) offered set. They must still compose: the second grant re-reads the live snapshot the
        // first wrote, so neither overwrites the other (the invariant moveOptInToRestored documents).
        val granter = RecordingGranter()
        val vm = doneWithOptIn(listOf(CAMERA, FINE_LOCATION), granter)
        val done = TestScopeRun(vm) { advanceUntilIdle() }
        assertThat(done.optInPermissions.single().permissions).containsExactly(CAMERA, FINE_LOCATION).inOrder()

        vm.grantOptIn("com.example.app", listOf(CAMERA))
        vm.grantOptIn("com.example.app", listOf(FINE_LOCATION))
        advanceUntilIdle()

        val after = vm.state.value as ReceiverState.Done
        assertThat(after.optInPermissions).isEmpty() // both moved out — neither clobbered the other
        assertThat(after.restoredPermissions.single().permissions).containsExactly(CAMERA, FINE_LOCATION)
    }

    @Test
    fun `a grant that lands after reset never repopulates the cleared state (race)`() = runTest(dispatcher) {
        // The user taps grant, then returns Home before the bridge call returns. reset() cancels nothing on
        // viewModelScope, so the late grant must find itself off the Done screen and do nothing.
        val gate = CompletableDeferred<Unit>()
        val granter = object : RuntimePermissionGranter {
            override suspend fun grant(packageName: String, permissions: List<String>): Set<String> {
                gate.await() // park until the test releases it
                return permissions.toSet()
            }
        }
        val vm = doneWithOptIn(listOf(CAMERA), granter)
        TestScopeRun(vm) { advanceUntilIdle() }

        vm.grantOptIn("com.example.app", listOf(CAMERA))
        advanceUntilIdle() // grant coroutine is now parked inside the granter
        vm.reset()
        assertThat(vm.optInPermissions.value).isEmpty()
        assertThat(vm.restoredPermissions.value).isEmpty()

        gate.complete(Unit) // the bridge call finally returns, post-reset
        advanceUntilIdle()

        assertThat(vm.optInPermissions.value).isEmpty()
        assertThat(vm.restoredPermissions.value).isEmpty() // no stale entry leaks into the next transfer
        assertThat(vm.state.value).isEqualTo(ReceiverState.Idle)
    }

    private companion object {
        const val APK_ITEM_ID = 9
        const val CAMERA = "android.permission.CAMERA"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
        const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    }
}
