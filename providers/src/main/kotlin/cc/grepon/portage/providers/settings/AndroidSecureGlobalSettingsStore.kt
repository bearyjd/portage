/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.settings

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import cc.grepon.portage.settings.Namespace

/**
 * Thin Settings.Secure / Settings.Global adapter behind [SecureGlobalSettingsStore] (ADR-001
 * reach table, T1_GRANT). Reads need no permission. Writes need WRITE_SECURE_SETTINGS, which the
 * grant architecture installs via a ONE-SHOT `pm grant` (ADR-001 §1) — after which writes use
 * the normal Settings.* API with no live bridge. [canWrite] reports whether the grant is held.
 *
 * WRITE_SECURE_SETTINGS is declared in the `:privileged` library manifest (so the shell uid can
 * `pm grant` it) and merges into the recv APK; the declaration is dormant and grants nothing
 * until that `pm grant` runs. The bridge that performs the grant
 * ([cc.grepon.portage.privileged.ShizukuPrivilegedOps.ensureWriteSecureSettingsGranted]) is the
 * deferred, on-device-verified Tier-1 follow-up — until it lands [canWrite] is false and the
 * apply provider self-skips every SECURE/GLOBAL key, leaving shipped Tier-0 behavior unchanged.
 *
 * [Namespace.SYSTEM] is never serviced here — it routes through [AndroidSystemSettingsStore]
 * (Tier 0). A SYSTEM call is a routing bug and is rejected fail-closed.
 */
class AndroidSecureGlobalSettingsStore(private val context: Context) : SecureGlobalSettingsStore {

    override fun read(namespace: Namespace, name: String): String? = runCatching {
        when (namespace) {
            Namespace.SECURE -> Settings.Secure.getString(context.contentResolver, name)
            Namespace.GLOBAL -> Settings.Global.getString(context.contentResolver, name)
            Namespace.SYSTEM -> null
        }
    }.getOrNull()

    override fun canWrite(): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    override fun write(namespace: Namespace, name: String, value: String): Boolean = runCatching {
        when (namespace) {
            Namespace.SECURE -> Settings.Secure.putString(context.contentResolver, name, value)
            Namespace.GLOBAL -> Settings.Global.putString(context.contentResolver, name, value)
            Namespace.SYSTEM -> false
        }
    }.getOrDefault(false)

    private companion object {
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    }
}
