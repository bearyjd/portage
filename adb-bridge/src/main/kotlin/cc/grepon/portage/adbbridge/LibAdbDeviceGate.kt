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
package cc.grepon.portage.adbbridge

import android.content.Context
import android.os.Build
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
    private val manager: AbsAdbConnectionManager by lazy {
        PortageAdbManager(keyStore.load())
    }

    override suspend fun pair(port: Int, pairingCode: String) {
        runInterruptible(io) {
            // Never log the code. Returns true or throws (3.1.1 semantics).
            manager.pair(LOCALHOST, port, pairingCode)
        }
    }

    override suspend fun connect(timeoutMs: Long): Boolean = runInterruptible(io) {
        manager.connectTls(appContext, timeoutMs)
    }

    override fun isConnected(): Boolean = runCatching { manager.isConnected }.getOrDefault(false)

    override fun closeQuietly() {
        // Closes the underlying connection; an in-flight exec's read aborts with an
        // IOException rather than blocking forever.
        runCatching { manager.disconnect() }
    }

    override suspend fun exec(command: String): String = runInterruptible(io) {
        val stream = manager.openStream("shell:$command")
        try {
            val out = ByteArrayOutputStream()
            val input = stream.openInputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (out.size() < MAX_OUTPUT_BYTES) {
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    // A blocked read surfaces stream teardown as "Stream closed." — EOF for us.
                    if (e.message.orEmpty().contains("Stream closed", ignoreCase = true)) -1 else throw e
                }
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        } finally {
            runCatching { stream.close() }
        }
    }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val READ_BUFFER_BYTES = 8192
        const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024 // defensive cap; no portage op needs more
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
