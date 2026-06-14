/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.adbbridge

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException

/**
 * The bridge's typed-result logic over a fake [AdbDeviceGate] — the same seam pattern the old
 * ShizukuGate tests used. The fake answers wrapped shell lines through [FakeGate.respond],
 * which understands the sentinel wrapping so tests assert on the INNER command.
 */
class LocalAdbBridgeTest {

    private val wrapPattern =
        Regex("^\\{ (.*) ; } 2>&1; echo ${LocalAdbBridge.EXIT_SENTINEL}\\$\\?$")

    private class FakeGate : AdbDeviceGate {
        var connected = false
        var closed = false
        var pairBehavior: suspend (Int, String) -> Unit = { _, _ -> }
        var connectBehavior: suspend (Long) -> Boolean = { true }
        var execBehavior: suspend (String) -> String = { "" }
        val pairCalls = mutableListOf<Pair<Int, String>>()
        val execCalls = mutableListOf<String>()

        override suspend fun pair(port: Int, pairingCode: String) {
            pairCalls += port to pairingCode
            pairBehavior(port, pairingCode)
        }

        override suspend fun connect(timeoutMs: Long): Boolean {
            val result = connectBehavior(timeoutMs)
            if (result) connected = true
            return result
        }

        override fun isConnected(): Boolean = connected

        override fun closeQuietly() {
            closed = true
            connected = false
        }

        override suspend fun exec(command: String): String {
            execCalls += command
            return execBehavior(command)
        }
    }

    /** Make the fake answer the INNER command with (exitCode, stdout), honoring the wrapping. */
    private fun FakeGate.respond(handler: (String) -> Pair<Int, String>) {
        execBehavior = { wrapped ->
            val inner = wrapPattern.matchEntire(wrapped)?.groupValues?.get(1)
                ?: error("command was not sentinel-wrapped: $wrapped")
            val (exit, out) = handler(inner)
            val body = if (out.isEmpty()) "" else out + "\n"
            body + LocalAdbBridge.EXIT_SENTINEL + exit + "\n"
        }
    }

    // The dispatcher MUST share runTest's scheduler or virtual time never advances for the
    // bridge's withTimeout calls and the timeout tests hang.
    private fun TestScope.bridge(
        gate: FakeGate,
        selfPackage: String = "cc.grepon.portage.recv",
    ) = LocalAdbBridge(
        selfPackage = selfPackage,
        gate = gate,
        io = UnconfinedTestDispatcher(testScheduler),
        pairTimeoutMs = 1_000,
        connectTimeoutMs = 1_000,
        shellTimeoutMs = 1_000,
    )

