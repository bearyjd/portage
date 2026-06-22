/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * Pairing/connect mechanics use libadb-android © Muntashir Al-Islam and contributors,
 * used under the Apache License 2.0 (dual GPL-3.0-or-later OR Apache-2.0; we elect
 * Apache-2.0 — see docs/prp/ADR-003-self-contained-privilege.md and NOTICE).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.adbbridge

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * The thin device-facing layer of the bridge: libadb-android does the ADB/TLS/SPAKE2 wire
 * work; this class only adapts it to [AdbDeviceGate]. Deliberately logic-free — everything
 * decidable lives (tested) in [LocalAdbBridge].
 *
 * Lifecycle notes pinned to libadb-android 3.1.1 behavior:
 *  - `disconnect()` (not `close()`) for routine teardown — `close()` destroys the private key.
 *  - `connectTls` returns false when already connected; the bridge re-checks [isConnected].
 *  - `pair` signals failure exclusively by throwing.
 */
internal class LibAdbDeviceGate(
    context: Context,
    private val keyStore: AdbKeyStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AdbDeviceGate {

    private val appContext = context.applicationContext

    // One ADB identity + one manager per process (libadb-android caches a static SSLContext
    // keyed to the first identity it sees). Created lazily on the IO dispatcher at first use.
    // The Lazy handle is kept so isConnected/closeQuietly never FORCE init (key generation)
    // just to answer "no" / tear down nothing (code review 2026-06-12, MEDIUM).
    private val lazyManager = lazy { PortageAdbManager(keyStore.load()) }
    private val manager: AbsAdbConnectionManager by lazyManager

    override suspend fun pair(port: Int, pairingCode: String) {
        runInterruptible(io) {
            // Never log the code. Returns true or throws (3.1.1 semantics).
            manager.pair(LOCALHOST, port, pairingCode)
        }
    }

    override fun isWirelessDebuggingEnabled(): Boolean = runCatching {
        // No SDK constant; the key is stable AOSP ("adb_wifi_enabled"), the same one
        // AndroidWizardEnvironment and LADB read. Absent ⇒ off. Never throws (read-only Settings).
        Settings.Global.getInt(appContext.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    override suspend fun connect(timeoutMs: Long): Boolean = runInterruptible(io) {
        manager.connectTls(appContext, timeoutMs)
    }

    override fun isConnected(): Boolean =
        lazyManager.isInitialized() && runCatching { manager.isConnected }.getOrDefault(false)

    override fun closeQuietly() {
        // Closes the underlying connection; an in-flight exec's read aborts with an
        // IOException rather than blocking forever. A never-initialized gate is a no-op.
        if (lazyManager.isInitialized()) runCatching { manager.disconnect() }
    }

    override suspend fun exec(command: String): String = runInterruptible(io) {
        val stream = manager.openStream("shell:$command")
        try {
            drainToEof(stream.openInputStream())
        } finally {
            runCatching { stream.close() }
        }
    }

    override suspend fun execWithStdin(
        command: String,
        input: java.io.InputStream,
        size: Long,
    ): String = runInterruptible(io) {
        // The binary-safe `exec:` service: no pty, no line-ending translation — the only channel
        // safe for `pm install-write -S <size> .. -`'s binary write phase. Ordering is load-bearing:
        // write ALL `size` stdin bytes and flush FIRST, then read stdout. `pm install-write -S`
        // reads exactly `size` bytes, then prints its result — interleaving would deadlock.
        val stream = manager.openStream("exec:$command")
        try {
            val sink = stream.openOutputStream()
            val buffer = ByteArray(WRITE_BUFFER_BYTES)
            var remaining = size
            while (remaining > 0) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val n = input.read(buffer, 0, want)
                if (n < 0) throw IOException("stdin ended early: $remaining of $size unsent")
                sink.write(buffer, 0, n)
                remaining -= n
            }
            sink.flush()
            drainToEof(stream.openInputStream())
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * Read [stdout] to EOF, capped at [MAX_OUTPUT_BYTES]. A blocked read surfaces stream teardown as
     * "Stream closed." — treated as EOF, exactly as the legacy `shell:` path does.
     */
    private fun drainToEof(stdout: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (out.size() < MAX_OUTPUT_BYTES) {
            val n = try {
                stdout.read(buffer)
            } catch (e: IOException) {
                // A blocked read surfaces stream teardown as "Stream closed." — EOF for us.
                if (e.message.orEmpty().contains("Stream closed", ignoreCase = true)) -1 else throw e
            }
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    internal companion object {
        const val LOCALHOST = "127.0.0.1"
        const val READ_BUFFER_BYTES = 8192
        const val WRITE_BUFFER_BYTES = 8192
        const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024 // defensive cap; no portage op needs more

        /**
         * AOSP `Settings.Global` key for the Wireless Debugging toggle (no SDK constant exists).
         * REPLICATED in `:wizard`'s `AndroidWizardEnvironment` (the two modules are dependency-
         * isolated by design). Each module's `adb_wifi_enabled key is pinned` test hardcodes this
         * canonical string, so a divergent edit fails CI — keep both copies in lockstep.
         */
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}

/**
 * libadb-android subclass supplying our persisted identity. `setApi` tells the library the
 * adbd version it speaks to — for self-connection that is exactly this device's SDK.
 */
private class PortageAdbManager(private val identity: AdbIdentity) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = DEVICE_NAME
}

private const val DEVICE_NAME = "portage"

/** Process-scoped construction of the production bridge. The only public way to get one. */
object AdbBridges {

    @Volatile
    private var cached: AdbBridge? = null

    fun local(context: Context): AdbBridge {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext
            val keyStore = AdbKeyStore(File(app.filesDir, KEY_DIR))
            return LocalAdbBridge(
                selfPackage = app.packageName,
                gate = LibAdbDeviceGate(app, keyStore),
            ).also { cached = it }
        }
    }

    private const val KEY_DIR = "adb-bridge"
}
