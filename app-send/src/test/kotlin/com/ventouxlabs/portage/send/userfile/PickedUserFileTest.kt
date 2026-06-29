/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.userfile

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.model.ItemKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PickedUserFileTest {
    @Test
    fun `picked file maps to streaming USER_FILE provider`() = runTest {
        val bytes = "hello".toByteArray()
        val provider = userFileExportProviders(
            listOf(
                PickedUserFile(
                    pickId = 1,
                    displayName = "hello.txt",
                    mimeType = "text/plain",
                    byteLength = bytes.size.toLong(),
                    openStream = { ByteArrayInputStream(bytes) },
                ),
            ),
        ).single()

        val sink = ByteArrayOutputStream()
        provider.exportTo(sink)

        assertThat(provider.kind).isEqualTo(ItemKind.USER_FILE)
        assertThat(provider.displayName).isEqualTo("hello.txt")
        assertThat(sink.toByteArray().takeLast(bytes.size).toByteArray()).isEqualTo(bytes)
    }
}
