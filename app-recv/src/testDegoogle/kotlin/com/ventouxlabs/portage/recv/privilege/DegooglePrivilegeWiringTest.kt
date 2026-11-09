/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.privilege

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Regression belt for the confused-deputy guard's degoogle wiring. [DegooglePrivilegeIntegration.wiring]
 * needs an Android Context (the JVM unit toolchain has no Robolectric/mocking lib), so the wiring is
 * pinned at the SOURCE level: wiring the fail-closed `ApkArchivePackageName.None` here would turn every
 * silent install into a refusal/Tier-0 downgrade without failing any behavioural unit test. The
 * behavioural pass-through is covered by ApplyRegistrationCompletenessTest.
 */
class DegooglePrivilegeWiringTest {

    private val source: String by lazy {
        val rel = "src/degoogle/kotlin/com/ventouxlabs/portage/recv/privilege/DegooglePrivilegeIntegration.kt"
        val file = listOf(File(rel), File("app-recv/$rel")).first { it.exists() }
        stripComments(file.readText())
    }

    /** A commented-out wiring line must not satisfy the match. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `degoogle wires the real archive package reader`() {
        assertThat(source).containsMatch("""(?m)^\s*archivePackageName\s*=\s*androidArchivePackageName\(context\),""")
    }

    @Test
    fun `degoogle never wires the fail-closed None archive seam`() {
        assertThat(source).doesNotContain("ApkArchivePackageName.None")
    }
}
