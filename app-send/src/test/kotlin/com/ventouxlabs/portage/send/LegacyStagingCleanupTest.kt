/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyStagingCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `upgrade deletes legacy cache payloads and preserves retained lineage and unrelated cache`() = runTest {
        val cache = tmp.newFolder("cache")
        val noBackup = tmp.newFolder("no-backup")
        val legacy = File(cache, "portage-staging").apply { mkdirs() }
        File(legacy, "item-1-contacts.bin").writeText("old plaintext")
        val retained = File(noBackup, "portage-staging/lineage/staging").apply { mkdirs() }
        val saved = File(retained, "saved.bin").apply { writeText("retained") }
        val unrelated = File(cache, "other.bin").apply { writeText("other") }
        Files.createSymbolicLink(File(legacy, "linked-retained").toPath(), retained.toPath())
        cleanupLegacyStaging(cache, noBackup)
        assertThat(legacy.exists()).isFalse()
        assertThat(saved.readText()).isEqualTo("retained")
        assertThat(unrelated.readText()).isEqualTo("other")
        cleanupLegacyStaging(cache, noBackup)
        assertThat(saved.exists()).isTrue()
    }
}
