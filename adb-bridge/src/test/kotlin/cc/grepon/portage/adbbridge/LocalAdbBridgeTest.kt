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
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
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
        // Wireless Debugging toggle — default ON so the existing connect tests exercise gate.connect().
        var wirelessDebuggingEnabled = true
        var connectAttempts = 0
        var pairBehavior: suspend (Int, String) -> Unit = { _, _ -> }
        var connectBehavior: suspend (Long) -> Boolean = { true }
        var execBehavior: suspend (String) -> String = { "" }
        // Streamed install-write (the `exec:` path). Default mimics a good write: pm prints "Success".
        // Scriptable so a test can return "Failure [INSTALL_FAILED_…]" or throw a transport error.
        var execWithStdinBehavior: suspend (String) -> String = { "Success" }
        val pairCalls = mutableListOf<Pair<Int, String>>()
        val execCalls = mutableListOf<String>()
        val execWithStdinCalls = mutableListOf<String>()
        /** Bytes the fake fully read off each `execWithStdin` stdin stream, in call order. */
        val streamedBytes = mutableListOf<Long>()

        override suspend fun pair(port: Int, pairingCode: String) {
            pairCalls += port to pairingCode
            pairBehavior(port, pairingCode)
        }

        override fun isWirelessDebuggingEnabled(): Boolean = wirelessDebuggingEnabled

        override suspend fun connect(timeoutMs: Long): Boolean {
            connectAttempts++
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

        override suspend fun execWithStdin(
            command: String,
            input: java.io.InputStream,
            size: Long,
        ): String {
            execWithStdinCalls += command
            // Fully drain the stdin stream and count what we read — the bridge promises exactly
            // [size] bytes, so a test asserts the count equals the StagedApk size.
            var read = 0L
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                read += n
            }
            streamedBytes += read
            return execWithStdinBehavior(command)
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

    @Test
    fun `connect with Wireless Debugging off is NoEndpoint and NEVER calls gate connect`() = runTest {
        // The hang guard: with WD off, libadb's mDNS discovery ignores thread interruption and
        // gate.connect() would hang INDEFINITELY. The bridge must short-circuit to NoEndpoint up
        // front and never reach the gate's connect (which would deadlock on a real device).
        val gate = FakeGate().apply {
            connected = false
            wirelessDebuggingEnabled = false
            connectBehavior = { error("gate.connect() must not be called when Wireless Debugging is off") }
        }
        assertThat(bridge(gate).connect()).isEqualTo(AdbBridge.ConnectionResult.NoEndpoint)
        assertThat(gate.connectAttempts).isEqualTo(0) // proves the gate was never driven into the hang
    }

    @Test
    fun `connect with WD off but an already-live link stays Connected (no reconnect needed)`() = runTest {
        // The WD gate only guards the RECONNECT path. An already-connected gate is reported Connected
        // without consulting the toggle or calling connect() — it cannot hit the discovery hang.
        val gate = FakeGate().apply {
            connected = true
            wirelessDebuggingEnabled = false
            connectBehavior = { error("must not reconnect an already-live link") }
        }
        assertThat(bridge(gate).connect()).isEqualTo(AdbBridge.ConnectionResult.Connected)
        assertThat(gate.connectAttempts).isEqualTo(0)
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

    /** A staged file of [size] zero-bytes, opened fresh each call (the bridge re-opens per install). */
    private fun stagedFile(name: String, size: Long): AdbBridge.StagedApk =
        AdbBridge.StagedApk(name, size) { ByteArrayInputStream(ByteArray(size.toInt())) }

    /** Each name → a distinct fixed size so a test can assert the streamed byte count per file. */
    private fun staged(vararg names: String): List<AdbBridge.StagedApk> =
        names.mapIndexed { i, name -> stagedFile(name, (1024L * (i + 1))) }

    private val baseApk = listOf(stagedFile("base", 1024L))

    /** Script create/commit/abandon (the `exec:` shell path) into [seen]; install-write is streamed. */
    private fun FakeGate.respondToSessionOps(seen: MutableList<String>, sessionId: String) = respond { inner ->
        seen += inner
        when {
            inner.startsWith("pm install-create") -> 0 to "[$sessionId]"
            inner.startsWith("pm install-commit") -> 0 to "Success"
            inner.startsWith("pm install-abandon") -> 0 to ""
            else -> 1 to "unexpected: $inner"
        }
    }

    @Test
    fun `installApk drives create write commit and maps Success`() = runTest {
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respondToSessionOps(seen, "42") // create + commit on the shell path
        // install-write streams over the exec: path; the default behavior prints "Success".
        val result = bridge(gate).installApk(baseApk)
        assertThat(result).isEqualTo(AdbBridge.InstallResult.Installed)
        // create then commit on the shell path (the streamed write is asserted separately).
        assertThat(seen).containsExactly(
            "pm install-create --user 0",
            "pm install-commit 42",
        ).inOrder()
        // The streamed write carried -S <size>, the session id, the name, and the `-` stdin marker,
        // and EXACTLY [size] bytes were piped (baseApk is 1024 bytes).
        assertThat(gate.execWithStdinCalls).containsExactly("pm install-write -S 1024 42 base -")
        assertThat(gate.streamedBytes).containsExactly(1024L)
    }

    @Test
    fun `installApk writes every staged file once in order into a single session`() = runTest {
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respondToSessionOps(seen, "42")
        val files = staged("base", "config.arm64_v8a", "config.en") // sizes 1024, 2048, 3072
        val result = bridge(gate).installApk(files)
        assertThat(result).isEqualTo(AdbBridge.InstallResult.Installed)
        // create once on the shell path, then a single commit (writes are streamed, not shell).
        assertThat(seen).containsExactly(
            "pm install-create --user 0",
            "pm install-commit 42",
        ).inOrder()
        // one streamed write per file, in order, each with -S <size> .. -, each fully piped.
        assertThat(gate.execWithStdinCalls).containsExactly(
            "pm install-write -S 1024 42 base -",
            "pm install-write -S 2048 42 config.arm64_v8a -",
            "pm install-write -S 3072 42 config.en -",
        ).inOrder()
        assertThat(gate.streamedBytes).containsExactly(1024L, 2048L, 3072L).inOrder()
    }

    @Test
    fun `installApk abandons the session when an early split write fails`() = runTest {
        // A failing write on the FIRST split must abandon immediately and never write the rest.
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respondToSessionOps(seen, "7")
        // The base streams "Success"; the first split (config.arm64_v8a) streams a Failure.
        gate.execWithStdinBehavior = { command ->
            if (command.contains(" base ")) "Success" else "Failure [INSTALL_FAILED_INVALID_APK]"
        }
        val files = staged("base", "config.arm64_v8a", "config.en")
        val result = bridge(gate).installApk(files)
        assertThat(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
        assertThat(seen).contains("pm install-abandon 7")
        // the base wrote, the failing split was attempted, the LATER split never was, no commit.
        assertThat(gate.execWithStdinCalls).contains("pm install-write -S 2048 7 config.arm64_v8a -")
        assertThat(gate.execWithStdinCalls).doesNotContain("pm install-write -S 3072 7 config.en -")
        assertThat(seen.none { it.startsWith("pm install-commit") }).isTrue()
    }

    @Test
    fun `installApk abandons the session when the write step fails`() = runTest {
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respondToSessionOps(seen, "7")
        gate.execWithStdinBehavior = { "Failure [INSTALL_FAILED_INVALID_APK]" }
        val result = bridge(gate).installApk(baseApk)
        assertThat(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
        assertThat(seen).contains("pm install-abandon 7")
        // a failed write verdict — no commit success is ever reported.
        assertThat(seen.none { it.startsWith("pm install-commit") }).isTrue()
    }

    @Test
    fun `installApk abandons the session when the commit step fails`() = runTest {
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respond { inner ->
            seen += inner
            when {
                inner.startsWith("pm install-create") -> 0 to "[8]"
                inner.startsWith("pm install-commit") -> 1 to "Failure [INSTALL_FAILED_INVALID_APK]"
                else -> 0 to ""
            }
        }
        // write streams "Success" (default), but the commit exit code is the verdict and it fails.
        val result = bridge(gate).installApk(baseApk)
        assertThat(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
        assertThat(seen).contains("pm install-abandon 8")
    }

    @Test
    fun `installApk commit verdict is the exit code not a Success string`() = runTest {
        // Exit 0 with NO "Success" line still installs; a non-zero exit fails even if stdout
        // happened to contain the word "Success". The commit exit code alone is authoritative.
        val ok = FakeGate().apply { connected = true }
        ok.respond { inner ->
            when {
                inner.startsWith("pm install-create") -> 0 to "[5]"
                inner.startsWith("pm install-commit") -> 0 to "" // exit 0, no "Success" text
                else -> 1 to "unexpected"
            }
        }
        assertThat(bridge(ok).installApk(baseApk)).isEqualTo(AdbBridge.InstallResult.Installed)

        val bad = FakeGate().apply { connected = true }
        bad.respond { inner ->
            when {
                inner.startsWith("pm install-create") -> 0 to "[6]"
                inner.startsWith("pm install-commit") -> 1 to "Success" // word present, exit non-zero
                else -> 0 to ""
            }
        }
        assertThat(bridge(bad).installApk(baseApk))
            .isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
    }

    @Test
    fun `installApk base-only no-splits production happy path`() = runTest {
        // Explicit base-only case: single StagedApk("base",...) → exactly one streamed install-write
        // → install-commit → Installed. The most common real-world install (non-split APKs).
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respondToSessionOps(seen, "20")
        val result = bridge(gate).installApk(listOf(stagedFile("base", 1024L)))
        assertThat(result).isEqualTo(AdbBridge.InstallResult.Installed)
        assertThat(seen).containsExactly(
            "pm install-create --user 0",
            "pm install-commit 20",
        ).inOrder()
        assertThat(gate.execWithStdinCalls).containsExactly("pm install-write -S 1024 20 base -")
    }

    @Test
    fun `installApk write transport failure is BridgeUnavailable not Failed`() = runTest {
        // A streamed-write transport failure (the exec: stream throws) must read as BridgeUnavailable,
        // consistent with install-create and install-commit — the apply provider uses this distinction
        // to differentiate a dead bridge from a real install rejection.
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respondToSessionOps(seen, "30")
        gate.execWithStdinBehavior = { throw java.io.IOException("Stream closed.") }
        val result = bridge(gate).installApk(baseApk)
        assertThat(result).isEqualTo(AdbBridge.InstallResult.BridgeUnavailable)
        // abandon ran best-effort (may have also failed, but was attempted)
        assertThat(seen.any { it.startsWith("pm install-abandon") }).isTrue()
        // no commit was attempted
        assertThat(seen.none { it.startsWith("pm install-commit") }).isTrue()
    }

    @Test
    fun `installApk write Failure output is Failed not BridgeUnavailable`() = runTest {
        // A "Failure [..]" line from a streamed write (the command ran and rejected) stays Failed,
        // not BridgeUnavailable — the bridge is alive, the install was refused.
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respondToSessionOps(seen, "31")
        gate.execWithStdinBehavior = { "Failure [INSTALL_FAILED_INVALID_APK]" }
        val result = bridge(gate).installApk(baseApk)
        assertThat(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
    }

    /**
     * The bridge-side name corpus (ADR-006 AC-6b). This exercises the bridge's own
     * [LocalAdbBridge.validatedSplitNameOrNull] against hostile names. It is NOT a cross-module pin:
     * :adb-bridge is dependency-isolated from :providers, so there is no shared corpus. The real
     * parity guardrail is the `SPLIT_NAME pattern is pinned` test in each module, which hardcodes the
     * canonical regex string so a divergent edit fails that module's CI.
     */
    private val NAME_CORPUS_ACCEPT = listOf("base", "config.arm64_v8a")
    private val NAME_CORPUS_REJECT = listOf(
        "../../etc/x",   // path traversal
        "a;rm -rf",      // shell metachar + whitespace
        "a/b",           // forward slash
        "a\\b",          // backslash
        "..",            // parent dir (defence-in-depth)
        ".",             // current dir (defence-in-depth)
        ".hidden",       // leading dot
        "-rf",           // leading dash
        "",              // empty
        "a b",           // space
        // "a\tb" and "a\nb" are ShellArgs-masked: ShellArgs rejects control chars before the name
        // guard sees them, so these are defence-in-depth, NOT name-guard coverage — a future reader
        // should not credit them to validatedSplitNameOrNull.
        "a\tb",          // tab
        "a\nb",          // newline
        "a;b",           // semicolon
        "a|b",           // pipe
        "a\$(x)",        // command substitution $()
        "a`x`",          // command substitution backtick
    )

    @Test
    fun `installApk name-corpus REJECT names are all abandoned without writing`() = runTest {
        // AC-6b: every name in NAME_CORPUS_REJECT must be refused — session abandoned, the bad name
        // never reaches install-write, no commit. (Cross-module regex parity is pinned separately by
        // the `SPLIT_NAME pattern is pinned` test in each module, not by sharing this corpus.)
        for (bad in NAME_CORPUS_REJECT) {
            val gate = FakeGate().apply { connected = true }
            val seen = mutableListOf<String>()
            gate.respondToSessionOps(seen, "3")
            val files = listOf(stagedFile("base", 1024L), stagedFile(bad, 2048L))
            val result = bridge(gate).installApk(files)
            assertWithMessage("expected Failed for rejected name [$bad]")
                .that(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
            assertWithMessage("expected abandon for rejected name [$bad]")
                .that(seen).contains("pm install-abandon 3")
            assertWithMessage("expected the bad name never streamed [$bad]")
                .that(gate.execWithStdinCalls.none { it.contains(" $bad ") })
                .isTrue()
            assertWithMessage("expected no commit for rejected name [$bad]")
                .that(seen.none { it.startsWith("pm install-commit") })
                .isTrue()
        }
    }

    @Test
    fun `installApk name-corpus ACCEPT names all succeed`() = runTest {
        // AC-6b: every name in NAME_CORPUS_ACCEPT must be accepted. "base" is the single-file case;
        // "config.arm64_v8a" is exercised as the second file in a split session.
        val gate = FakeGate().apply { connected = true }
        val seen = mutableListOf<String>()
        gate.respondToSessionOps(seen, "11")
        val files = NAME_CORPUS_ACCEPT.mapIndexed { i, name -> stagedFile(name, 1024L * (i + 1)) }
        assertThat(bridge(gate).installApk(files)).isEqualTo(AdbBridge.InstallResult.Installed)
    }

    @Test
    fun `SPLIT_NAME pattern is pinned`() {
        // Cross-module parity pin: :adb-bridge and :providers each REPLICATE the split-name regex
        // (no shared dep by design). Both pins hardcode the SAME canonical string, so editing either
        // copy breaks that module's pin test and forces the other to be updated in lockstep.
        assertThat(LocalAdbBridge.SPLIT_NAME.pattern).isEqualTo("[A-Za-z0-9][A-Za-z0-9._-]*")
    }

    @Test
    fun `adb_wifi_enabled key is pinned`() {
        // Cross-module parity pin: :adb-bridge (LibAdbDeviceGate) and :wizard (AndroidWizardEnvironment)
        // each REPLICATE this AOSP Settings.Global key (dependency-isolated by design). Both pins
        // hardcode the SAME canonical string, so editing either copy breaks that module's pin and
        // forces the other to be updated in lockstep. The key gates bridge connect (WD-off ⇒ Tier-0
        // fallback), so a silent drift would break the hang-prevention guard — hence the CI pin.
        assertThat(LibAdbDeviceGate.ADB_WIFI_ENABLED).isEqualTo("adb_wifi_enabled")
    }

    @Test
    fun `installApk abandon itself failing does not suppress the install result or throw`() = runTest {
        // Pins the shellQuietly best-effort contract: if the abandon command itself hits a transport
        // failure, installApk must still return the correct typed result (Failed) with no exception
        // and no hang.
        val gate = FakeGate().apply { connected = true }
        var abandonAttempted = false
        gate.execBehavior = { wrapped ->
            val inner = wrapPattern.matchEntire(wrapped)?.groupValues?.get(1)
                ?: error("not sentinel-wrapped: $wrapped")
            when {
                inner.startsWith("pm install-create") ->
                    "[40]\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner.startsWith("pm install-abandon") -> {
                    abandonAttempted = true
                    // abandon itself drops the connection — no sentinel returned
                    "socket closed"
                }
                else -> "${LocalAdbBridge.EXIT_SENTINEL}0\n"
            }
        }
        // The streamed write rejects (Failure line) → triggers the abandon path.
        gate.execWithStdinBehavior = { "Failure [INSTALL_FAILED_INVALID_APK]" }
        val result = bridge(gate).installApk(baseApk)
        // write failed → Failed (not BridgeUnavailable), no throw
        assertThat(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
        assertThat(abandonAttempted).isTrue()
    }

    @Test
    fun `installApk on a dead bridge is BridgeUnavailable`() = runTest {
        assertThat(bridge(FakeGate()).installApk(baseApk))
            .isEqualTo(AdbBridge.InstallResult.BridgeUnavailable)
    }

    @Test
    fun `installApk rejects a zero-base set before opening a session`() = runTest {
        // Structural invariant at the privilege boundary: a set with no "base" is refused cheaply,
        // never opening a session — crucially, NO pm install-create is issued.
        val gate = FakeGate().apply { connected = true }
        gate.respond { 0 to "" }
        val files = staged("config.arm64_v8a", "config.en")
        val result = bridge(gate).installApk(files)
        assertThat(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
        // The guard fires before any shell op — the fake gate saw no create.
        assertThat(gate.execCalls).isEmpty()
    }

    @Test
    fun `installApk rejects a multi-base set before opening a session`() = runTest {
        // Two base files is also a malformed set — refused before any session opens, no create.
        val gate = FakeGate().apply { connected = true }
        gate.respond { 0 to "" }
        val files = listOf(stagedFile("base", 1024L), stagedFile("base", 2048L))
        val result = bridge(gate).installApk(files)
        assertThat(result).isInstanceOf(AdbBridge.InstallResult.Failed::class.java)
        assertThat(gate.execCalls).isEmpty()
    }

    // ── probeCapabilities ────────────────────────────────────────────────────────────────────

    private fun FakeGate.respondLikeHealthyDevice() = respond { inner ->
        when {
            inner == "id" -> 0 to "uid=2000(shell) gid=2000(shell) context=u:r:shell:s0"
            inner.startsWith("pm grant") -> 0 to ""
            inner == "pm list permissions >/dev/null 2>&1" -> 0 to "permission:android.permission.INTERNET"
            inner == "pm install-create --user 0" -> 0 to "[13]"
            inner.startsWith("pm install-abandon") -> 0 to ""
            inner == "cmd overlay list android" ->
                0 to "[x] com.android.internal.systemui.navbar.gestural"
            inner == "dumpsys role >/dev/null 2>&1" -> 0 to "ROLE MANAGER STATE"
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
                inner == "pm list permissions >/dev/null 2>&1" -> "ok\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner == "pm install-create --user 0" -> "[9]\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner.startsWith("pm install-abandon") -> "${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner == "cmd overlay list android" ->
                    "com.android.internal.systemui.navbar.gestural\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
                inner == "dumpsys role >/dev/null 2>&1" -> "STATE\n${LocalAdbBridge.EXIT_SENTINEL}0\n"
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
    fun `the large-output probes discard output on-device so they cannot stall the cold link`() = runTest {
        val gate = FakeGate().apply { connected = true }
        gate.respondLikeHealthyDevice()
        bridge(gate).probeCapabilities()

        // The two big-output probes (pm list permissions ~80 KB, dumpsys role ~10 KB) MUST redirect
        // to /dev/null: large wire reads stall the cold libadb stream and mis-report the capability
        // absent ("Basic transfer ready"). Only the exit code is read, so suppressing output is safe.
        val inners = gate.execCalls.mapNotNull { wrapPattern.matchEntire(it)?.groupValues?.get(1) }
        assertThat(inners).contains("pm list permissions >/dev/null 2>&1")
        assertThat(inners).contains("dumpsys role >/dev/null 2>&1")
        assertThat(inners).containsNoneOf("pm list permissions", "dumpsys role")
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
            if (!dropped && inner == "pm list permissions >/dev/null 2>&1") {
                // Kill the link mid-sweep, exactly as a dropped socket would.
                dropped = true
                gate.connected = false
                throw IOException("connection reset by peer")
            }
            val (exit, out) = when {
                inner == "id" -> 0 to "uid=2000(shell)"
                inner.startsWith("pm grant") -> 0 to ""
                inner == "pm list permissions >/dev/null 2>&1" -> 0 to "permission:android.permission.INTERNET"
                inner == "pm install-create --user 0" -> 0 to "[7]"
                inner.startsWith("pm install-abandon") -> 0 to ""
                inner == "cmd overlay list android" ->
                    0 to "[x] com.android.internal.systemui.navbar.gestural"
                inner == "dumpsys role >/dev/null 2>&1" -> 0 to "ROLE MANAGER STATE"
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
            if (!dropped && inner == "pm list permissions >/dev/null 2>&1") {
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
            if (inner == "pm list permissions >/dev/null 2>&1" && !parityFailedOnce) {
                // Transport failure, but the link is NOT torn down (connected stays true) — exactly
                // a cold-connection stream hiccup, not a dropped socket.
                parityFailedOnce = true
                throw IOException("shell stream read hiccup")
            }
            val (exit, out) = when {
                inner == "id" -> 0 to "uid=2000(shell)"
                inner.startsWith("pm grant") -> 0 to ""
                inner == "pm list permissions >/dev/null 2>&1" -> 0 to "permission:android.permission.INTERNET"
                inner == "pm install-create --user 0" -> 0 to "[7]"
                inner.startsWith("pm install-abandon") -> 0 to ""
                inner == "cmd overlay list android" ->
                    0 to "[x] com.android.internal.systemui.navbar.gestural"
                inner == "dumpsys role >/dev/null 2>&1" -> 0 to "ROLE MANAGER STATE"
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
