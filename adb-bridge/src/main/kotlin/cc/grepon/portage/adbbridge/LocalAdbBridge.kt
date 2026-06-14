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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import javax.net.ssl.SSLException

/**
 * The production [AdbBridge]: typed results, per-op timeouts, serialized ops, and the ADR-001
 * V4–V7 capability probes, over a fakeable [AdbDeviceGate]. Everything here is JVM-unit-tested;
 * the gate below it is the thin device-verified layer.
 *
 * Failure mapping is deliberately conservative: anything ambiguous degrades to a typed
 * unavailability, never an exception — Tier 0 must keep working with the bridge in any state.
 */
class LocalAdbBridge internal constructor(
    private val selfPackage: String,
    private val gate: AdbDeviceGate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pairTimeoutMs: Long = PAIR_TIMEOUT_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val shellTimeoutMs: Long = SHELL_TIMEOUT_MS,
) : AdbBridge {

    /** Serializes pair/connect/shell — one ADB conversation at a time (single stream, single adbd). */
    private val opLock = Mutex()

    override suspend fun pair(pairingPort: Int, pairingCode: String): AdbBridge.PairingResult =
        withContext(io) {
            opLock.withLock {
                try {
                    withTimeout(pairTimeoutMs) { gate.pair(pairingPort, pairingCode) }
                    AdbBridge.PairingResult.Paired
                } catch (t: TimeoutCancellationException) {
                    AdbBridge.PairingResult.Timeout
                } catch (c: CancellationException) {
                    throw c
                } catch (s: SSLException) {
                    // The SPAKE2/TLS exchange aborting is how a wrong or expired code presents.
                    AdbBridge.PairingResult.WrongCode
                } catch (c: ConnectException) {
                    AdbBridge.PairingResult.Unavailable(PAIRING_ENDPOINT_DOWN)
                } catch (n: NoRouteToHostException) {
                    AdbBridge.PairingResult.Unavailable(PAIRING_ENDPOINT_DOWN)
                } catch (t: Throwable) {
                    // Fixed copy, never raw library internals (review 2026-06-12, LOW).
                    AdbBridge.PairingResult.Unavailable(PAIRING_FAILED)
                }
            }
        }

    override suspend fun connect(): AdbBridge.ConnectionResult = withContext(io) {
        opLock.withLock {
            if (gate.isConnected()) return@withLock AdbBridge.ConnectionResult.Connected
            try {
                // The outer guard fires TIMEOUT_SLACK_MS after the gate's own timeout so the
                // gate gets to map its own, more specific failure first.
                withTimeout(connectTimeoutMs + TIMEOUT_SLACK_MS) {
                    gate.connect(connectTimeoutMs)
                }
                if (gate.isConnected()) {
                    AdbBridge.ConnectionResult.Connected
                } else {
                    AdbBridge.ConnectionResult.Rejected(KEY_REFUSED)
                }
            } catch (t: TimeoutCancellationException) {
                AdbBridge.ConnectionResult.Timeout
            } catch (c: CancellationException) {
                throw c
            } catch (i: InterruptedException) {
                // libadb-android signals "no endpoint discovered in time" as InterruptedException.
                AdbBridge.ConnectionResult.NoEndpoint
            } catch (e: IOException) {
                // "Could not find any valid host address or port" — Wireless Debugging is off.
                if (e.message.orEmpty().contains("find any valid host", ignoreCase = true)) {
                    AdbBridge.ConnectionResult.NoEndpoint
                } else {
                    AdbBridge.ConnectionResult.Rejected(KEY_REFUSED)
                }
            } catch (t: Throwable) {
                AdbBridge.ConnectionResult.Rejected(CONNECT_FAILED)
            }
        }
    }

    override fun isConnected(): Boolean = gate.isConnected()

    // Deliberately NOT under [opLock]: teardown must abort an in-flight shell, not queue
    // behind it (the gate closes the socket underneath the blocked read).
    override fun disconnect() = gate.closeQuietly()

    override suspend fun shell(command: String): AdbBridge.ShellResult = withContext(io) {
        opLock.withLock { shellLocked(command) }
    }

    private suspend fun shellLocked(command: String): AdbBridge.ShellResult {
        if (!gate.isConnected()) return AdbBridge.ShellResult.NotConnected
        // Legacy `shell:` has no exit codes (no shell_v2 in libadb-android): wrap the command so
        // the last line carries `$?`. Braces make multi-command inputs behave as one unit.
        val wrapped = "{ $command ; } 2>&1; echo $EXIT_SENTINEL$?"
        val raw = try {
            withTimeout(shellTimeoutMs) { gate.exec(wrapped) }
        } catch (t: TimeoutCancellationException) {
            return AdbBridge.ShellResult.TransportFailure("shell timed out after ${shellTimeoutMs}ms")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return AdbBridge.ShellResult.TransportFailure(t.message ?: t.javaClass.simpleName)
        }
        return parseSentinel(raw)
    }

    private fun parseSentinel(raw: String): AdbBridge.ShellResult {
        val idx = raw.lastIndexOf(EXIT_SENTINEL)
        if (idx < 0) {
            // Stream ended before the sentinel — the connection died mid-command.
            return AdbBridge.ShellResult.TransportFailure("shell stream ended without status")
        }
        val exitCode = raw.substring(idx + EXIT_SENTINEL.length).trim().toIntOrNull()
            ?: return AdbBridge.ShellResult.TransportFailure("unparseable shell status")
        val stdout = raw.substring(0, idx).removeSuffix("\n")
        // stderr rides stdout on the legacy shell service (2>&1 above) — kept as a separate
        // field for interface stability; always empty from this implementation.
        return AdbBridge.ShellResult.Completed(exitCode = exitCode, stdout = stdout, stderr = "")
    }

    override suspend fun selfGrant(permission: String): AdbBridge.GrantResult {
        val command = ShellArgs.command("pm", "grant", selfPackage, permission)
            ?: return AdbBridge.GrantResult.REJECTED
        return when (val result = shell(command)) {
            is AdbBridge.ShellResult.Completed ->
                if (result.ok) AdbBridge.GrantResult.GRANTED else AdbBridge.GrantResult.REJECTED

            is AdbBridge.ShellResult.NotConnected -> AdbBridge.GrantResult.BRIDGE_UNAVAILABLE
            is AdbBridge.ShellResult.TransportFailure -> AdbBridge.GrantResult.BRIDGE_UNAVAILABLE
        }
    }

    /**
     * Batched silent install (ADR-001 V6): create a session, stream the staged APK in, commit.
     * Degrades to [AdbBridge.InstallResult.Failed] on any step — the Tier-0 per-tap installer
     * remains the fallback.
     */
    override suspend fun installApk(stagedApkPath: String): AdbBridge.InstallResult {
        val create = ShellArgs.command("pm", "install-create", "--user", "0")
            ?: return AdbBridge.InstallResult.Failed("bad arguments")
        val created = shell(create)
        if (created !is AdbBridge.ShellResult.Completed) {
            return AdbBridge.InstallResult.BridgeUnavailable
        }
        val sessionId = SESSION_ID.find(created.stdout)?.groupValues?.get(1)
            ?: return AdbBridge.InstallResult.Failed("no install session: ${created.stdout.trim().take(120)}")

        val write = ShellArgs.command("pm", "install-write", sessionId, "base.apk", stagedApkPath)
            ?: return AdbBridge.InstallResult.Failed("bad apk path")
        val written = shell(write)
        if (written !is AdbBridge.ShellResult.Completed || !written.ok) {
            shellQuietly("pm", "install-abandon", sessionId)
            return AdbBridge.InstallResult.Failed("install-write failed")
        }

        val commit = ShellArgs.command("pm", "install-commit", sessionId)
            ?: return AdbBridge.InstallResult.Failed("bad session")
        // The exit code is the authoritative verdict; matching pm's "Success" string would
        // break on output-format drift (review 2026-06-12). A failed commit abandons the
        // session rather than leaving it for OS reaping.
        return when (val committed = shell(commit)) {
            is AdbBridge.ShellResult.Completed ->
                if (committed.ok) {
                    AdbBridge.InstallResult.Installed
                } else {
                    shellQuietly("pm", "install-abandon", sessionId)
                    AdbBridge.InstallResult.Failed(committed.stdout.trim().take(200))
                }

            else -> AdbBridge.InstallResult.BridgeUnavailable
        }
    }

    /**
     * ADR-001 V4–V7, one independent, non-invasive probe per capability. A probe that RAN and was
     * refused (Completed-not-ok / GrantResult.REJECTED) marks only its own capability absent. But a
     * probe that could not RUN — NotConnected / TransportFailure — is INCONCLUSIVE, not evidence of
     * absence; counting it as absent silently under-reports a fully-capable device.
     *
     * Observed on hardware (2026-06-13, rango): the FIRST sweep after pairing came back "Basic
     * transfer" with the two LARGE-output probes (`pm list permissions`, `dumpsys role`) failing —
     * they hiccup on the cold connection while the link stays UP — yet re-running the wizard (a
     * fresh connect + probe) reported every capability AUTOMATIC. So the right response to ANY
     * inconclusive sweep is to get a FRESH link (disconnect + reconnect) and re-probe, replicating
     * that manual re-run — NOT to gate on whether the link is still "connected" (it usually is).
     *
     * The probes are idempotent and non-invasive (self-grant is idempotent, listings read-only);
     * the one non-idempotent probe is the install session, opened-then-abandoned best-effort (a
     * drop during abandon orphans a session the OS reaps — nothing is ever staged or committed).
     * Bounded by MAX_PROBE_ATTEMPTS; capabilities only accrue across attempts; the last attempt
     * returns the best partial. The caller (wizard) still disconnects afterward — holding shell uid
     * open in the background is forbidden.
     */
    override suspend fun probeCapabilities(): Set<AdbBridge.PrivilegedCapability> {
        if (!gate.isConnected()) return emptySet()
        var found = emptySet<AdbBridge.PrivilegedCapability>()
        repeat(MAX_PROBE_ATTEMPTS) { attempt ->
            val sweep = runProbeSweep()
            found = found + sweep.capabilities // capabilities only accrue as the link recovers
            if (!sweep.transportFailed) return found // a complete sweep is authoritative
            if (attempt == MAX_PROBE_ATTEMPTS - 1) return found // out of attempts → best partial
            // A probe could not RUN: a cold-connection transient on a STILL-LIVE link (the hardware
            // failure mode) or a real drop. Either way get a FRESH link and re-probe — exactly the
            // manual "re-run Advanced transfer setup" that recovers on device. Bail if it won't come back.
            disconnect()
            if (connect() !is AdbBridge.ConnectionResult.Connected) return found
        }
        return found
    }

    private data class ProbeSweep(
        val capabilities: Set<AdbBridge.PrivilegedCapability>,
        /** A probe could not RUN (NotConnected / TransportFailure) — its verdict is inconclusive. */
        val transportFailed: Boolean,
    )

    /**
     * One full V4–V7 sweep: returns the capabilities verified present, plus whether any probe hit a
     * transport-level failure (link down / timeout) rather than producing a real absence verdict.
     */
    private suspend fun runProbeSweep(): ProbeSweep {
        val found = mutableSetOf<AdbBridge.PrivilegedCapability>()
        var transportFailed = false

        // Run a read-only probe command. A transport failure (dropped link / timeout) is
        // inconclusive — flag it and return null so the probe never counts it as a real absence.
        suspend fun completed(command: String): AdbBridge.ShellResult.Completed? =
            when (val r = shell(command)) {
                is AdbBridge.ShellResult.Completed -> r
                AdbBridge.ShellResult.NotConnected -> null.also { transportFailed = true }
                is AdbBridge.ShellResult.TransportFailure -> null.also { transportFailed = true }
            }

        probe { // SHELL — the floor (V2/V3 analogue): are we really uid 2000?
            val id = completed("id")
            if (id != null && id.ok && id.stdout.contains("uid=2000")) {
                found += AdbBridge.PrivilegedCapability.SHELL
            }
        }
        probe { // SETTINGS_SECURE (V4) — the one-shot self-grant itself.
            when (selfGrant(WRITE_SECURE_SETTINGS)) {
                AdbBridge.GrantResult.GRANTED -> found += AdbBridge.PrivilegedCapability.SETTINGS_SECURE
                AdbBridge.GrantResult.REJECTED -> Unit // the grant ran and was refused — truly absent
                AdbBridge.GrantResult.BRIDGE_UNAVAILABLE -> transportFailed = true // could not run
            }
        }
        probe { // PERMISSION_PARITY (V7) — pm permission machinery reachable, read-only.
            val pm = completed("pm list permissions")
            if (pm != null && pm.ok) found += AdbBridge.PrivilegedCapability.PERMISSION_PARITY
        }
        probe { // SILENT_INSTALL (V6) — open a session, then abandon it immediately.
            val create = completed("pm install-create --user 0")
            if (create != null && create.ok) {
                SESSION_ID.find(create.stdout)?.groupValues?.get(1)?.let { id ->
                    found += AdbBridge.PrivilegedCapability.SILENT_INSTALL
                    shellQuietly("pm", "install-abandon", id)
                }
            }
        }
        probe { // NAV_MODE (V7) — the navigation overlays exist and cmd overlay answers.
            val overlays = completed("cmd overlay list android")
            if (overlays != null && overlays.ok &&
                overlays.stdout.contains("com.android.internal.systemui.navbar")
            ) {
                found += AdbBridge.PrivilegedCapability.NAV_MODE
            }
        }
        probe { // SMS_ROLE (V7) — role service reachable (read-only; shell holds DUMP).
            val role = completed("dumpsys role")
            if (role != null && role.ok) found += AdbBridge.PrivilegedCapability.SMS_ROLE
        }
        return ProbeSweep(found, transportFailed)
    }

    /** Isolation wrapper: a probe's failure must never abort the sweep. */
    private suspend fun probe(block: suspend () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // This probe's capability simply stays absent.
        }
    }

    private suspend fun shellQuietly(vararg argv: String) {
        val command = ShellArgs.command(*argv) ?: return
        try {
            shell(command)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Best-effort cleanup.
        }
    }

    internal companion object {
        const val EXIT_SENTINEL = "__PORTAGE_EXIT__"
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

        const val PAIR_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SHELL_TIMEOUT_MS = 20_000L
        const val TIMEOUT_SLACK_MS = 2_000L

        /** Sweeps to attempt when the link drops MID-sweep (idempotent probes, so re-running is safe). */
        const val MAX_PROBE_ATTEMPTS = 3

        const val PAIRING_ENDPOINT_DOWN =
            "pairing endpoint unreachable — keep the pairing dialog open and retry"
        const val PAIRING_FAILED = "pairing failed — reopen the pairing dialog and retry"
        const val KEY_REFUSED = "adbd refused our key — re-pair required"
        const val CONNECT_FAILED = "debug connection failed — toggle Wireless debugging and retry"

        val SESSION_ID = Regex("\\[(\\d+)]")
    }
}
