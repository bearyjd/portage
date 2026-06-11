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

/**
 * Real [PrivilegedAccess] over the Shizuku 13.x permission API. Availability, the one-shot grant,
 * and the authorization decision all delegate to [ops] (a [ShizukuPrivilegedOps], whose decision
 * logic — including [requestAccess] — is pure and tested through the [ShizukuGate] seam). The only
 * remaining device-only surface here is [canWriteSecureSettings], a plain self-permission check.
 *
 * NOT unit-tested: [canWriteSecureSettings] reads `checkSelfPermission` off a real [Context], which
 * exists only on a device. It is guarded so a failure fails closed to false. The receiver's unlock
 * orchestration is exercised against a fake [PrivilegedAccess] in app-recv.
 */
class AndroidPrivilegedAccess(
    context: Context,
    private val ops: ShizukuPrivilegedOps,
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

    override suspend fun requestAccess(): Boolean = ops.requestAccess()

    private companion object {
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    }
}
