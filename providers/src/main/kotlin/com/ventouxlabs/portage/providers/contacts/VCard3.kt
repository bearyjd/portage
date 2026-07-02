/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.contacts

import com.ventouxlabs.portage.providers.text.RfcText
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** Parsed payload: contacts plus how many cards were unusable (per-record resilience). */
data class VCardParseResult(val records: List<ContactRecord>, val malformed: Int)

/**
 * vCard 3.0 (RFC 2426) reader/writer for the `contacts.vcf` item kind. The parser is
 * lenient by design: unknown properties are ignored, a card without FN is counted in
 * [VCardParseResult.malformed] and skipped, and noise outside BEGIN/END never aborts
 * the payload (PROTOCOL.md §5).
 */
object VCard3 {

    private const val CRLF = "\r\n"

    fun write(records: List<ContactRecord>, sink: OutputStream) {
        val writer = sink.bufferedWriter(Charsets.UTF_8)
        for (record in records) {
            writer.write(cardOf(record))
        }
        writer.flush()
    }

    private fun cardOf(record: ContactRecord): String = buildString {
        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        appendLine("FN:${RfcText.escape(record.displayName)}")
        if (record.givenName != null || record.familyName != null) {
            val family = RfcText.escape(record.familyName.orEmpty())
            val given = RfcText.escape(record.givenName.orEmpty())
            appendLine("N:$family;$given;;;")
        }
        record.phones.forEach { appendLine("TEL;TYPE=${it.type}:${RfcText.escape(it.value)}") }
        record.emails.forEach { appendLine("EMAIL;TYPE=${it.type}:${RfcText.escape(it.value)}") }
        record.postals.forEach { appendLine("ADR;TYPE=${it.type}:;;${RfcText.escape(it.value)};;;;") }
        record.organization?.let { appendLine("ORG:${RfcText.escape(it)}") }
        record.title?.let { appendLine("TITLE:${RfcText.escape(it)}") }
        record.nickname?.let { appendLine("NICKNAME:${RfcText.escape(it)}") }
        record.birthday?.let { appendLine("BDAY:${RfcText.escape(it)}") }
        record.websites.forEach {
            val custom = it.customLabel?.let(::encodeCustomLabel)
                ?.let { encoded -> ";X-PORTAGE-LABEL=$encoded" }
                .orEmpty()
            appendLine("URL;TYPE=${it.type}$custom:${RfcText.escape(it.value)}")
        }
        record.note?.let { appendLine("NOTE:${RfcText.escape(it)}") }
        if (record.starred) appendLine("X-PORTAGE-STARRED:1")
        record.photoBase64?.let { appendLine("PHOTO;ENCODING=b;TYPE=${photoType(it)}:$it") }
        appendLine("END:VCARD")
    }

    private fun photoType(base64: String): String =
        runCatching { Base64.getDecoder().decode(base64.take(16)) }
            .getOrNull()
            ?.let { bytes ->
                if (bytes.size >= 4 &&
                    bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte()
                ) "PNG" else "JPEG"
            }
            ?: "JPEG"

