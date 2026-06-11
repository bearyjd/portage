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

import cc.grepon.portage.providers.inventory.AppRecord
import cc.grepon.portage.providers.inventory.InstallAction
import cc.grepon.portage.providers.inventory.InstallStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstallLaunchTest {

    private fun action(uri: String, store: InstallStore = InstallStore.FDROID) =
        InstallAction("com.example.app", "Example", store, uri)

    @Test
    fun `passes through known-store https and market URIs`() {
        listOf(
            "https://f-droid.org/packages/com.example.app",
            "https://play.google.com/store/apps/details?id=com.example.app",
            "market://details?id=com.example.app",
        ).forEach { uri ->
            assertThat(InstallLaunch.safeUri(action(uri))).isEqualTo(uri)
        }
    }

    @Test
    fun `rejects an https uri whose host is not a known store`() {
        listOf(
            "https://evil.example/packages/com.example.app",      // wrong host
            "https://play.google.com.evil.example/x",             // lookalike suffix
            "https://f-droid.org.attacker.test/packages/x",       // lookalike suffix
            "https:no-host",                                      // no authority at all
        ).forEach { uri ->
            assertThat(InstallLaunch.safeUri(action(uri))).isNull()
        }
    }

    @Test
    fun `every store's generated deep link survives safeUri — producer and consumer stay in sync`() {
        // Guards against the allowlist silently desyncing from InstallAction.from's templates
        // (code review 2026-06-11, MEDIUM): every store a producer can emit must remain launchable.
        val installerFor = mapOf(
            InstallStore.PLAY to "com.android.vending",
            InstallStore.FDROID to "org.fdroid.fdroid",
            InstallStore.AURORA to "com.aurora.store",
            InstallStore.UNKNOWN to "com.some.sideloader",
        )
        InstallStore.entries.forEach { store ->
            val generated = InstallAction.from(AppRecord("com.example.app", 1, installerFor.getValue(store), "X"))
            assertThat(generated).isNotNull()
            assertThat(generated!!.store).isEqualTo(store)
            assertThat(InstallLaunch.safeUri(generated)).isNotNull()
        }
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
