/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.roles

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DefaultRolesProvidersTest {

    private fun store(vararg pairs: Pair<RestorableRole, String?>): DefaultRolesStore {
        val map = pairs.toMap()
        return DefaultRolesStore { role -> map[role] }
    }

    private fun payload(json: String) = ByteArrayInputStream(json.toByteArray())

    private fun applied(
        json: String,
        installed: Set<String> = setOf("com.example.browser", "com.example.dialer", "com.example.home"),
    ): Pair<ItemStatus, List<RoleRestoreCandidate>> {
        var seen: List<RoleRestoreCandidate> = emptyList()
        val provider = DefaultRolesApplyProvider(
            isInstalled = { it in installed },
            onCandidates = { seen = it },
        )
        val outcome = kotlinx.coroutines.runBlocking { provider.apply(payload(json)) }
        return outcome.status to seen
    }

    // ---- export ----

    @Test
    fun `export is unavailable when no role has an unambiguous holder`() = runTest {
        val provider = DefaultRolesExportProvider(
            store(
                RestorableRole.BROWSER to null,
                RestorableRole.DIALER to null,
                RestorableRole.HOME to null,
            ),
        )
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `export carries only roles with a well-formed package`() = runTest {
        val provider = DefaultRolesExportProvider(
            store(
                RestorableRole.BROWSER to "com.example.browser",
                // Not a valid package name — must be dropped, not shipped.
                RestorableRole.DIALER to "not a package",
                RestorableRole.HOME to null,
            ),
        )
        assertThat(provider.available()).isTrue()

        val sink = ByteArrayOutputStream()
        provider.exportTo(sink)
        val decoded = DefaultRolesCodec.decode(ByteArrayInputStream(sink.toByteArray()))

        assertThat(decoded?.roles).hasSize(1)
        assertThat(decoded?.roles?.single()?.role).isEqualTo(RestorableRole.BROWSER)
        assertThat(decoded?.roles?.single()?.packageName).isEqualTo("com.example.browser")
    }

    @Test
    fun `export declares the DEFAULT_ROLES kind`() {
        assertThat(DefaultRolesExportProvider(store()).kind).isEqualTo(ItemKind.DEFAULT_ROLES)
    }

    @Test
    fun `a throwing store degrades to nothing to send, never an exception`() = runTest {
        val provider = DefaultRolesExportProvider { error("store blew up") }
        assertThat(provider.available()).isFalse()
        // Must not throw — the Tier-0 graceful-degrade contract.
        provider.exportTo(ByteArrayOutputStream())
    }

    // ---- apply: the security-critical behaviours ----

    @Test
    fun `an UNKNOWN role name rejects the whole item — a hostile sender cannot name a new role`() {
        // This is the load-bearing test for the closed-set design. If the wire carried a free
        // string, "android.app.role.ASSISTANT" here would flow to `cmd role add-role-holder`.
        // Because it is an enum, deserialization fails and the entire item is refused.
        val (status, candidates) = applied(
            """{"roles":[{"role":"assistant","packageName":"com.evil.app"}]}""",
        )
        assertThat(status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(candidates).isEmpty()
    }

    @Test
    fun `an unknown role fails to DECODE, which is what makes the set closed`() {
        // Pins the mechanism one layer below the apply provider, so a future decoder change is
        // caught here rather than showing up as a mysterious WRITE_ERROR.
        //
        // WHY IT THROWS, precisely — this was verified by mutation, not assumed: `role` is a
        // NON-NULLABLE property with NO DEFAULT, and kotlinx.serialization only ever substitutes a
        // value for properties that are nullable or defaulted. Enabling `coerceInputValues` on
        // JsonLines.format therefore does NOT reopen this (checked: the suite still passes with it
        // on). The real hazard is different and worth naming: giving `role` a default value or
        // making it nullable WOULD let an unknown role silently become something valid. Don't.
        val decoded = runCatching {
            DefaultRolesCodec.decode(payload("""{"roles":[{"role":"assistant","packageName":"com.evil.app"}]}"""))
        }.getOrNull()
        assertThat(decoded).isNull()

        // ...and a KNOWN role through the same path still decodes, so the assertion above is about
        // the role value specifically and not a decoder that rejects everything.
        val ok = DefaultRolesCodec.decode(
            payload("""{"roles":[{"role":"browser","packageName":"com.example.browser"}]}"""),
        )
        assertThat(ok?.roles?.single()?.role).isEqualTo(RestorableRole.BROWSER)
    }

    @Test
    fun `apply NEVER restores anything by itself — it only surfaces candidates`() {
        // The consent guarantee: constructing and applying performs no restore. There is no
        // RoleRestorer here at all, by construction — the provider cannot reach one.
        val (status, candidates) = applied(
            """{"roles":[{"role":"browser","packageName":"com.example.browser"}]}""",
        )
        assertThat(status).isEqualTo(ItemStatus.OK)
        assertThat(candidates).containsExactly(
            RoleRestoreCandidate(RestorableRole.BROWSER, "com.example.browser"),
        )
    }

    @Test
    fun `a malformed package name is dropped`() {
        val (_, candidates) = applied(
            """{"roles":[{"role":"browser","packageName":"com.evil; rm -rf /"}]}""",
        )
        assertThat(candidates).isEmpty()
    }

    @Test
    fun `package names that ShellArgs alone would NOT stop are dropped here`() {
        // Layer note, verified against the real ShellArgs: its SAFE_ARG allowlist
        // (^[A-Za-z0-9_.,:/@+=-]+$) INCLUDES '-', so an argument like "-foo" passes through
        // UNQUOTED and `cmd role` could read it as a flag. ShellArgs is therefore not the control
        // that stops flag injection — THIS regex is, because it is a full match anchored on
        // [A-Za-z0-9_] segments. The two layers cover different things; neither is redundant.
        val hostile = listOf(
            "-com.evil",           // leading dash: would survive ShellArgs unquoted
            "--user",              // looks like a flag entirely
            "com.evil/../other",   // path traversal shape; '/' is in SAFE_ARG too
            "com.evil app",        // argument split
            "",                    // empty
            "noDotsAtAll",         // not a package (needs >= 2 segments)
        )
        hostile.forEach { pkg ->
            // CRITICAL: claim every hostile string IS installed. Otherwise the `isInstalled` filter
            // drops them and this test passes without the regex doing anything — it would be green
            // even with the package validation deleted. (Caught by mutation-testing: loosening
            // PACKAGE_NAME left this test passing until the installed-set was made permissive too.)
            val (_, candidates) = applied(
                """{"roles":[{"role":"browser","packageName":"$pkg"}]}""",
                installed = setOf(pkg),
            )
            assertThat(candidates).isEmpty()
        }
    }

    @Test
    fun `a duplicated role yields one candidate, not two`() {
        val (_, candidates) = applied(
            """{"roles":[
                {"role":"browser","packageName":"com.example.browser"},
                {"role":"browser","packageName":"com.example.dialer"}
            ]}""",
        )
        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().packageName).isEqualTo("com.example.browser")
    }

    @Test
    fun `a role whose app is not installed here is dropped and reported SKIPPED`() {
        val (status, candidates) = applied(
            """{"roles":[{"role":"browser","packageName":"com.example.browser"}]}""",
            installed = emptySet(),
        )
        assertThat(candidates).isEmpty()
        // SKIPPED, not WRITE_ERROR: "that app isn't here" is a defined outcome, not a failure.
        assertThat(status).isEqualTo(ItemStatus.SKIPPED)
    }

    @Test
    fun `an empty but well-formed snapshot is OK, not an error`() {
        val (status, candidates) = applied("""{"roles":[]}""")
        assertThat(status).isEqualTo(ItemStatus.OK)
        assertThat(candidates).isEmpty()
    }

    @Test
    fun `an unparseable payload is a WRITE_ERROR`() {
        val (status, _) = applied("this is not json")
        assertThat(status).isEqualTo(ItemStatus.WRITE_ERROR)
    }

    @Test
    fun `an isInstalled predicate that throws drops the entry instead of propagating`() {
        var seen: List<RoleRestoreCandidate>? = null
        val provider = DefaultRolesApplyProvider(
            isInstalled = { error("package manager unavailable") },
            onCandidates = { seen = it },
        )
        val outcome = kotlinx.coroutines.runBlocking {
            provider.apply(payload("""{"roles":[{"role":"home","packageName":"com.example.home"}]}"""))
        }
        assertThat(seen).isEmpty()
        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
    }

    @Test
    fun `round-trips every role through the wire`() = runTest {
        val provider = DefaultRolesExportProvider(
            store(
                RestorableRole.BROWSER to "com.example.browser",
                RestorableRole.DIALER to "com.example.dialer",
                RestorableRole.HOME to "com.example.home",
            ),
        )
        val sink = ByteArrayOutputStream()
        provider.exportTo(sink)

        val (status, candidates) = applied(sink.toString(Charsets.UTF_8.name()))
        assertThat(status).isEqualTo(ItemStatus.OK)
        assertThat(candidates.map { it.role })
            .containsExactlyElementsIn(RestorableRole.entries)
    }
}
