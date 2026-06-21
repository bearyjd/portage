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

/**
 * The seam between [LocalAdbBridge] (typed results, timeouts, probes — all unit-tested on the
 * JVM) and the actual ADB stack ([LibAdbDeviceGate] over libadb-android — verified on device
 * and in CI compile only). Mirrors the ShizukuGate pattern this repo used at the old privilege
 * boundary: the gate is dumb plumbing that throws; the bridge above it maps failures to types.
 */
internal interface AdbDeviceGate {

    /**
     * Run the Wireless Debugging pairing exchange. Returns normally on success; throws on
     * failure (libadb-android's `pair` signals failure exclusively by exception — typically
     * `SSLException`-family for a wrong/expired code, `ConnectException` for a dead port).
     */
    @Throws(Exception::class)
    suspend fun pair(port: Int, pairingCode: String)

    /**
     * Discover the local `_adb-tls-connect` endpoint and connect with our key. Returns true on
     * a (new or already-live) connection; throws on discovery/auth failure.
     */
    @Throws(Exception::class)
    suspend fun connect(timeoutMs: Long): Boolean

    fun isConnected(): Boolean

    /**
     * Close the connection. Idempotent, never throws, callable from any thread, and must
     * abort an in-flight [exec] by closing the underlying socket — never wait for it.
     */
    fun closeQuietly()

    /**
     * Run one `shell:` command and return everything the stream produced until EOF. The ADB
     * legacy shell service has no exit codes and no stderr separation — [LocalAdbBridge]
     * layers a sentinel on top. Throws on transport failure.
     */
    @Throws(Exception::class)
    suspend fun exec(command: String): String

    /** Run one binary-safe `exec:` command, streaming EXACTLY [size] bytes from [input] to the
     *  command's stdin, then return everything it wrote to stdout until EOF. Uses the adb `exec:`
     *  service (no pty, no line-ending translation) — the only safe channel for a binary write
     *  phase like `pm install-write -S <size> ... -`. `exec:` has NO exit code; callers parse the
     *  command's own output. Throws on transport failure. */
    @Throws(Exception::class)
    suspend fun execWithStdin(command: String, input: java.io.InputStream, size: Long): String
}
