/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.privileged

import cc.grepon.portage.privileged.PrivilegedOps.Availability
import cc.grepon.portage.privileged.PrivilegedOps.GrantOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fake [ShizukuGate]. [exitCode] is what a (precondition-satisfied) `pm grant` returns; null
 * models a bind/exec failure. [commands] records every argv handed to the shell so tests can
 * assert the bridge runs the exact, fixed `pm grant` and nothing else.
 */
private class FakeShizukuGate(
    private val installed: Boolean = true,
    private val binderAlive: Boolean = true,
    private val preV11: Boolean = false,
    private val permission: Boolean = true,
    private val exitCode: Int? = 0,
    private val hang: Boolean = false,
    private val permissionGranted: Boolean = false,
    private val permissionHang: Boolean = false,
) : ShizukuGate {
    val commands = mutableListOf<List<String>>()
    var permissionRequested = false

    override fun isInstalled(): Boolean = installed
    override fun isBinderAlive(): Boolean = binderAlive
    override fun isPreV11(): Boolean = preV11
    override fun hasPermission(): Boolean = permission
    override suspend fun runAsShell(command: List<String>): Int? {
        commands.add(command)
        if (hang) awaitCancellation() // models a bind that is accepted but never connects
        return exitCode
    }

    override suspend fun requestPermission(): Boolean {
        permissionRequested = true
        if (permissionHang) awaitCancellation() // models a dialog the user never answers
        return permissionGranted
    }
}

class ShizukuPrivilegedOpsTest {

    private fun ops(gate: FakeShizukuGate) = ShizukuPrivilegedOps(SELF_PACKAGE, gate)

    // --- availability() ---

    @Test
    fun `availability is LIVE when binder alive, modern, and permission held`() {
        val a = ops(FakeShizukuGate()).availability()
        assertThat(a).isEqualTo(Availability.LIVE)
    }

    @Test
    fun `availability is PERMISSION_DENIED when running but permission not held`() {
        val a = ops(FakeShizukuGate(permission = false)).availability()
        assertThat(a).isEqualTo(Availability.PERMISSION_DENIED)
    }

    @Test
    fun `availability is INSTALLED_NOT_RUNNING when installed but binder dead`() {
        val a = ops(FakeShizukuGate(installed = true, binderAlive = false)).availability()
        assertThat(a).isEqualTo(Availability.INSTALLED_NOT_RUNNING)
    }

    @Test
    fun `availability is NOT_INSTALLED when absent and binder dead`() {
        val a = ops(FakeShizukuGate(installed = false, binderAlive = false)).availability()
        assertThat(a).isEqualTo(Availability.NOT_INSTALLED)
    }

    @Test
    fun `availability is OUTDATED for a running pre-v11 server`() {
        val a = ops(FakeShizukuGate(preV11 = true)).availability()
        assertThat(a).isEqualTo(Availability.OUTDATED)
    }

    // --- ensureWriteSecureSettingsGranted() ---

    @Test
    fun `grant runs the exact pm grant argv for our own package`() = runTest {
        val gate = FakeShizukuGate(exitCode = 0)
        ops(gate).ensureWriteSecureSettingsGranted()

        assertThat(gate.commands).containsExactly(
            listOf("pm", "grant", SELF_PACKAGE, "android.permission.WRITE_SECURE_SETTINGS"),
        )
    }

    @Test
    fun `grant is GRANTED on a zero exit code`() = runTest {
        val outcome = ops(FakeShizukuGate(exitCode = 0)).ensureWriteSecureSettingsGranted()
        assertThat(outcome).isEqualTo(GrantOutcome.GRANTED)
    }

    @Test
    fun `grant is GRANT_REJECTED on a non-zero exit code`() = runTest {
        val outcome = ops(FakeShizukuGate(exitCode = 255)).ensureWriteSecureSettingsGranted()
        assertThat(outcome).isEqualTo(GrantOutcome.GRANT_REJECTED)
    }

    @Test
    fun `grant is BRIDGE_UNAVAILABLE when the bind or exec could not run`() = runTest {
        val outcome = ops(FakeShizukuGate(exitCode = null)).ensureWriteSecureSettingsGranted()
        assertThat(outcome).isEqualTo(GrantOutcome.BRIDGE_UNAVAILABLE)
    }

