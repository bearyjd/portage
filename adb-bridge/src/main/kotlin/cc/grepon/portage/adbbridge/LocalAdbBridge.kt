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
            // HANG GUARD (every caller, uniformly): with Wireless Debugging off there is no
            // _adb-tls-connect endpoint, and libadb's NsdManager mDNS discovery wait IGNORES thread
            // interruption on-device (GOS A16) — so gate.connect() would hang INDEFINITELY (withTimeout
            // fires but the worker never unwinds, holding opLock) and closeQuietly() cannot rescue an
            // in-flight discovery. Gate up front and never call connect() into a missing endpoint; the
            // wizard's route() uses the same guard. NoEndpoint is exactly "Wireless Debugging is off".
            if (!gate.isWirelessDebuggingEnabled()) return@withLock AdbBridge.ConnectionResult.NoEndpoint
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
     * Batched silent install of a split app (ADR-001 V6, ADR-006): open ONE session, write every
     * staged file into it under its own name, then commit once. Degrades to
     * [AdbBridge.InstallResult.Failed] on any step — the Tier-0 per-tap installer remains the
     * fallback.
     *
     * AC-6b defence in depth: every [AdbBridge.StagedApk.name] is re-validated here — the bridge is
     * the privilege boundary and the name is wire-derived, so it is checked again right before it
     * becomes a `pm install-write` argument even though the caller already validated it. A bad name
     * abandons the session and fails the install; it is never written. The bridge also enforces the
     * one-base structural invariant (exactly one [BASE_NAME] file) BEFORE opening a session, so a
     * zero-base or multi-base set is refused cheaply rather than relying on `pm install-commit` to
     * reject it. The file PATHS are caller-generated (not sender-controlled) but still flow through
     * [ShellArgs.command].
     *
     * Each file's bytes are STREAMED over the adb `exec:` stream's stdin into
     * `pm install-write -S <size> <session> <name> -` (ADR-006 P6); no shell-readable path ever
     * exists, so the receiver's app-private staging stays unreadable to shell uid as required. The
     * `exec:` service has no exit code — install-write success is parsed from pm's own output (it
     * prints "Success" on a good write), and a transport-level failure of the streamed write
     * classifies as [AdbBridge.InstallResult.BridgeUnavailable] (consistent with create/commit) so
     * Phase 4's apply provider falls back to Tier-0 on a dead bridge rather than reporting a refusal.
     */
    override suspend fun installApk(staged: List<AdbBridge.StagedApk>): AdbBridge.InstallResult {
        if (staged.isEmpty()) return AdbBridge.InstallResult.Failed("no staged apk files")
        // Structural invariant at the privilege boundary: exactly one base before any session opens.
        if (staged.count { it.name == BASE_NAME } != 1) {
            return AdbBridge.InstallResult.Failed("install set must contain exactly one base")
        }

        val create = ShellArgs.command("pm", "install-create", "--user", "0")
            ?: return AdbBridge.InstallResult.Failed("bad arguments")
        val created = shell(create)
        if (created !is AdbBridge.ShellResult.Completed) {
            return AdbBridge.InstallResult.BridgeUnavailable
        }
        val sessionId = SESSION_ID.find(created.stdout)?.groupValues?.get(1)
            ?: return AdbBridge.InstallResult.Failed("no install session: ${created.stdout.trim().take(120)}")

        for (file in staged) {
            // Re-validate the name at the boundary; a bad one never reaches install-write.
            val name = validatedSplitNameOrNull(file.name)
            if (name == null) {
                shellQuietly("pm", "install-abandon", sessionId)
                return AdbBridge.InstallResult.Failed("rejected split name")
            }
            // `-S <size> .. -`: pm reads exactly <size> bytes from stdin, so the bytes are piped over
            // the `exec:` stream and never staged to a shell-readable path. The size and name flow
            // through ShellArgs like every other argument.
            val write = ShellArgs.command(
                "pm", "install-write", "-S", file.size.toString(), sessionId, name, "-",
            )
            if (write == null) {
                shellQuietly("pm", "install-abandon", sessionId)
                return AdbBridge.InstallResult.Failed("bad install-write arguments")
            }
            // `exec:` has no exit code — a transport throw is null (→ BridgeUnavailable), and the
            // verdict otherwise comes from pm's own output. install-create/commit keep the
            // BridgeUnavailable-vs-Failed distinction the apply provider relies on; a streamed-write
            // transport failure joins them so a dead bridge falls back to Tier-0, not a fake refusal.
            val output = installWriteStreamed(write, file)
                ?: run {
                    shellQuietly("pm", "install-abandon", sessionId)
                    return AdbBridge.InstallResult.BridgeUnavailable
                }
            if (!output.contains(INSTALL_WRITE_SUCCESS)) {
                shellQuietly("pm", "install-abandon", sessionId)
                return AdbBridge.InstallResult.Failed("install-write $name: ${output.trim().take(200)}")
            }
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
     * Root cause (characterized on hardware 2026-06-14, rango): a probe whose command emits LARGE
     * output (`pm list permissions` ~80 KB, `dumpsys role` ~10 KB) STALLS the cold libadb stream —
     * the read blocks the full SHELL_TIMEOUT and the sweep mis-reports those capabilities absent
     * ("Basic transfer ready", ~40s/sweep on "Checking access"). FIX: those two exit-code-only
     * probes redirect BOTH streams to /dev/null (`>/dev/null 2>&1`, see runProbeSweep) so nothing
     * large crosses the wire.
     * The disconnect+reconnect retry below stays as a BACKSTOP for a genuinely transient/dropped
     * link — no longer the primary recovery — so a fresh sweep no longer gates on whether the link
     * is still "connected" (it usually is).
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
            // `>/dev/null 2>&1`: only the exit code matters, and the full listing is ~80 KB. Large
            // wire output stalls the cold libadb stream (each big read hits SHELL_TIMEOUT → the probe
            // mis-reports the capability absent → "Basic transfer"). Discard BOTH streams on-device
            // (stderr too — the wrapper's 2>&1 would otherwise relay it); the exit code still proves
            // pm's permission machinery answered. (Verified on rango 2026-06-14.)
            val pm = completed("pm list permissions >/dev/null 2>&1")
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
            // `>/dev/null 2>&1`: exit-code-only check; `dumpsys role`'s ~10 KB dump stalls the cold
            // libadb stream the same way as PERMISSION_PARITY — keep both streams off the wire.
            val role = completed("dumpsys role >/dev/null 2>&1")
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

    /**
     * AC-6b name guard at the privilege boundary (defence in depth — the name is wire-derived and
     * attacker-influenced). Replicated locally so :adb-bridge stays free of a :providers dependency;
     * it must stay equivalent to providers' `ApkContainerValidation.validatedSplitNameOrNull`. Accepts
     * exactly [BASE_NAME] or a strict allowlist name (alphanumeric first char, then only
     * `[A-Za-z0-9._-]`); the allowlist structurally excludes '/', '\\', '..', whitespace, control
     * characters, and shell metacharacters. Returns the trusted name, or null to REJECT.
     */
    private fun validatedSplitNameOrNull(name: String): String? {
        if (name == BASE_NAME) return name
        if (name.isEmpty()) return null
        if (name == "." || name == "..") return null
        if (!SPLIT_NAME.matches(name)) return null
        return name
    }

    /**
     * Run one `pm install-write … -` command, streaming [file]'s exactly-[size] bytes to its stdin
     * over the binary-safe `exec:` channel, and return pm's stdout. Mirrors how [shell] wraps
     * [shellLocked]: serialized under [opLock], dispatched on [io], bounded by [shellTimeoutMs].
     * Returns null on a transport-level failure (dead link / timeout / I/O) — the caller treats that
     * as [AdbBridge.InstallResult.BridgeUnavailable]. [open]'s stream is always closed.
     */
    private suspend fun installWriteStreamed(
        command: String,
        file: AdbBridge.StagedApk,
    ): String? = withContext(io) {
        opLock.withLock {
            if (!gate.isConnected()) return@withLock null
            try {
                withTimeout(shellTimeoutMs) {
                    file.open().use { input -> gate.execWithStdin(command, input, file.size) }
                }
            } catch (t: TimeoutCancellationException) {
                null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            }
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

        /**
         * The `exec:` service has no exit code, so a streamed `pm install-write` is judged by pm's
         * own output: it prints "Success" on a good write (case-sensitive, as pm emits it). Anything
         * else (a "Failure [INSTALL_FAILED_…]" line or empty output) is a failed write.
         */
        const val INSTALL_WRITE_SUCCESS = "Success"

        const val PAIR_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SHELL_TIMEOUT_MS = 20_000L
        const val TIMEOUT_SLACK_MS = 2_000L

        /** Max sweeps to attempt on any inconclusive sweep (transient OR drop); idempotent probes, safe to re-run. */
        const val MAX_PROBE_ATTEMPTS = 3

        const val PAIRING_ENDPOINT_DOWN =
            "pairing endpoint unreachable — keep the pairing dialog open and retry"
        const val PAIRING_FAILED = "pairing failed — reopen the pairing dialog and retry"
        const val KEY_REFUSED = "adbd refused our key — re-pair required"
        const val CONNECT_FAILED = "debug connection failed — toggle Wireless debugging and retry"

        /**
         * Parses the session id from `pm install-create`, assumed to emit exactly one bracketed id
         * on a single line (first match wins) — the same output-format-drift exposure the commit
         * verdict deliberately avoids, accepted here under GOS-stable targeting.
         */
        val SESSION_ID = Regex("\\[(\\d+)]")

        /** The wire name a base APK must carry, exactly (ADR-006); anything else is a split name. */
        const val BASE_NAME = "base"

        /**
         * The strict split-name allowlist (ADR-006 AC-6b): alphanumeric first char, then only
         * `[A-Za-z0-9._-]`. Parity: `:adb-bridge` is intentionally dependency-isolated from
         * `:providers`, so this regex is REPLICATED from `ApkContainerValidation.SPLIT_NAME`, not
         * shared. There is no cross-module dep; each module's `SPLIT_NAME pattern is pinned` test
         * hardcodes the canonical string, so a divergent edit fails CI. Keep both copies and both
         * pins in lockstep.
         */
        val SPLIT_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
