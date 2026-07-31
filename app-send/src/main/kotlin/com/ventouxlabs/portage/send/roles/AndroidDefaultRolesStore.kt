/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.roles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.ventouxlabs.portage.providers.roles.DefaultRolesStore
import com.ventouxlabs.portage.providers.roles.RestorableRole

/**
 * Reads the user's current default browser / dialer / launcher using ORDINARY INTENT RESOLUTION —
 * no privilege, no ADB bridge, no runtime permission (#122).
 *
 * This is what keeps the sender clean. `RoleManager.getRoleHolders()` would answer the same
 * question but needs `MANAGE_ROLE_HOLDERS` (`protectionLevel: signature`, unreachable for a
 * non-platform-signed app), and `RoleManager.isRoleHeld()` only ever answers about the CALLER.
 * `PackageManager.resolveActivity` needs neither: it reports the activity the system would launch,
 * which for these three intents IS the user's chosen default.
 *
 * Measured on GrapheneOS A17 (`SPIKE-RESULTS-2026-07-31.md` §2): resolution returned exactly what
 * `cmd role get-role-holders` reported for all three roles, each with `isDefault=true`. So the
 * capture side needs no privilege at all and `app-send` continues to link no privilege stack — the
 * no-escalation CI assert is unaffected by this feature.
 *
 * Returns null when there is no unambiguous default. That matters: when the user has NOT chosen a
 * default, Android resolves to the system resolver/chooser activity, and shipping that as "your
 * default browser" would be wrong. Those sentinels are filtered out rather than carried.
 */
class AndroidDefaultRolesStore(context: Context) : DefaultRolesStore {

    private val packageManager: PackageManager = context.packageManager
    private val selfPackage: String = context.packageName

    override fun currentHolder(role: RestorableRole): String? {
        val intent = when (role) {
            RestorableRole.BROWSER ->
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)

            RestorableRole.DIALER ->
                Intent(Intent.ACTION_DIAL)

            RestorableRole.HOME ->
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        }

        val resolved = runCatching {
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() ?: return null

        val pkg = resolved.activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: return null

        // Drop the "no default chosen" sentinels. Android reports the resolver/chooser itself when
        // nothing is set; carrying that would restore the chooser as the user's "default".
        if (pkg in RESOLVER_SENTINELS) return null
        // Never carry ourselves: portage handling one of these intents in some future build must not
        // turn into "portage was your default browser".
        if (pkg == selfPackage) return null

        return pkg
    }

    private companion object {
        /** Framework activities that mean "the user has not picked a default". */
        val RESOLVER_SENTINELS = setOf("android", "com.android.internal.app.ResolverActivity")
    }
}