    // ── pairing ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `pair passes port and code through and maps success`() = runTest {
        val gate = FakeGate()
        val result = bridge(gate).pair(40123, "847291")
        assertThat(result).isEqualTo(AdbBridge.PairingResult.Paired)
        assertThat(gate.pairCalls).containsExactly(40123 to "847291")
    }

    @Test
    fun `pair maps an SSL abort to WrongCode`() = runTest {
        val gate = FakeGate().apply {
            pairBehavior = { _, _ -> throw SSLHandshakeException("spake2 mismatch") }
        }
        assertThat(bridge(gate).pair(40123, "000000"))
            .isEqualTo(AdbBridge.PairingResult.WrongCode)
    }

    @Test
    fun `pair maps a dead endpoint to Unavailable`() = runTest {
        val gate = FakeGate().apply {
            pairBehavior = { _, _ -> throw ConnectException("refused") }
        }
        assertThat(bridge(gate).pair(40123, "847291"))
            .isInstanceOf(AdbBridge.PairingResult.Unavailable::class.java)
    }

    @Test
    fun `pair times out as Timeout not an exception`() = runTest {
        val gate = FakeGate().apply { pairBehavior = { _, _ -> delay(60_000) } }
        assertThat(bridge(gate).pair(40123, "847291"))
            .isEqualTo(AdbBridge.PairingResult.Timeout)
    }

    // ── connect ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `connect maps success and an already-live link to Connected`() = runTest {
        val gate = FakeGate()
        assertThat(bridge(gate).connect()).isEqualTo(AdbBridge.ConnectionResult.Connected)

        val live = FakeGate().apply { connected = true }
        assertThat(bridge(live).connect()).isEqualTo(AdbBridge.ConnectionResult.Connected)
    }

    @Test
    fun `connect maps discovery interruption to NoEndpoint`() = runTest {
        val gate = FakeGate().apply {
            connectBehavior = { throw InterruptedException("Timed out while trying to find a valid host address and port") }
        }
        assertThat(bridge(gate).connect()).isEqualTo(AdbBridge.ConnectionResult.NoEndpoint)
    }

    @Test
    fun `connect maps the no-valid-host IOException to NoEndpoint`() = runTest {
        val gate = FakeGate().apply {
            connectBehavior = { throw IOException("Could not find any valid host address or port") }
        }
        assertThat(bridge(gate).connect()).isEqualTo(AdbBridge.ConnectionResult.NoEndpoint)
    }

    @Test
    fun `connect maps other IO failures to Rejected`() = runTest {
        val gate = FakeGate().apply { connectBehavior = { throw IOException("broken pipe") } }
        assertThat(bridge(gate).connect())
            .isInstanceOf(AdbBridge.ConnectionResult.Rejected::class.java)
    }

    @Test
    fun `connect times out as Timeout`() = runTest {
        val gate = FakeGate().apply { connectBehavior = { delay(60_000); true } }
        assertThat(bridge(gate).connect()).isEqualTo(AdbBridge.ConnectionResult.Timeout)
    }

    // ── shell ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `shell on a dead bridge is NotConnected and never reaches the gate`() = runTest {
        val gate = FakeGate()
        assertThat(bridge(gate).shell("id")).isEqualTo(AdbBridge.ShellResult.NotConnected)
        assertThat(gate.execCalls).isEmpty()
    }

    @Test
    fun `shell wraps the command with the exit sentinel and parses both streams`() = runTest {
        val gate = FakeGate().apply { connected = true }
        gate.respond { inner ->
            assertThat(inner).isEqualTo("id")
            0 to "uid=2000(shell) gid=2000(shell)"
        }
        val result = bridge(gate).shell("id")
        assertThat(result).isEqualTo(
            AdbBridge.ShellResult.Completed(0, "uid=2000(shell) gid=2000(shell)", ""),
        )
    }

    @Test
    fun `shell propagates a non-zero exit code as Completed not failure`() = runTest {
        val gate = FakeGate().apply { connected = true }
        gate.respond { 127 to "sh: nope: inaccessible or not found" }
        val result = bridge(gate).shell("nope")
        assertThat(result).isInstanceOf(AdbBridge.ShellResult.Completed::class.java)
        assertThat((result as AdbBridge.ShellResult.Completed).exitCode).isEqualTo(127)
        assertThat(result.ok).isFalse()
    }

    @Test
    fun `shell without a sentinel in the output is a transport failure`() = runTest {
        val gate = FakeGate().apply {
            connected = true
            execBehavior = { "partial output then the link died" }
        }
        assertThat(bridge(gate).shell("id"))
            .isInstanceOf(AdbBridge.ShellResult.TransportFailure::class.java)
    }

    @Test
    fun `shell maps a gate exception to TransportFailure`() = runTest {
        val gate = FakeGate().apply {
            connected = true
            execBehavior = { throw IOException("Stream closed.") }
        }
        assertThat(bridge(gate).shell("id"))
            .isInstanceOf(AdbBridge.ShellResult.TransportFailure::class.java)
    }

    @Test
    fun `shell times out as TransportFailure`() = runTest {
        val gate = FakeGate().apply {
            connected = true
            execBehavior = { delay(60_000); "" }
        }
        assertThat(bridge(gate).shell("sleep 100"))
            .isInstanceOf(AdbBridge.ShellResult.TransportFailure::class.java)
    }

    // ── disconnect ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `disconnect tears the gate down even with a shell in flight`() = runTest {
        val gate = FakeGate()
        val started = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<Unit>()
        gate.connected = true
        gate.execBehavior = {
            started.complete(Unit)
            aborted.await() // a real gate's read aborts when the socket closes underneath it
            throw IOException("Stream closed.")
        }
        val b = bridge(gate)
        val inFlight = launch { b.shell("top") }
        started.await()

        b.disconnect() // must not queue behind the in-flight op
        assertThat(gate.closed).isTrue()

        aborted.complete(Unit)
        inFlight.join()
    }

    // ── selfGrant ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `selfGrant runs the exact pm grant argv for our own package`() = runTest {
        val gate = FakeGate().apply { connected = true }
        var seen: String? = null
        gate.respond { inner ->
            seen = inner
            0 to ""
        }
        val result = bridge(gate).selfGrant("android.permission.WRITE_SECURE_SETTINGS")
        assertThat(result).isEqualTo(AdbBridge.GrantResult.GRANTED)
        assertThat(seen)
            .isEqualTo("pm grant cc.grepon.portage.recv android.permission.WRITE_SECURE_SETTINGS")
    }

    @Test
    fun `selfGrant maps rejection and a dead bridge`() = runTest {
        val rejecting = FakeGate().apply { connected = true }
        rejecting.respond { 255 to "Operation not allowed" }
        assertThat(bridge(rejecting).selfGrant("android.permission.WRITE_SECURE_SETTINGS"))
            .isEqualTo(AdbBridge.GrantResult.REJECTED)

        val dead = FakeGate()
        assertThat(bridge(dead).selfGrant("android.permission.WRITE_SECURE_SETTINGS"))
            .isEqualTo(AdbBridge.GrantResult.BRIDGE_UNAVAILABLE)
    }

    // ── derived typed ops ────────────────────────────────────────────────────────────────────

    @Test
    fun `derived ops build validated argv and quote values`() = runTest {
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respond { inner ->
            seen += inner
            0 to ""
        }
        val b = bridge(gate)
        assertThat(b.writeSecureSetting("ui_night_mode", "2")).isEqualTo(AdbBridge.OpResult.Ok)
        assertThat(b.writeGlobalSetting("k", "two words")).isEqualTo(AdbBridge.OpResult.Ok)
        assertThat(b.setSmsRoleHolder("com.example.sms")).isEqualTo(AdbBridge.OpResult.Ok)
        assertThat(b.setNavigationMode(AdbBridge.NavigationMode.GESTURAL))
            .isEqualTo(AdbBridge.OpResult.Ok)
        assertThat(seen).containsExactly(
            "settings put secure ui_night_mode 2",
            "settings put global k 'two words'",
            "cmd role add-role-holder android.app.role.SMS com.example.sms",
            "cmd overlay enable-exclusive --category android.theme.customization.navigation " +
                "com.android.internal.systemui.navbar.gestural",
        ).inOrder()
    }

    @Test
    fun `derived ops reject control characters before touching the gate`() = runTest {
        val gate = FakeGate().apply { connected = true }
        gate.respond { 0 to "" }
        val result = bridge(gate).writeSecureSetting("ui_night_mode", "2\nreboot")
        assertThat(result).isInstanceOf(AdbBridge.OpResult.Failed::class.java)
        assertThat(gate.execCalls).isEmpty()
    }

    // ── installApk ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `installApk drives create write commit and maps Success`() = runTest {
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respond { inner ->
            seen += inner
            when {
                inner.startsWith("pm install-create") -> 0 to "Success: created install session [42]"
                inner.startsWith("pm install-write") -> 0 to "Success: streamed 1024 bytes"
                inner.startsWith("pm install-commit") -> 0 to "Success"
                else -> 1 to "unexpected: $inner"
            }
        }
        val result = bridge(gate).installApk("/data/local/tmp/base.apk")
        assertThat(result).isEqualTo(AdbBridge.InstallResult.Installed)
        assertThat(seen).containsExactly(
            "pm install-create --user 0",
            "pm install-write 42 base.apk /data/local/tmp/base.apk",
            "pm install-commit 42",
        ).inOrder()
    }

    @Test
    fun `installApk abandons the session when the write step fails`() = runTest {
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respond { inner ->
            seen += inner
            when {
                inner.startsWith("pm install-create") -> 0 to "[7]"
                inner.startsWith("pm install-write") -> 1 to "failure"
                else -> 0 to ""
            }
        }
        val result = bridge(gate).installApk("/data/local/tmp/base.apk")
        assertThat(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
        assertThat(seen).contains("pm install-abandon 7")
    }

    @Test
    fun `installApk abandons the session when the commit step fails`() = runTest {
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respond { inner ->
            seen += inner
            when {
                inner.startsWith("pm install-create") -> 0 to "[8]"
                inner.startsWith("pm install-write") -> 0 to "Success"
                inner.startsWith("pm install-commit") -> 1 to "Failure [INSTALL_FAILED_INVALID_APK]"
                else -> 0 to ""
            }
        }
        val result = bridge(gate).installApk("/data/local/tmp/base.apk")
        assertThat(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
        assertThat(seen).contains("pm install-abandon 8")
    }

    @Test
    fun `installApk on a dead bridge is BridgeUnavailable`() = runTest {
        assertThat(bridge(FakeGate()).installApk("/data/local/tmp/base.apk"))
            .isEqualTo(AdbBridge.InstallResult.BridgeUnavailable)
    }

    // ── probeCapabilities ────────────────────────────────────────────────────────────────────

    private fun FakeGate.respondLikeHealthyDevice() = respond { inner ->
        when {
            inner == "id" -> 0 to "uid=2000(shell) gid=2000(shell) context=u:r:shell:s0"
            inner.startsWith("pm grant") -> 0 to ""
            inner == "pm list permissions" -> 0 to "permission:android.permission.INTERNET"
            inner == "pm install-create --user 0" -> 0 to "[13]"
            inner.startsWith("pm install-abandon") -> 0 to ""
            inner == "cmd overlay list android" ->
                0 to "[x] com.android.internal.systemui.navbar.gestural"
            inner == "dumpsys role" -> 0 to "ROLE MANAGER STATE"
            else -> 1 to "unexpected: $inner"
        }
    }

    @Test
    fun `probe finds the full capability set on a healthy device`() = runTest {
        val gate = FakeGate().apply { connected = true }
        gate.respondLikeHealthyDevice()
        assertThat(bridge(gate).probeCapabilities())
            .containsExactlyElementsIn(AdbBridge.PrivilegedCapability.entries)
    }

    @Test
    fun `probe runs checks independently - one failure does not abort the others`() = runTest {
        val gate = FakeGate().apply { connected = true }
        gate.execBehavior = { wrapped ->
            val inner = wrapPattern.matchEntire(wrapped)?.groupValues?.get(1) ?: ""
            if (inner.startsWith("pm grant")) throw IOException("link hiccup")
            when {
                inner == "id" -> "uid=2000(shell)\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner == "pm list permissions" -> "ok\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner == "pm install-create --user 0" -> "[9]\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner.startsWith("pm install-abandon") -> "${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner == "cmd overlay list android" ->
                    "com.android.internal.systemui.navbar.gestural\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner == "dumpsys role" -> "STATE\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                else -> "${LocalAdbBridge.EXIT_SENTINEL}1\n"
            }
        }
        val caps = bridge(gate).probeCapabilities()
        assertThat(caps).doesNotContain(AdbBridge.PrivilegedCapability.SETTINGS_SECURE)
        assertThat(caps).containsAtLeast(
            AdbBridge.PrivilegedCapability.SHELL,
            AdbBridge.PrivilegedCapability.PERMISSION_PARITY,
            AdbBridge.PrivilegedCapability.SILENT_INSTALL,
            AdbBridge.PrivilegedCapability.NAV_MODE,
            AdbBridge.PrivilegedCapability.SMS_ROLE,
        )
    }

    @Test
    fun `probe on a dead bridge is the empty set`() = runTest {
        assertThat(bridge(FakeGate()).probeCapabilities()).isEmpty()
    }

    @Test
    fun `probe abandons the install session it opened`() = runTest {
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.execBehavior = { wrapped ->
            val inner = wrapPattern.matchEntire(wrapped)?.groupValues?.get(1) ?: ""
            seen += inner
            when {
                inner == "pm install-create --user 0" -> "[55]\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                else -> "${LocalAdbBridge.EXIT_SENTINEL}1\n"
            }
        }
        bridge(gate).probeCapabilities()
        assertThat(seen).contains("pm install-abandon 55")
    }

    @Test
    fun `probe recovers the full set when the link drops mid-sweep then reconnects`() = runTest {
        // Healthy at the start; the link dies partway through the FIRST sweep (at the third probe),
        // then the reconnect (default connectBehavior) restores a healthy device for a second sweep.
        val gate = FakeGate().apply { connected = true }
        var dropped = false
        gate.execBehavior = { wrapped ->
            val inner = wrapPattern.matchEntire(wrapped)?.groupValues?.get(1) ?: ""
            if (!dropped && inner == "pm list permissions") {
                // Kill the link mid-sweep, exactly as a dropped socket would.
                dropped = true
                gate.connected = false
                throw IOException("connection reset by peer")
            }
            val (exit, out) = when {
                inner == "id" -> 0 to "uid=2000(shell)"
                inner.startsWith("pm grant") -> 0 to ""
                inner == "pm list permissions" -> 0 to "permission:android.permission.INTERNET"
                inner == "pm install-create --user 0" -> 0 to "[7]"
                inner.startsWith("pm install-abandon") -> 0 to ""
                inner == "cmd overlay list android" ->
                    0 to "[x] com.android.internal.systemui.navbar.gestural"
                inner == "dumpsys role" -> 0 to "ROLE MANAGER STATE"
                else -> 1 to "unexpected: $inner"
            }
            val body = if (out.isEmpty()) "" else out + "\n"
            body + LocalAdbBridge.EXIT_SENTINEL + exit + "\n"
        }
        // The under-report bug: a mid-sweep drop after the grant landed must NOT leave the later
        // capabilities falsely absent — the retry re-probes the recovered link and finds them all.
        val caps = bridge(gate).probeCapabilities()
        assertThat(caps).containsExactlyElementsIn(AdbBridge.PrivilegedCapability.entries)
        // …and prove the recovery actually re-swept: `id` (the first probe) ran once per sweep.
        val idRuns = gate.execCalls.count { wrapPattern.matchEntire(it)?.groupValues?.get(1) == "id" }
        assertThat(idRuns).isEqualTo(2)
    }

    @Test
    fun `probe returns the partial set when the dropped link will not reconnect`() = runTest {
        val gate = FakeGate().apply { connected = true }
        var dropped = false
        gate.execBehavior = { wrapped ->
            val inner = wrapPattern.matchEntire(wrapped)?.groupValues?.get(1) ?: ""
            if (!dropped && inner == "pm list permissions") {
                dropped = true
                gate.connected = false
                throw IOException("connection reset by peer")
            }
            when {
                inner == "id" -> "uid=2000(shell)\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner.startsWith("pm grant") -> "${LocalAdbBridge.EXIT_SENTINEL}0\n"
                else -> "${LocalAdbBridge.EXIT_SENTINEL}1\n"
            }
        }
        gate.connectBehavior = { false } // the link never comes back
        // Only the probes that actually ran before the drop are reported — never a hang, and never
        // a false "absent" for the ones that could not run.
        assertThat(bridge(gate).probeCapabilities()).containsExactly(
            AdbBridge.PrivilegedCapability.SHELL,
            AdbBridge.PrivilegedCapability.SETTINGS_SECURE,
        )
    }

    @Test
    fun `probe is bounded - it stops after MAX_PROBE_ATTEMPTS even when every sweep drops`() = runTest {
        // Pathological link: reconnect always succeeds, but every sweep drops at the 2nd probe.
        // The retry must NOT spin — it is hard-capped at MAX_PROBE_ATTEMPTS sweeps.
        val gate = FakeGate().apply { connected = true }
        gate.execBehavior = { wrapped ->
            val inner = wrapPattern.matchEntire(wrapped)?.groupValues?.get(1) ?: ""
            if (inner == "id") {
                "uid=2000(shell)\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
            } else {
                // Every non-floor probe kills the link; the reconnect (below) re-arms it.
                gate.connected = false
                throw IOException("connection reset by peer")
            }
        }
        gate.connectBehavior = { true } // reconnect always succeeds — only the cap stops the loop
        val caps = bridge(gate).probeCapabilities()
        // SHELL is the one conclusively-verified capability each sweep; the loop terminates.
        assertThat(caps).containsExactly(AdbBridge.PrivilegedCapability.SHELL)
        // Bounded: exactly MAX_PROBE_ATTEMPTS sweeps ran (one `id` per sweep), no runaway.
        val idRuns = gate.execCalls.count { wrapPattern.matchEntire(it)?.groupValues?.get(1) == "id" }
        assertThat(idRuns).isEqualTo(LocalAdbBridge.MAX_PROBE_ATTEMPTS)
    }

    @Test
    fun `probe retries a live-link transient (link stays up) and recovers the full set`() = runTest {
        // The hardware failure mode (2026-06-13, rango): a large-output probe transiently fails on
        // the COLD connection while the link stays CONNECTED — the first sweep mislabeled the device
        // "Basic". The retry must fire on an inconclusive sweep even though isConnected() is true,
        // reconnect fresh, and recover. (This case is NOT retried by the drop-only predecessor.)
        val gate = FakeGate().apply { connected = true }
        var parityFailedOnce = false
        gate.execBehavior = { wrapped ->
            val inner = wrapPattern.matchEntire(wrapped)?.groupValues?.get(1) ?: ""
            if (inner == "pm list permissions" && !parityFailedOnce) {
                // Transport failure, but the link is NOT torn down (connected stays true) — exactly
                // a cold-connection stream hiccup, not a dropped socket.
                parityFailedOnce = true
                throw IOException("shell stream read hiccup")
            }
            val (exit, out) = when {
                inner == "id" -> 0 to "uid=2000(shell)"
                inner.startsWith("pm grant") -> 0 to ""
                inner == "pm list permissions" -> 0 to "permission:android.permission.INTERNET"
                inner == "pm install-create --user 0" -> 0 to "[7]"
                inner.startsWith("pm install-abandon") -> 0 to ""
                inner == "cmd overlay list android" ->
                    0 to "[x] com.android.internal.systemui.navbar.gestural"
                inner == "dumpsys role" -> 0 to "ROLE MANAGER STATE"
                else -> 1 to "unexpected: $inner"
            }
            val body = if (out.isEmpty()) "" else out + "\n"
            body + LocalAdbBridge.EXIT_SENTINEL + exit + "\n"
        }
        // Link never drops; the bridge's own disconnect()+reconnect on the inconclusive sweep is
        // what gives the retry a fresh attempt (default connectBehavior restores it).
        assertThat(bridge(gate).probeCapabilities())
            .containsExactlyElementsIn(AdbBridge.PrivilegedCapability.entries)
    }
}
