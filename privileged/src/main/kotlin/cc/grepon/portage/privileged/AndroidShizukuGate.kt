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

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Real [ShizukuGate] over the Shizuku 13.x API. Every Shizuku call is guarded — the statics throw
 * `IllegalStateException` when the binder is dead, so each method fails closed to the safe value.
 *
 * NOT unit-tested: it touches the Shizuku binder and spawns a shell-uid [PrivilegedService], which
 * only exist on a device. ADR-001 isolates exactly this surface as the on-device-verified part
 * (V4/V5). The bridge's decision logic is tested through the [ShizukuGate] seam instead.
 */
internal class AndroidShizukuGate(context: Context) : ShizukuGate {

    private val appContext = context.applicationContext
    private val selfPackage = context.packageName

    // Identifies the UserService build. A stale fallback is harmless here: each grant is a fresh
    // bind torn down with remove=true (daemon(false)), so no service persists between calls for a
    // lower version to reuse. versionCode (Int) is deprecated but fine on this app's range.
    private val serviceVersion = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(selfPackage, 0).versionCode
    }.getOrDefault(1)

    override fun isInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    override fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override fun isPreV11(): Boolean = runCatching { Shizuku.isPreV11() }.getOrDefault(true)

    override fun hasPermission(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override suspend fun runAsShell(command: List<String>): Int? = suspendCancellableCoroutine { cont ->
        val args = Shizuku.UserServiceArgs(
            ComponentName(selfPackage, PrivilegedService::class.java.name),
        ).daemon(false) // dies with our process — correct for a one-shot grant
            .processNameSuffix("portage_priv")
            .version(serviceVersion)
            .tag(USER_SERVICE_TAG) // stable identity; the class name is unstable under R8

        // resume() throws if called twice (the connect/disconnect callbacks can race), so gate it.
        val settled = AtomicBoolean(false)
        fun settle(code: Int?) {
            if (settled.compareAndSet(false, true) && cont.isActive) cont.resume(code)
        }

        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                // The binder call blocks on the remote `pm` process; keep it off the callback thread.
                Thread({
                    val code = runCatching {
                        if (binder != null && binder.pingBinder()) {
                            IPrivilegedService.Stub.asInterface(binder).runCommand(command.toTypedArray())
                        } else {
                            null
                        }
                    }.onFailure { Log.w(TAG, "privileged runCommand failed", it) }.getOrNull()
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    settle(code)
                }, "portage-priv-grant").start()
            }

            override fun onServiceDisconnected(name: ComponentName?) = settle(null)
        }

        val bound = runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { Log.w(TAG, "bindUserService failed", it) }
        if (bound.isFailure) {
            // Release any partial registration before failing closed.
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            settle(null)
        }
        cont.invokeOnCancellation { runCatching { Shizuku.unbindUserService(args, connection, true) } }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val USER_SERVICE_TAG = "portage-privileged"
        const val TAG = "PortagePrivileged"
    }
}
