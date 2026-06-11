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

import cc.grepon.portage.model.PairingPayload
import cc.grepon.portage.privileged.PrivilegedAccess
import cc.grepon.portage.privileged.PrivilegedOps
import cc.grepon.portage.recv.shizuku.ShizukuAccessStrand
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

/**
 * The optional secure-settings unlock orchestration. The transfer flow is irrelevant here (the
 * channel is never opened), so this exercises only the [PrivilegedAccess] interaction against a
 * fake — independent of the device-only Shizuku wiring in `:privileged`.
 */
class ReceiverViewModelShizukuTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val tmp = TemporaryFolder()

    /** The unlock flow never touches the channel; this fake just satisfies the constructor. */
    private val unusedFactory = object : SecureChannel.Factory {
        override suspend fun connectAsReceiver(payload: PairingPayload): SecureChannel = error("unused")
        override suspend fun acceptAsSender(payload: PairingPayload): SecureChannel = error("unused")
    }

    private class FakePrivilegedAccess(
        var nextAvailability: PrivilegedOps.Availability = PrivilegedOps.Availability.NOT_INSTALLED,
        var canWrite: Boolean = false,
        var authorize: Boolean = false,
        var grantResult: PrivilegedOps.GrantOutcome = PrivilegedOps.GrantOutcome.BRIDGE_UNAVAILABLE,
    ) : PrivilegedAccess {
        var requestCalls = 0
        var grantCalls = 0
        override fun availability(): PrivilegedOps.Availability = nextAvailability
        override fun canWriteSecureSettings(): Boolean = canWrite
        override suspend fun requestAccess(): Boolean {
            requestCalls++
            // A successful Shizuku authorization makes the bridge LIVE; model that so the retry and
            // already-authorized paths run exactly as they would on a real device (no re-prompt).
            if (authorize) nextAvailability = PrivilegedOps.Availability.LIVE
            return authorize
        }
        override suspend fun ensureWriteSecureSettingsGranted(): PrivilegedOps.GrantOutcome {
            grantCalls++
            return grantResult
        }
    }

    private fun viewModel(access: PrivilegedAccess) = ReceiverViewModel(
        channelFactory = unusedFactory,
        nowEpochSeconds = { 1_000 },
        appVersion = "test",
        osFingerprint = "test-fingerprint",
        stagingDir = tmp.root,
        privilegedAccess = access,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial strand is derived from the bridge at construction`() = runTest(dispatcher) {
        val vm = viewModel(FakePrivilegedAccess(PrivilegedOps.Availability.PERMISSION_DENIED))
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.LOCKED)
    }

    @Test
    fun `a held grant reads as UNLOCKED from the start`() = runTest(dispatcher) {
        val vm = viewModel(FakePrivilegedAccess(PrivilegedOps.Availability.LIVE, canWrite = true))
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.UNLOCKED)
    }

    @Test
    fun `unlock from LOCKED authorizes then grants through to UNLOCKED`() = runTest(dispatcher) {
        val access = FakePrivilegedAccess(
            nextAvailability = PrivilegedOps.Availability.PERMISSION_DENIED,
            authorize = true,
            grantResult = PrivilegedOps.GrantOutcome.GRANTED,
        )
        val vm = viewModel(access)

        vm.unlockSecureSettings()
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.UNLOCKING) // set before the launch runs
        advanceUntilIdle()

        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.UNLOCKED)
        assertThat(access.requestCalls).isEqualTo(1)
        assertThat(access.grantCalls).isEqualTo(1)
    }

    @Test
    fun `unlock when already authorized skips the permission request`() = runTest(dispatcher) {
        val access = FakePrivilegedAccess(
            nextAvailability = PrivilegedOps.Availability.LIVE, // Shizuku permission already held
            grantResult = PrivilegedOps.GrantOutcome.GRANTED,
        )
        val vm = viewModel(access)

        vm.unlockSecureSettings()
        advanceUntilIdle()

        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.UNLOCKED)
        assertThat(access.requestCalls).isEqualTo(0) // LIVE → no second prompt
        assertThat(access.grantCalls).isEqualTo(1)
    }

    @Test
    fun `a declined authorization falls back to the derived strand`() = runTest(dispatcher) {
        val access = FakePrivilegedAccess(
            nextAvailability = PrivilegedOps.Availability.PERMISSION_DENIED,
            authorize = false, // user declined the Shizuku dialog
        )
        val vm = viewModel(access)

        vm.unlockSecureSettings()
        advanceUntilIdle()

        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.LOCKED)
        assertThat(access.grantCalls).isEqualTo(0) // never reached the grant
    }

    @Test
    fun `a rejected grant surfaces GRANT_FAILED, and a retry re-attempts`() = runTest(dispatcher) {
        val access = FakePrivilegedAccess(
            nextAvailability = PrivilegedOps.Availability.PERMISSION_DENIED,
            authorize = true,
            grantResult = PrivilegedOps.GrantOutcome.GRANT_REJECTED,
        )
        val vm = viewModel(access)

        vm.unlockSecureSettings()
        advanceUntilIdle()
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.GRANT_FAILED)

        // Retry is allowed from GRANT_FAILED; this time the grant takes.
        access.grantResult = PrivilegedOps.GrantOutcome.GRANTED
        vm.unlockSecureSettings()
        advanceUntilIdle()

        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.UNLOCKED)
        // After the first authorization the bridge is LIVE, so the retry takes the no-reprompt path
        // and only re-runs the grant — the actual on-device control flow.
        assertThat(access.requestCalls).isEqualTo(1)
        assertThat(access.grantCalls).isEqualTo(2)
    }

    @Test
    fun `a second unlock while one is in flight is ignored`() = runTest(dispatcher) {
        val access = FakePrivilegedAccess(
            nextAvailability = PrivilegedOps.Availability.PERMISSION_DENIED,
            authorize = true,
            grantResult = PrivilegedOps.GrantOutcome.GRANTED,
        )
        val vm = viewModel(access)

        vm.unlockSecureSettings() // → UNLOCKING, work queued
        vm.unlockSecureSettings() // no-op: strand is UNLOCKING, not actionable
        advanceUntilIdle()

        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.UNLOCKED)
        assertThat(access.requestCalls).isEqualTo(1) // only the first attempt ran
        assertThat(access.grantCalls).isEqualTo(1)
    }

    @Test
    fun `refreshShizukuAccess does not clobber an in-flight unlock`() = runTest(dispatcher) {
        val access = FakePrivilegedAccess(
            nextAvailability = PrivilegedOps.Availability.PERMISSION_DENIED,
            authorize = true,
            grantResult = PrivilegedOps.GrantOutcome.GRANTED,
        )
        val vm = viewModel(access)

        vm.unlockSecureSettings() // → UNLOCKING (not yet advanced)
        vm.refreshShizukuAccess() // a resume/refresh mid-unlock must not reset it to LOCKED
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.UNLOCKING)

        advanceUntilIdle()
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.UNLOCKED)
    }

    @Test
    fun `unlock is a no-op in a guidance state`() = runTest(dispatcher) {
        val access = FakePrivilegedAccess(PrivilegedOps.Availability.INSTALLED_NOT_RUNNING)
        val vm = viewModel(access)
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.NOT_RUNNING)

        vm.unlockSecureSettings()
        advanceUntilIdle()

        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.NOT_RUNNING)
        assertThat(access.requestCalls).isEqualTo(0)
        assertThat(access.grantCalls).isEqualTo(0)
    }

    @Test
    fun `refreshShizukuAccess picks up an out-of-app bridge change`() = runTest(dispatcher) {
        // Shizuku absent at construction → the affordance is hidden.
        val access = FakePrivilegedAccess(PrivilegedOps.Availability.NOT_INSTALLED)
        val vm = viewModel(access)
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.NOT_INSTALLED)

        // User starts/authorizes-to-reachable Shizuku OUTSIDE the app: now reachable, not yet granted.
        access.nextAvailability = PrivilegedOps.Availability.PERMISSION_DENIED
        vm.refreshShizukuAccess()
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.LOCKED)

        // The one-shot WRITE_SECURE_SETTINGS grant lands (e.g. from a prior run): a held grant wins.
        access.canWrite = true
        vm.refreshShizukuAccess()
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.UNLOCKED)
    }

    @Test
    fun `unlockSecureSettings recovers via the derived strand on BRIDGE_UNAVAILABLE`() = runTest(dispatcher) {
        // Authorized (requestAccess true → bridge LIVE) but the grant bind drops: BRIDGE_UNAVAILABLE.
        // With canWrite still false, the post-failure derivation is LOCKED (LIVE + not-yet-granted).
        val access = FakePrivilegedAccess(
            nextAvailability = PrivilegedOps.Availability.PERMISSION_DENIED,
            authorize = true,
            grantResult = PrivilegedOps.GrantOutcome.BRIDGE_UNAVAILABLE,
        )
        val vm = viewModel(access)
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.LOCKED)

        vm.unlockSecureSettings()
        advanceUntilIdle()

        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.LOCKED) // fell back to derived
        assertThat(access.grantCalls).isEqualTo(1) // the grant was attempted exactly once
    }

    @Test
    fun `the inert default reports NOT_INSTALLED and unlock does nothing`() = runTest(dispatcher) {
        // No privilegedAccess wired (production default) → the affordance is inert.
        val vm = ReceiverViewModel(
            channelFactory = unusedFactory,
            nowEpochSeconds = { 1_000 },
            appVersion = "test",
            osFingerprint = "test-fingerprint",
            stagingDir = tmp.root,
        )
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.NOT_INSTALLED)

        vm.unlockSecureSettings()
        advanceUntilIdle()
        assertThat(vm.shizukuAccess.value).isEqualTo(ShizukuAccessStrand.NOT_INSTALLED)
    }
}
