/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.userfile

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.model.ItemStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UserFileProvidersTest {
    @Test
    fun `opaque file round trips without buffering semantics`() = runTest {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val header = UserFileHeader("photo.jpg", "image/jpeg", bytes.size.toLong())
        val payload = ByteArrayOutputStream()
        UserFileExportProvider(header) { ByteArrayInputStream(bytes) }.exportTo(payload)
        var received = ByteArray(0)

        val outcome = UserFileApplyProvider(
            writeFile = { decoded, source ->
                val sink = ByteArrayOutputStream()
                val count = UserFileCodec.stream(source, sink, decoded.byteLength)
                received = sink.toByteArray()
                count == decoded.byteLength
            },
        ).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(received).isEqualTo(bytes)
    }

    @Test
    fun `header strips path separators and control characters`() {
        val safe = UserFileHeader("../bad\\name\n.jpg", "IMAGE/JPEG", 1).sanitizedOrNull()

        assertThat(safe?.displayName).isEqualTo("..badname.jpg")
        assertThat(safe?.mimeType).isEqualTo("image/jpeg")
    }

    @Test
    fun `invalid mime and oversized files are rejected while empty files are valid`() = runTest {
        assertThat(UserFileHeader("x", "not-a-mime", 1).sanitizedOrNull()).isNull()
        assertThat(UserFileHeader("x", "text/plain", 0).sanitizedOrNull()).isNotNull()
        assertThat(
            UserFileExportProvider(
                UserFileHeader("x", "text/plain", UserFileHeader.MAX_PAYLOAD_BYTES + 1),
            ) {
                ByteArrayInputStream(ByteArray(0))
            }.available(),
        ).isFalse()
    }

    @Test
    fun `truncated payload is a write error`() = runTest {
        val payload = ByteArrayOutputStream().also {
            UserFileCodec.writeHeader(it, UserFileHeader("x.txt", "text/plain", 10))
            it.write(byteArrayOf(1, 2, 3))
        }

        val outcome = UserFileApplyProvider(
            writeFile = { header, source ->
                UserFileCodec.stream(source, ByteArrayOutputStream(), header.byteLength) ==
                    header.byteLength
            },
        ).apply(ByteArrayInputStream(payload.toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
    }
}
