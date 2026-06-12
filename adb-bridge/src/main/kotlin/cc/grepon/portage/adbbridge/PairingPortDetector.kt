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

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Finds the Wireless Debugging PAIRING port. The `_adb-tls-pairing._tcp` service is advertised
 * ONLY while the "Pair device with pairing code" dialog is open in Developer options, so the
 * detector is started right when the wizard tells the user to open that dialog. Never
 * hardcoded — the port is random per dialog. A null result feeds the wizard's manual-entry
 * fallback (NsdManager flakiness is a known failure mode).
 */
fun interface PairingPortDetector {
    /** The pairing port, or null if none was advertised within [timeoutMs]. */
    suspend fun detectPairingPort(timeoutMs: Long): Int?
}

/** mDNS implementation over libadb-android's [AdbMdns] (built-in own-device filtering). */
class MdnsPairingPortDetector(context: Context) : PairingPortDetector {

    private val appContext = context.applicationContext

    override suspend fun detectPairingPort(timeoutMs: Long): Int? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            lateinit var mdns: AdbMdns
            mdns = AdbMdns(appContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
                // Fires on every advertisement change; -1 means service lost. Resume once.
                if (port > 0 && resumed.compareAndSet(false, true)) {
                    runCatching { mdns.stop() }
                    continuation.resume(port)
                }
            }
            continuation.invokeOnCancellation { runCatching { mdns.stop() } }
            mdns.start()
        }
    }
}
