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
        canRestore: () -> Boolean = { true },
    ): Pair<ItemStatus, List<RoleRestoreCandidate>> {
        var seen: List<RoleRestoreCandidate> = emptyList()
        val provider = DefaultRolesApplyProvider(
            canRestore = canRestore,
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
            // The regex is now the ONLY thing that can drop these. This test used to need
            // `installed = setOf(pkg)` to stay honest — without it the installed-set filter dropped
            // the hostile strings and the test passed even with PACKAGE_NAME deleted (found by
            // mutation-testing). That filter has since moved to the receiver, so the confound is
            // gone by construction rather than by careful test setup.
            val (_, candidates) = applied("""{"roles":[{"role":"browser","packageName":"$pkg"}]}""")
            assertThat(candidates).isEmpty()
        }
    }

    @Test
    fun `duplicate padding cannot squeeze out a legitimate role`() {
        // The input bound counts DISTINCT roles, not raw entries. With the bound applied to raw
        // entries first, MAX_ROLES_INPUT browser rows consumed the entire budget and the trailing
        // dialer was dropped — a hostile sender could suppress any role by padding ahead of it, and
        // the item still reported OK so nothing looked wrong.
        val padding = (1..DefaultRolesApplyProvider.MAX_ROLES_INPUT)
            .joinToString(",") { """{"role":"browser","packageName":"com.example.browser"}""" }
        val (status, candidates) = applied("""{"roles":[$padding,{"role":"dialer","packageName":"com.example.dialer"}]}""")

        assertThat(candidates.map { it.role })
            .containsExactly(RestorableRole.BROWSER, RestorableRole.DIALER)
        assertThat(status).isEqualTo(ItemStatus.OK)
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
    fun `apply does NOT filter on installedness — that decision is the receiver's, made live`() {
        // REGRESSION PIN for the headline case ("restore my defaults after reinstalling my apps").
        //
        // The provider deliberately knows nothing about which packages exist. An apply-time
        // installed check cannot work: Tier-0 APK installs are user-confirmed system dialogs, so
        // ApkApplyProvider.apply returns once the PROMPT is surfaced and the app itself arrives
        // after the Done screen renders. Filtering here dropped exactly the apps this same transfer
        // was installing, and no reordering of the item stream fixes it — the install has not
        // happened yet at any point during the stream.
        //
        // So a candidate for a package that exists nowhere must still be surfaced; the receiver
        // re-filters against a live read when it shows the row, on resume, and at tap time.
        val (status, candidates) = applied(
            """{"roles":[{"role":"browser","packageName":"com.example.nothere"}]}""",
        )
        assertThat(candidates).containsExactly(
            RoleRestoreCandidate(RestorableRole.BROWSER, "com.example.nothere"),
        )
        assertThat(status).isEqualTo(ItemStatus.OK)
    }

    @Test
    fun `a build that cannot restore reports SKIPPED, not OK`() {
        // play ships no bridge, so nothing carried here is restorable. Reporting OK counted the
        // item as MOVED on the Done summary while nothing had moved or could.
        val (status, _) = applied(
            """{"roles":[{"role":"browser","packageName":"com.example.browser"}]}""",
            canRestore = { false },
        )
        assertThat(status).isEqualTo(ItemStatus.SKIPPED)
    }

    @Test
    fun `a canRestore probe that throws is treated as cannot-restore, never propagated`() {
        val (status, _) = applied(
            """{"roles":[{"role":"browser","packageName":"com.example.browser"}]}""",
            canRestore = { error("privilege wiring unavailable") },
        )
        assertThat(status).isEqualTo(ItemStatus.SKIPPED)
    }

    @Test
    fun `an over-length payload is refused, NOT truncated to a parseable prefix`() {
        // Two properties in one fixture, because only this shape can distinguish them.
        //
        // (1) The bound is on the ALLOCATION, not the decoded list: the per-item receive cap is
        //     64 MiB, so a hostile sender can frame an item that large. An earlier version read the
        //     whole stream with readText() and bounded only the resulting list — by then the bytes
        //     were already on the heap.
        //
        // (2) Over-length input is REJECTED rather than truncated. That matters more than it looks:
        //     a prefix of a valid snapshot can itself be valid JSON, so a truncating reader would
        //     silently apply an attacker-chosen SUBSET of a payload portage refused to read whole.
        //
        // THE PADDING MUST BE WHITESPACE. Cut at the ceiling, this fixture leaves
        // `{"roles":[{browser}]}` + spaces — still a complete, parseable document that yields a
        // usable candidate. So a truncating reader SURFACES com.example.browser and reports OK, and
        // this test fails. Padding with non-whitespace (say "x") makes the truncated prefix
        // unparseable, the decode fails for the wrong reason, and the test passes against a
        // truncating implementation — green while proving nothing. That exact mistake was caught
        // here by mutation: `return String(buffer, 0, minOf(filled, maxBytes), …)` must turn this
        // test red, and does.
        val padding = " ".repeat(DefaultRolesCodec.MAX_PAYLOAD_BYTES)
        val oversized =
            """{"roles":[{"role":"browser","packageName":"com.example.browser"}]}$padding"""
        assertThat(oversized.length).isGreaterThan(DefaultRolesCodec.MAX_PAYLOAD_BYTES)

        val (status, candidates) = applied(oversized)
        assertThat(status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(candidates).isEmpty()

        // ...and the same document UNPADDED still applies, so the assertions above are about the
        // LENGTH and not about trailing whitespace tripping the parser.
        val (okStatus, okCandidates) =
            applied("""{"roles":[{"role":"browser","packageName":"com.example.browser"}]}""")
        assertThat(okStatus).isEqualTo(ItemStatus.OK)
        assertThat(okCandidates).hasSize(1)
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
