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
import cc.grepon.portage.providers.inventory.InstallStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstallLaunchTest {

    private fun action(uri: String, store: InstallStore = InstallStore.FDROID) =
        InstallAction("com.example.app", "Example", store, uri)

    @Test
    fun `passes through https and market store URIs`() {
        val https = "https://f-droid.org/packages/com.example.app"
        val market = "market://details?id=com.example.app"
        assertThat(InstallLaunch.safeUri(action(https))).isEqualTo(https)
        assertThat(InstallLaunch.safeUri(action(market))).isEqualTo(market)
    }

    @Test
    fun `rejects every other scheme — fail closed before any intent fires`() {
        // The action's uri is attacker-influenced upstream; the consumer re-validates the
        // scheme so a crafted value can never become an ACTION_VIEW to somewhere hostile
        // (Gap-1 security follow-up: scheme allowlist {https, market}).
        listOf(
            "http://insecure.example/app",      // downgrade — refused
            "intent://evil#Intent;scheme=x;end", // intent smuggling
            "javascript:alert(1)",
            "file:///data/data/x",
            "content://media/external",
            "market2://details?id=x",
            "",
            "no-scheme-here",
        ).forEach { hostile ->
            assertThat(InstallLaunch.safeUri(action(hostile))).isNull()
        }
    }

    @Test
    fun `scheme match is case-insensitive`() {
        assertThat(InstallLaunch.safeUri(action("HTTPS://f-droid.org/packages/com.example.app"))).isNotNull()
        assertThat(InstallLaunch.safeUri(action("Market://details?id=com.example.app"))).isNotNull()
    }
}
