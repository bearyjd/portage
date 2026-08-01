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

    /**
     * A transfer carrying exactly one DEFAULT_ROLES item with [json] as its payload.
     *
     * When [thenBreakProtocol] is set the stream ends with a message the receiver cannot accept
     * there (HELLO where ITEM_BEGIN or BATCH_END is required) instead of BATCH_END, so the transfer
     * lands in `Failed` with the roles item ALREADY applied — the state that lets a carried role
     * outlive its transfer without ever passing through `reset()`.
     */
    private fun rolesChannel(json: String, thenBreakProtocol: Boolean = false): SecureChannel {
        val bytes = json.toByteArray()
        val meta = ItemMeta(4, ItemKind.DEFAULT_ROLES, bytes.size.toLong(), sha256(bytes), "Default apps", "Apps")
        val tail: ProtocolMessage =
            if (thenBreakProtocol) ProtocolMessage.Hello("x", "x")
            else ProtocolMessage.BatchEnd(listOf(4), "done")
        return FakeChannel(
            ProtocolMessage.Manifest(TransferManifest("old phone", listOf(meta), bytes.size.toLong())),
            ProtocolMessage.ItemBegin(4, ItemKind.DEFAULT_ROLES, meta.size, bytes.size),
            ProtocolMessage.ItemData(4, 0, bytes),
            ProtocolMessage.ItemEnd(4, meta.sha256),
            tail,
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

    /**
     * The device's installed set, MUTABLE so a test can model what actually happens on Tier-0: the
     * apps this transfer carries appear only after the user confirms the system install dialogs,
     * i.e. strictly after the DEFAULT_ROLES item has applied.
     */
    private val installed = mutableSetOf("com.example.browser", "com.example.dialer", "com.example.home")

    private fun viewModel(
        channel: SecureChannel,
        restorer: RoleRestorer,
        canRestoreRoles: Boolean = true,
    ): ReceiverViewModel =
        ReceiverViewModel(
            pairingCodec = FakeCodec(),
            channelFactory = FakeFactory(channel),
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test",
            stagingDir = tmp.newFolder("staging-${System.nanoTime()}"),
            roleRestorer = restorer,
            canRestoreRoles = canRestoreRoles,
            installedPackages = { installed.toSet() },
            applyRegistryFactory = ApplyRegistryFactory { sinks ->
                ApplyProviderRegistry(
                    listOf(DefaultRolesApplyProvider(canRestore = { true }, onCandidates = sinks.onRoleCandidates)),
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

    @Test
    fun `an app installed AFTER the transfer becomes restorable on resume — the headline case`() =
        runTest(dispatcher) {
            // THE regression pin for the whole feature. "Restore my defaults after reinstalling my
            // apps" only works if installedness is re-read after the installs land, and on Tier-0
            // they land after the transfer: ApkApplyProvider.apply returns when the install PROMPT
            // is surfaced, and the user confirms the system dialog from the Done screen. So at the
            // moment DEFAULT_ROLES applies, the carried app genuinely does not exist yet.
            //
            // The previous design read the installed set inside apply() and filtered there, which
            // produced ZERO candidates in exactly this case — the feature's headline scenario. No
            // reordering of the item stream fixes that; the install has not happened at any point
            // during the stream.
            val restorer = RecordingRestorer()
            installed.remove("com.example.browser")
            val vm = viewModel(
                rolesChannel("""{"roles":[{"role":"browser","packageName":"com.example.browser"}]}"""),
                restorer,
            )
            vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }

            // Not installed yet ⇒ carried, but not offered, and a tap reaches nothing.
            assertThat((vm.state.value as ReceiverState.Done).roleCandidates).isEmpty()
            vm.restoreRole(RestorableRole.BROWSER, "com.example.browser")
            advanceUntilIdle()
            assertThat(restorer.calls).isEmpty()

            // The user confirms the system install dialog and returns to portage.
            installed.add("com.example.browser")
            vm.refreshRoleCandidates()

            assertThat((vm.state.value as ReceiverState.Done).roleCandidates).hasSize(1)
            vm.restoreRole(RestorableRole.BROWSER, "com.example.browser")
            advanceUntilIdle()
            assertThat(restorer.calls)
                .containsExactly(RestorableRole.BROWSER to "com.example.browser")
        }

    @Test
    fun `an app uninstalled while Done is up stops being restorable`() = runTest(dispatcher) {
        // The other direction of the same liveness property, and the reason the tap-time re-read is
        // a belt rather than a nicety: the offered list is built from a read that may be minutes
        // old. Here the row is on screen and valid when rendered, and the app is gone by the tap.
        val restorer = RecordingRestorer()
        val vm = viewModel(
            rolesChannel("""{"roles":[{"role":"dialer","packageName":"com.example.dialer"}]}"""),
            restorer,
        )
        vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }
        assertThat((vm.state.value as ReceiverState.Done).roleCandidates).hasSize(1)

        // Uninstalled behind the still-rendered row — Done is NOT refreshed, so the stale offer is
        // deliberately still present in state. Only the tap-time read can catch this.
        installed.remove("com.example.dialer")

        vm.restoreRole(RestorableRole.DIALER, "com.example.dialer")
        advanceUntilIdle()
        assertThat(restorer.calls).isEmpty()
    }

    @Test
    fun `a restorer that THROWS leaves the row offered instead of taking the process down`() =
        runTest(dispatcher) {
            // An uncaught throw here reaches viewModelScope's handler and crashes the app on the
            // Done screen, with the transfer's results still on it. The bridge is a network client
            // over localhost TLS, so throwing is an ordinary outcome, not an exotic one.
            val throwing = RoleRestorer { _, _ -> error("bridge exploded") }
            val vm = viewModel(
                rolesChannel("""{"roles":[{"role":"home","packageName":"com.example.home"}]}"""),
                throwing,
            )
            vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }

            vm.restoreRole(RestorableRole.HOME, "com.example.home")
            advanceUntilIdle()

            assertThat(vm.restoredRoles.value).isEmpty()
            assertThat(vm.roleCandidates.value).hasSize(1)
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Done::class.java)
        }

    @Test
    fun `startScanning clears carried roles, so a FAILED transfer cannot leak into the next`() =
        runTest(dispatcher) {
            // reset() covers the Done → Home exit. Failed → Scanning re-enters WITHOUT passing
            // through it, so the carried roles survived that path. That matters because the sink
            // appends with distinctBy { role }, which keeps the FIRST entry — a stale candidate
            // would SHADOW the next transfer's legitimate one for the same role.
            val restorer = RecordingRestorer()
            val vm = viewModel(
                rolesChannel(
                    """{"roles":[{"role":"browser","packageName":"com.example.browser"}]}""",
                    thenBreakProtocol = true,
                ),
                restorer,
            )
            vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }

            // The item applied, THEN the transfer died — so the roles are carried but there is no
            // Done screen and the user never passes through reset().
            assertThat(vm.state.value).isInstanceOf(ReceiverState.Failed::class.java)
            assertThat(vm.roleCandidates.value).hasSize(1)

            vm.startScanning()

            assertThat(vm.roleCandidates.value).isEmpty()
            assertThat(vm.restoredRoles.value).isEmpty()
        }

    @Test
    fun `a build that cannot restore offers nothing and cannot be driven to the seam`() =
        runTest(dispatcher) {
            // The play flavor ships no bridge. Surfacing a "SET" row it can never honour would be a
            // dead button, and this is the guard that prevents it — previously untested, so nothing
            // stopped a refactor from dropping the canRestoreRoles check and shipping the affordance
            // to a build that cannot act on it.
            val restorer = RecordingRestorer()
            val vm = viewModel(
                rolesChannel("""{"roles":[{"role":"browser","packageName":"com.example.browser"}]}"""),
                restorer,
                canRestoreRoles = false,
            )
            vm.runTransfer(ROLES_ITEM_ID) { advanceUntilIdle() }

            assertThat(vm.roleCandidates.value).isEmpty()
            assertThat((vm.state.value as ReceiverState.Done).roleCandidates).isEmpty()

            // ...and the belt still holds if something calls restoreRole anyway.
            vm.restoreRole(RestorableRole.BROWSER, "com.example.browser")
            advanceUntilIdle()
            assertThat(restorer.calls).isEmpty()
        }
}
