/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.ventouxlabs.portage.transport

import com.google.common.truth.Truth.assertThat
import com.ventouxlabs.portage.model.PairingMode
import com.ventouxlabs.portage.model.PairingPayload
import org.junit.Test
import java.util.Base64

class PairingCodecV6Test {
    private val codec = PairingCodecImpl()
    private fun payload(mode: PairingMode = PairingMode.NEW) = PairingPayload(
        psk = ByteArray(32) { it.toByte() }, sid = ByteArray(16) { it.toByte() },
        ip = listOf("127.0.0.1"), port = 54321, expiresAtEpochSeconds = 1120, mode = mode,
    )

    @Test
    fun `NEW and RESUME QR roundtrip modes without stored lineage or resume credential`() {
        for (mode in PairingMode.entries) {
            val original = payload(mode)
            val qr = codec.encode(original)
            assertThat(codec.decode(qr, 1000).getOrThrow()).isEqualTo(original)
            val body = Base64.getUrlDecoder().decode(qr.removePrefix(PairingPayload.SCHEME))
            val fieldNames = body.toString(Charsets.ISO_8859_1)
            assertThat(fieldNames).doesNotContain("lineageId")
            assertThat(fieldNames).doesNotContain("resumeCredential")
            assertThat(original.psk).isEqualTo(ByteArray(32) { it.toByte() })
        }
    }

    @Test
    fun `v5 QR is rejected before creating a transport session`() {
        val qr = codec.encode(payload().copy(version = 5))
        val result = codec.decode(qr, 1000)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("unsupported protocol version 5")
    }

    @Test
    fun `expired v6 QR remains invalid for both pairing modes`() {
        for (mode in PairingMode.entries) {
            assertThat(codec.decode(codec.encode(payload(mode)), 1121).isFailure).isTrue()
        }
    }
}
