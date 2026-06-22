/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.install

import com.ventouxlabs.portage.providers.inventory.InstallAction

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

    /** For the https scheme, pin the host to the known stores — independent of the producer. */
    private val ALLOWED_HTTPS_HOSTS = setOf("play.google.com", "f-droid.org")

    fun safeUri(action: InstallAction): String? {
        val uri = action.uri
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val scheme = uri.substring(0, colon).lowercase()
        if (scheme !in ALLOWED_SCHEMES) return null
        if (scheme == "https") {
            // Host-pin https so even a future producer change can't point a tap off-store
            // (security review 2026-06-11, LOW: defense-in-depth over the package grammar).
            val host = runCatching { java.net.URI(uri).host }.getOrNull()?.lowercase() ?: return null
            if (host !in ALLOWED_HTTPS_HOSTS) return null
        }
        return uri
    }
}
