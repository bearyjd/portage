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

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Real [PrivilegedAccess] over the Shizuku 13.x permission API. Availability and the one-shot grant
 * delegate to [ops] (a [ShizukuPrivilegedOps], whose decision logic is tested through the
 * [ShizukuGate] seam). The two device-only additions here are [canWriteSecureSettings] — a plain
 * self-permission check — and [requestAccess], the Shizuku authorization dialog.
 *
 * NOT unit-tested: [requestAccess] touches the Shizuku binder + permission listener, which exist
 * only on a device (ADR-001 V4/V5, same rationale as [AndroidShizukuGate]). Every Shizuku static is
 * guarded so a dead binder fails closed. The receiver's unlock orchestration is exercised against a
 * fake [PrivilegedAccess] in app-recv.
 */
class AndroidPrivilegedAccess(
    context: Context,
    private val ops: PrivilegedOps,
) : PrivilegedAccess {

    private val appContext = context.applicationContext

    constructor(context: Context) : this(context, ShizukuPrivilegedOps(context))

    override fun availability(): PrivilegedOps.Availability = ops.availability()

    override fun canWriteSecureSettings(): Boolean =
        runCatching {
            appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    override suspend fun ensureWriteSecureSettingsGranted(): PrivilegedOps.GrantOutcome =
        ops.ensureWriteSecureSettingsGranted()

    override suspend fun requestAccess(): Boolean {
        // Only a reachable, modern server can be authorized. Already-authorized short-circuits to
        // true (no second prompt); a dead binder / pre-v11 returns false so the UI shows guidance.
        val reachable = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
        if (!reachable) return false
        val alreadyHeld = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (alreadyHeld) return true

        // Bound the wait: a Shizuku dialog the user never answers must not hang the unlock coroutine
        // (mirrors the SMS role-dialog cap). A timeout cancels the await, which removes the listener.
        return withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val settled = AtomicBoolean(false)
                fun settle(granted: Boolean) {
                    if (settled.compareAndSet(false, true) && cont.isActive) cont.resume(granted)
                }
                // `lateinit` so the listener can unregister itself; it is assigned before the async
                // callback can fire (addRequestPermissionResultListener is called after this line).
                lateinit var listener: Shizuku.OnRequestPermissionResultListener
                listener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
                    if (code != REQUEST_CODE) return@OnRequestPermissionResultListener
                    runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                    settle(grantResult == PackageManager.PERMISSION_GRANTED)
                }
                Shizuku.addRequestPermissionResultListener(listener)
                cont.invokeOnCancellation {
                    runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                }
                // If the request itself can't be issued, fail closed now rather than waiting out
                // the full timeout for a result that will never come.
                val issued = runCatching { Shizuku.requestPermission(REQUEST_CODE) }.isSuccess
                if (!issued) {
                    runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                    settle(false)
                }
            }
        } ?: false
    }

    private companion object {
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

        // Arbitrary non-zero request code; the listener filters on it so a stray result for another
        // caller's request is ignored.
        const val REQUEST_CODE = 0x504F // 'P','O' — portage

        // Generous (the user may read the Shizuku dialog), but finite — a never-answered dialog
        // must not hang the unlock. 2 minutes, matching AndroidSmsRoleCoordinator.
        const val PERMISSION_TIMEOUT_MS = 120_000L
    }
}
