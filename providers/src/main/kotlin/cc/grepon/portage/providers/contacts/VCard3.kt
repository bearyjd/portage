/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.contacts

import cc.grepon.portage.providers.text.RfcText
import java.io.InputStream
import java.io.OutputStream

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
        record.note?.let { appendLine("NOTE:${RfcText.escape(it)}") }
        appendLine("END:VCARD")
    }

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
                "NOTE" -> note = RfcText.unescape(rawValue).ifEmpty { null }
                else -> Unit // unknown property — ignore (forward compat)
            }
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
            )
        }
    }
}
