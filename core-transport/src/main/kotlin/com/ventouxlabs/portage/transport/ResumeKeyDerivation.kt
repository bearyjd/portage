/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.ventouxlabs.portage.transport

import com.ventouxlabs.portage.model.PairingMode
import com.ventouxlabs.portage.model.PairingPayload
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol v6 resume key schedule, using RFC 5869 HKDF-SHA256:
 * `PRK = HMAC-SHA256(qrPsk, resumeCredential)`;
 * `PSK = HMAC-SHA256(PRK, info || 0x01)` (32 output bytes).
 *
 * Context encoding is the concatenation of `u16be(byteLength) || bytes` for EACH field,
 * in order: ASCII domain, one-byte version, ASCII mode name, raw 16-byte SID,
 * ASCII `recv->send`, raw 16-byte lineage (decoded from canonical lowercase hex).
 * KDF domain is `portage-resume-psk`; prologue domain is `portage-noise-prologue`.
 * NEW has a zero-length final lineage field. Neither secret appears in either context.
 *
 * Inputs remain caller-owned. Internal secret arrays are wiped on all exits; callers
 * MUST wipe the returned derived PSK. JCA providers may retain internal key copies until GC.
 */
object ResumeKeyDerivation {
    const val CREDENTIAL_BYTES = 32

    fun derive(
        qrPsk: ByteArray,
        resumeCredential: ByteArray,
        version: Int,
        sid: ByteArray,
        lineageId: String,
    ): ByteArray {
        require(qrPsk.size == PairingPayload.PSK_BYTES) { "QR PSK must be 32 bytes" }
        require(resumeCredential.size == CREDENTIAL_BYTES) { "resume credential must be 32 bytes" }
        val info = context("portage-resume-psk", version, PairingMode.RESUME, sid, lineageId)
        return try {
            HkdfSha256.derive(qrPsk, resumeCredential, info, PairingPayload.PSK_BYTES)
        } finally {
            info.fill(0)
        }
    }

    internal fun context(
        domain: String,
        version: Int,
        mode: PairingMode,
        sid: ByteArray,
        lineageId: String?,
    ): ByteArray {
        require(version in 0..255) { "version out of single-byte prologue range" }
        require(sid.size == PairingPayload.SID_BYTES) { "sid must be 16 bytes" }
        val lineage = when (mode) {
            PairingMode.NEW -> {
                require(lineageId == null) { "NEW handshake cannot supply stored lineage" }
                ByteArray(0)
            }
            PairingMode.RESUME -> {
                require(lineageId != null && lineageId.length == 32 &&
                    lineageId.all { it in '0'..'9' || it in 'a'..'f' }) {
                    "RESUME requires a canonical 32-character lowercase hex lineage"
                }
                ByteArray(16) { index -> lineageId.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
            }
        }
        val fields = listOf(
            domain.toByteArray(Charsets.US_ASCII), byteArrayOf(version.toByte()),
            mode.name.toByteArray(Charsets.US_ASCII), sid,
            "recv->send".toByteArray(Charsets.US_ASCII), lineage,
        )
        return try {
            ByteArray(fields.sumOf { 2 + it.size }).also { output ->
                var offset = 0
                for (field in fields) {
                    require(field.size <= 65535) { "context field exceeds u16 length" }
                    output[offset++] = (field.size ushr 8).toByte()
                    output[offset++] = field.size.toByte()
                    field.copyInto(output, offset)
                    offset += field.size
                }
            }
        } finally {
            lineage.fill(0)
        }
    }
}

/** RFC 5869 extract/expand, kept internal so only the fixed v6 key schedule is public. */
internal object HkdfSha256 {
    private const val HASH_BYTES = 32

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_BYTES) { "HKDF output length out of range" }
        val mac = Mac.getInstance("HmacSHA256")
        val saltCopy = if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt.copyOf()
        var inputCopy: ByteArray? = null
        var prk: ByteArray? = null
        var block = ByteArray(0)
        var output: ByteArray? = null
        var returned = false
        try {
            inputCopy = ikm.copyOf()
            mac.init(SecretKeySpec(saltCopy, "HmacSHA256"))
            prk = mac.doFinal(inputCopy)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val result = ByteArray(length)
            output = result
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.update(block)
                mac.update(info)
                mac.update(counter.toByte())
                val next = mac.doFinal()
                block.fill(0)
                block = next
                val count = minOf(HASH_BYTES, length - offset)
                block.copyInto(result, offset, 0, count)
                offset += count
                counter++
            }
            returned = true
            return result
        } finally {
            saltCopy.fill(0)
            inputCopy?.fill(0)
            prk?.fill(0)
            block.fill(0)
            if (!returned) output?.fill(0)
            // Drop the provider's current key schedule where its implementation permits it.
            runCatching { mac.init(SecretKeySpec(ByteArray(HASH_BYTES), "HmacSHA256")) }
        }
    }
}
