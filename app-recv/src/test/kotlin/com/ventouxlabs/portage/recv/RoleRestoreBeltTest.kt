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

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemMeta
import com.ventouxlabs.portage.model.PairingPayload
import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.model.TransferManifest
import com.ventouxlabs.portage.providers.ApplyProviderRegistry
import com.ventouxlabs.portage.providers.roles.DefaultRolesApplyProvider
import com.ventouxlabs.portage.providers.roles.RestorableRole
import com.ventouxlabs.portage.providers.roles.RoleRestorer
import com.ventouxlabs.portage.transport.PairingCodec
import com.ventouxlabs.portage.transport.SecureChannel
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
import java.security.MessageDigest

/**
 * The ViewModel-side consent belt for default-app role restore (#122).
 *
 * `RoleRestoreConsentTest` covers the [RoleRestorer] seam contract. THIS covers the belt that
 * actually protects the user: [ReceiverViewModel.restoreRole] must refuse any (role, package) pair
 * that is not currently OFFERED on the live Done state.
 *
 * It exists because a security review found the belt was bypassable across a transfer boundary:
 * `reset()` cleared every other Done-scoped flow but not `_roleCandidates` / `_restoredRoles`, so a
 * candidate from transfer #1 survived into transfer #2 and still validated — even when #2 carried
 * no DEFAULT_ROLES item at all. The seam-contract tests could not catch that; only a test that
 * drives a real transfer, resets, and drives another one can.
 */
class RoleRestoreBeltTest {

    private companion object { const val ROLES_ITEM_ID = 4 }

    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

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

    /** A transfer carrying exactly one DEFAULT_ROLES item with [json] as its payload. */
    private fun rolesChannel(json: String): SecureChannel {
        val bytes = json.toByteArray()
        val meta = ItemMeta(4, ItemKind.DEFAULT_ROLES, bytes.size.toLong(), sha256(bytes), "Default apps", "Apps")
        return FakeChannel(
            ProtocolMessage.Manifest(TransferManifest("old phone", listOf(meta), bytes.size.toLong())),
            ProtocolMessage.ItemBegin(4, ItemKind.DEFAULT_ROLES, meta.size, bytes.size),
            ProtocolMessage.ItemData(4, 0, bytes),
            ProtocolMessage.ItemEnd(4, meta.sha256),
            ProtocolMessage.BatchEnd(listOf(4), "done"),
        )
    }

    /** Records every restore that actually reached the seam. */
    private class RecordingRestorer : RoleRestorer {
        val calls = mutableListOf<Pair<RestorableRole, String>>()
        override suspend fun restore(role: RestorableRole, packageName: String): RoleRestorer.Outcome {
            calls += role to packageName
            return RoleRestorer.Outcome.RESTORED
        }
    }

    private fun viewModel(channel: SecureChannel, restorer: RoleRestorer): ReceiverViewModel =
        ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channel),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test",
            stagingDir = tmp.newFolder("staging-${System.nanoTime()}"),
            roleRestorer = restorer,
            canRestoreRoles = true,
            applyRegistryFactory = ApplyRegistryFactory { sinks ->
                ApplyProviderRegistry(
                    listOf(
                        DefaultRolesApplyProvider(
                            isInstalled = { true },
                            onCandidates = sinks.onRoleCandidates,
                        ),
                    ),
                )
            },
        )

    /**
     * Drive a full receive to Done. Note [onToggle]: DEFAULT_ROLES is Tier 1, so it is NOT
     * pre-checked on the checklist — the user opts in there BEFORE the per-role tap on Done. That
     * is two independent consents, and this helper exercises both.
     */
    private fun ReceiverViewModel.runTransfer(itemId: Int?, advance: () -> Unit) {
        startScanning()
        onQrScanned("good-qr")
        advance()
        if (itemId != null) onToggle(itemId)
        onConfirm()
        advance()
    }

    @Test
    fun `a role the transfer DID carry restores`() = runTest(dispatcher) {
        val restorer = RecordingRestorer()
        val vm = viewModel(
            rolesChannel("""{"roles":[{"role":"browser","packageName":"com.example.browser"}]}"""),
            restorer,
        )
        vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }

        vm.restoreRole(RestorableRole.BROWSER, "com.example.browser")
        advanceUntilIdle()

        assertThat(restorer.calls).containsExactly(RestorableRole.BROWSER to "com.example.browser")
    }

    @Test
    fun `a role the transfer did NOT carry never reaches the seam`() = runTest(dispatcher) {
        val restorer = RecordingRestorer()
        val vm = viewModel(
            rolesChannel("""{"roles":[{"role":"browser","packageName":"com.example.browser"}]}"""),
            restorer,
        )
        vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }

        // Right role, WRONG package — the belt must reject the pair, not just the role.
        vm.restoreRole(RestorableRole.BROWSER, "com.attacker.app")
        // A role that was never offered at all.
        vm.restoreRole(RestorableRole.DIALER, "com.example.dialer")
        advanceUntilIdle()

        assertThat(restorer.calls).isEmpty()
    }

    @Test
    fun `reset clears the offer, so a candidate cannot survive into the NEXT transfer`() =
        runTest(dispatcher) {
            // The regression this file exists for. Transfer #1 offers BROWSER; the user does not
            // tap and returns Home. Transfer #2 carries NO roles at all. The stale candidate must
            // not still validate.
            val restorer = RecordingRestorer()
            val vm = viewModel(
                rolesChannel("""{"roles":[{"role":"browser","packageName":"com.example.browser"}]}"""),
                restorer,
            )
            vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }
            assertThat((vm.state.value as ReceiverState.Done).roleCandidates).hasSize(1)

            vm.reset()
            advanceUntilIdle()

            assertThat(vm.roleCandidates.value).isEmpty()
            assertThat(vm.restoredRoles.value).isEmpty()

            // Attempting the previously-offered pair after the reset must reach nothing.
            vm.restoreRole(RestorableRole.BROWSER, "com.example.browser")
            advanceUntilIdle()
            assertThat(restorer.calls).isEmpty()
        }

    @Test
    fun `a successful restore removes the offer, so it cannot be replayed`() = runTest(dispatcher) {
        val restorer = RecordingRestorer()
        val vm = viewModel(
            rolesChannel("""{"roles":[{"role":"home","packageName":"com.example.home"}]}"""),
            restorer,
        )
        vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }

        vm.restoreRole(RestorableRole.HOME, "com.example.home")
        advanceUntilIdle()
        assertThat(restorer.calls).hasSize(1)
        assertThat(vm.restoredRoles.value).containsExactly(RestorableRole.HOME)

        // Second tap on the same row: the candidate is gone, so the belt refuses it.
        vm.restoreRole(RestorableRole.HOME, "com.example.home")
        advanceUntilIdle()
        assertThat(restorer.calls).hasSize(1)
    }

    @Test
    fun `a restorer that does not confirm leaves the offer in place and claims nothing`() =
        runTest(dispatcher) {
            val refusing = RoleRestorer { _, _ -> RoleRestorer.Outcome.REJECTED }
            val vm = viewModel(
                rolesChannel("""{"roles":[{"role":"dialer","packageName":"com.example.dialer"}]}"""),
                refusing,
            )
            vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }

            vm.restoreRole(RestorableRole.DIALER, "com.example.dialer")
            advanceUntilIdle()

            // Still offered, and NOT claimed as restored — portage must never report a default it
            // did not set.
            assertThat(vm.roleCandidates.value).hasSize(1)
            assertThat(vm.restoredRoles.value).isEmpty()
        }
}