    /**
     * Keep the extension bounded in decoded and encoded form. Android does not impose a useful
     * portable label limit, so oversize labels are truncated at Unicode code-point boundaries.
     */
    private fun encodeCustomLabel(label: String): String? {
        if (label.isBlank()) return null
        val bounded = StringBuilder()
        var byteCount = 0
        var codePoints = 0
        var offset = 0
        while (offset < label.length && codePoints < MAX_CUSTOM_LABEL_CODE_POINTS) {
            val codePoint = label.codePointAt(offset)
            val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (byteCount + encoded.size > MAX_CUSTOM_LABEL_BYTES) break
            bounded.appendCodePoint(codePoint)
            byteCount += encoded.size
            codePoints++
            offset += Character.charCount(codePoint)
        }
        return bounded.toString().takeIf(String::isNotBlank)?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
        }
    }

    private const val MAX_CUSTOM_LABEL_CODE_POINTS = 128
    private const val MAX_CUSTOM_LABEL_BYTES = 192
    private const val MAX_CUSTOM_LABEL_ENCODED_CHARS = 256

    private fun StringBuilder.appendLine(line: String) {
        append(RfcText.fold(line))
        append(CRLF)
    }

    fun parse(source: InputStream): VCardParseResult {
        val physical = source.bufferedReader(Charsets.UTF_8).readLines().map { it.trimEnd('\r') }
        val logical = RfcText.unfold(physical)

        val records = mutableListOf<ContactRecord>()
        var malformed = 0
        var card: CardBuilder? = null

        for (line in logical) {
            val upper = line.uppercase()
            when {
                upper == "BEGIN:VCARD" -> card = CardBuilder()
                upper == "END:VCARD" -> {
                    card?.let { finished ->
                        finished.build()?.let { records += it } ?: malformed++
                    }
                    card = null
                }
                else -> card?.acceptProperty(line)
            }
        }
        return VCardParseResult(records, malformed)
    }

    /** Split a compound value on unescaped `;` (component separators survive escaping). */
    private fun splitComponents(value: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length -> {
                    current.append(c).append(value[i + 1])
                    i += 2
                }
                c == ';' -> {
                    parts += current.toString()
                    current.setLength(0)
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        parts += current.toString()
        return parts
    }

    private class CardBuilder {
        var displayName: String? = null
        var givenName: String? = null
        var familyName: String? = null
        val phones = mutableListOf<LabeledValue>()
        val emails = mutableListOf<LabeledValue>()
        val postals = mutableListOf<LabeledValue>()
        var organization: String? = null
        var title: String? = null
        var note: String? = null
        var starred: Boolean = false
        var photoBase64: String? = null
        var nickname: String? = null
        var birthday: String? = null
        val websites = mutableListOf<LabeledValue>()

        fun acceptProperty(line: String) {
            val colon = line.indexOf(':')
            if (colon <= 0) return
            val left = line.substring(0, colon)
            val rawValue = line.substring(colon + 1)
            val nameAndParams = left.split(';')
            val name = nameAndParams[0].uppercase()
            val type = nameAndParams.drop(1)
                .firstOrNull { it.uppercase().startsWith("TYPE=") }
                ?.substringAfter('=')
                ?.substringBefore(',')
                ?.uppercase()
                ?: "OTHER"
            val customLabel = nameAndParams.drop(1)
                .firstOrNull { it.uppercase().startsWith("X-PORTAGE-LABEL=") }
                ?.substringAfter('=')
                ?.let(::decodeCustomLabel)

            when (name) {
                "FN" -> displayName = RfcText.unescape(rawValue)
                "N" -> {
                    val parts = splitComponents(rawValue)
                    familyName = parts.getOrNull(0)?.let(RfcText::unescape)?.ifEmpty { null }
                    givenName = parts.getOrNull(1)?.let(RfcText::unescape)?.ifEmpty { null }
                }
                "TEL" -> phones += LabeledValue(RfcText.unescape(rawValue), type)
                "EMAIL" -> emails += LabeledValue(RfcText.unescape(rawValue), type)
                "ADR" -> {
                    val joined = splitComponents(rawValue)
                        .map(RfcText::unescape)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    if (joined.isNotBlank()) postals += LabeledValue(joined, type)
                }
                "ORG" -> organization = RfcText.unescape(rawValue).ifEmpty { null }
                "TITLE" -> title = RfcText.unescape(rawValue).ifEmpty { null }
                "NICKNAME" -> nickname = RfcText.unescape(rawValue).ifEmpty { null }
                "BDAY" -> birthday = RfcText.unescape(rawValue).ifEmpty { null }
                "URL" -> websites += LabeledValue(RfcText.unescape(rawValue), type, customLabel)
                "NOTE" -> note = RfcText.unescape(rawValue).ifEmpty { null }
                "X-PORTAGE-STARRED" -> starred = rawValue.trim() == "1"
                "PHOTO" -> photoBase64 = validatedPhoto(rawValue)
                else -> Unit // unknown property — ignore (forward compat)
            }
        }

        private fun decodeCustomLabel(encoded: String): String? {
            if (encoded.length > MAX_CUSTOM_LABEL_ENCODED_CHARS) return null
            return runCatching {
                val bytes = Base64.getUrlDecoder().decode(encoded)
                if (bytes.size > MAX_CUSTOM_LABEL_BYTES) return null
                String(bytes, Charsets.UTF_8).takeIf {
                    it.isNotBlank() &&
                        it.codePointCount(0, it.length) <= MAX_CUSTOM_LABEL_CODE_POINTS
                }
            }.getOrNull()
        }

        private fun validatedPhoto(value: String): String? {
            // Reject on encoded length before decoding so hostile cards cannot force a large allocation.
            val maxEncodedLength = ((MAX_CONTACT_PHOTO_BYTES + 2) / 3) * 4
            if (value.length > maxEncodedLength) return null
            val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
            return value.takeIf { bytes.size <= MAX_CONTACT_PHOTO_BYTES }
        }

        /** A card is usable only with a non-blank FN; anything else is malformed. */
        fun build(): ContactRecord? {
            val fn = displayName?.takeIf { it.isNotBlank() } ?: return null
            return ContactRecord(
                displayName = fn,
                givenName = givenName,
                familyName = familyName,
                phones = phones.toList(),
                emails = emails.toList(),
                postals = postals.toList(),
                organization = organization,
                title = title,
                note = note,
                starred = starred,
                photoBase64 = photoBase64,
                nickname = nickname,
                birthday = birthday,
                websites = websites.toList(),
            )
        }
    }
}