    @Test
    fun `grant times out to BRIDGE_UNAVAILABLE when the bind never connects`() = runTest {
        // The gate suspends forever (a bind accepted but never connected). The bounded wait must
        // fail closed instead of hanging the apply. runTest advances virtual time past the cap.
        val outcome = ops(FakeShizukuGate(hang = true)).ensureWriteSecureSettingsGranted()
        assertThat(outcome).isEqualTo(GrantOutcome.BRIDGE_UNAVAILABLE)
    }

    @Test
    fun `grant does not run any command when the binder is dead`() = runTest {
        val gate = FakeShizukuGate(binderAlive = false)
        val outcome = ops(gate).ensureWriteSecureSettingsGranted()

        assertThat(outcome).isEqualTo(GrantOutcome.BRIDGE_UNAVAILABLE)
        assertThat(gate.commands).isEmpty()
    }

    @Test
    fun `grant does not run any command when the permission is not held`() = runTest {
        val gate = FakeShizukuGate(permission = false)
        val outcome = ops(gate).ensureWriteSecureSettingsGranted()

        assertThat(outcome).isEqualTo(GrantOutcome.BRIDGE_UNAVAILABLE)
        assertThat(gate.commands).isEmpty()
    }

    @Test
    fun `grant does not run any command on a pre-v11 server`() = runTest {
        val gate = FakeShizukuGate(preV11 = true)
        val outcome = ops(gate).ensureWriteSecureSettingsGranted()

        assertThat(outcome).isEqualTo(GrantOutcome.BRIDGE_UNAVAILABLE)
        assertThat(gate.commands).isEmpty()
    }

    // --- requestAccess() ---

    @Test
    fun `requestAccess returns true without prompting when already authorized`() = runTest {
        val gate = FakeShizukuGate(permission = true)
        assertThat(ops(gate).requestAccess()).isTrue()
        assertThat(gate.permissionRequested).isFalse()
    }

    @Test
    fun `requestAccess returns false without prompting when the binder is dead`() = runTest {
        val gate = FakeShizukuGate(binderAlive = false)
        assertThat(ops(gate).requestAccess()).isFalse()
        assertThat(gate.permissionRequested).isFalse()
    }

    @Test
    fun `requestAccess returns false without prompting on a pre-v11 server`() = runTest {
        val gate = FakeShizukuGate(preV11 = true)
        assertThat(ops(gate).requestAccess()).isFalse()
        assertThat(gate.permissionRequested).isFalse()
    }

    @Test
    fun `requestAccess returns true when the user grants`() = runTest {
        val gate = FakeShizukuGate(permission = false, permissionGranted = true)
        assertThat(ops(gate).requestAccess()).isTrue()
        assertThat(gate.permissionRequested).isTrue()
    }

    @Test
    fun `requestAccess returns false when the user declines`() = runTest {
        val gate = FakeShizukuGate(permission = false, permissionGranted = false)
        assertThat(ops(gate).requestAccess()).isFalse()
        assertThat(gate.permissionRequested).isTrue()
    }

    @Test
    fun `requestAccess times out to false when the dialog is never answered`() = runTest {
        // The gate suspends forever (a dialog the user never answers). The bounded wait must fail
        // closed instead of hanging the unlock. runTest advances virtual time past the cap.
        val gate = FakeShizukuGate(permission = false, permissionHang = true)
        assertThat(ops(gate).requestAccess()).isFalse()
        // The timeout must fire on the dialog-await, not on an earlier precondition exit.
        assertThat(gate.permissionRequested).isTrue()
    }

    @Test
    fun `the deferred privileged ops report the bridge as unavailable`() = runTest {
        val o = ops(FakeShizukuGate())
        assertThat(o.grantRuntimePermission("p", "perm")).isEqualTo(PrivilegedOps.OpResult.BridgeUnavailable)
        assertThat(o.installApk("/x")).isEqualTo(PrivilegedOps.OpResult.BridgeUnavailable)
        assertThat(o.setSmsRoleHolder("p")).isEqualTo(PrivilegedOps.OpResult.BridgeUnavailable)
    }

    private companion object {
        const val SELF_PACKAGE = "cc.grepon.portage.recv"
    }
}
