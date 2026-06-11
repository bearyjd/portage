/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.install

import cc.grepon.portage.providers.inventory.InstallAction

/**
 * Consumer-side gate for App-Inventory install deep links. The [InstallAction.uri] is built
 * from a sender-supplied package name; even though [InstallAction.from] already validates the
 * package grammar, the tap consumer re-checks the SCHEME before firing an `ACTION_VIEW`, so a
 * crafted value can never redirect the tap to a hostile target (Gap-1 security follow-up:
 * scheme allowlist {https, market}, fail-closed). Pure + JVM-testable; the Activity reuses it.
 */
object InstallLaunch {

    /** Only an app-store deep link is launchable; anything else returns null (no intent). */
    private val ALLOWED_SCHEMES = setOf("https", "market")

    fun safeUri(action: InstallAction): String? {
        val uri = action.uri
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val scheme = uri.substring(0, colon).lowercase()
        return if (scheme in ALLOWED_SCHEMES) uri else null
    }
}
