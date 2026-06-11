/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send.transfer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

class Sha256Test {

    @Test
    fun `hashes the empty stream to the well-known digest`() {
        assertThat(sha256Hex(ByteArrayInputStream(ByteArray(0))))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `hashes abc to the RFC test vector`() {
        assertThat(sha256Hex(ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8))))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `streams content larger than one buffer`() {
        val big = ByteArray(100_000) { (it % 251).toByte() }
        val once = sha256Hex(ByteArrayInputStream(big))
        val twice = sha256Hex(ByteArrayInputStream(big))
        assertThat(once).isEqualTo(twice)
        assertThat(once).hasLength(64)
    }
}
