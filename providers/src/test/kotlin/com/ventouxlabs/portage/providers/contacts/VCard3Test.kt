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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class VCard3Test {

    private fun roundTrip(records: List<ContactRecord>): VCardParseResult {
        val out = ByteArrayOutputStream()
        VCard3.write(records, out)
        return VCard3.parse(ByteArrayInputStream(out.toByteArray()))
    }

    private fun parse(text: String): VCardParseResult =
        VCard3.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    @Test
    fun `round trips a fully populated contact`() {
        val record = ContactRecord(
            displayName = "Ada Lovelace",
            givenName = "Ada",
            familyName = "Lovelace",
            phones = listOf(LabeledValue("+15551234567", "CELL"), LabeledValue("+15559876543", "WORK")),
            emails = listOf(LabeledValue("ada@example.org", "HOME")),
            postals = listOf(LabeledValue("12 Analytical Way, London", "HOME")),
            organization = "Analytical Engines Ltd",
            title = "Mathematician",
            note = "First programmer",
            nickname = "Enchantress of Numbers",
            birthday = "1815-12-10",
            websites = listOf(LabeledValue("https://example.org/ada", "WORK")),
            groupNames = listOf("Friends", "Analytical Society"),
        )

        val back = roundTrip(listOf(record))

        assertThat(back.malformed).isEqualTo(0)
        assertThat(back.records).containsExactly(record)
    }

    @Test
    fun `round trips special characters in values`() {
        val record = ContactRecord(
            displayName = "Smith; Jones, \\ & Co.\nLine two",
            note = "semi;colon, comma\\backslash",
        )

        val back = roundTrip(listOf(record))

        assertThat(back.records).containsExactly(record)
    }

    @Test
    fun `round trips favorite starred state through portage extension`() {
        val record = ContactRecord(displayName = "Favorite", starred = true)

        val out = ByteArrayOutputStream()
        VCard3.write(listOf(record), out)
        val text = out.toString(Charsets.UTF_8)
        val back = VCard3.parse(ByteArrayInputStream(out.toByteArray()))

        assertThat(text).contains("X-PORTAGE-STARRED:1\r\n")
        assertThat(back.records).containsExactly(record)
    }

    @Test
    fun `round trips a bounded contact photo`() {
        val photo = Base64.getEncoder().encodeToString(
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte()),
        )
        val record = ContactRecord(displayName = "Portrait", photoBase64 = photo)

        val out = ByteArrayOutputStream()
        VCard3.write(listOf(record), out)
        val back = VCard3.parse(ByteArrayInputStream(out.toByteArray()))

        assertThat(out.toString(Charsets.UTF_8)).contains("PHOTO;ENCODING=b;TYPE=JPEG:")
        assertThat(back.records).containsExactly(record)
    }

    @Test
    fun `oversize and malformed photos are ignored without dropping the contact`() {
        val oversize = Base64.getEncoder().encodeToString(ByteArray(MAX_CONTACT_PHOTO_BYTES + 1))
        val back = parse(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Oversize\r\n" +
                "PHOTO;ENCODING=b:$oversize\r\nEND:VCARD\r\n" +
                "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Malformed\r\n" +
                "PHOTO;ENCODING=b:not-base64!\r\nEND:VCARD\r\n",
        )

        assertThat(back.records).hasSize(2)
        assertThat(back.records.map { it.photoBase64 }).containsExactly(null, null)
    }

    @Test
    fun `round trips a yearless birthday and multiple typed websites`() {
        val record = ContactRecord(
            displayName = "Details",
            nickname = "D",
            birthday = "--02-29",
            websites = listOf(
                LabeledValue("https://home.example", "HOME"),
                LabeledValue("https://work.example", "WORK"),
            ),
        )

        assertThat(roundTrip(listOf(record)).records).containsExactly(record)
    }

    @Test
    fun `round trips every Android website type and a custom label`() {
        val types = listOf("HOMEPAGE", "BLOG", "PROFILE", "HOME", "WORK", "FTP", "OTHER")
        val websites = types.map { LabeledValue("https://${it.lowercase()}.example", it) } +
            LabeledValue("https://custom.example", "CUSTOM", "Family portal; private")
        val record = ContactRecord(displayName = "All websites", websites = websites)

        val out = ByteArrayOutputStream()
        VCard3.write(listOf(record), out)
        val back = VCard3.parse(ByteArrayInputStream(out.toByteArray()))

        assertThat(out.toString(Charsets.UTF_8)).contains("X-PORTAGE-LABEL=")
        assertThat(back.records).containsExactly(record)
    }

    @Test
    fun `oversize custom website labels are bounded before serialization`() {
        val original = "😀".repeat(100) + "x".repeat(200)
        val record = ContactRecord(
            displayName = "Bounded label",
            websites = listOf(LabeledValue("https://custom.example", "CUSTOM", original)),
        )

        val out = ByteArrayOutputStream()
        VCard3.write(listOf(record), out)
        val back = VCard3.parse(ByteArrayInputStream(out.toByteArray())).records.single()
        val restored = back.websites.single().customLabel!!

        assertThat(restored).isEqualTo("😀".repeat(48))
        assertThat(restored.codePointCount(0, restored.length)).isAtMost(128)
        assertThat(restored.toByteArray(Charsets.UTF_8).size).isAtMost(192)
    }

    @Test
    fun `round trips repeated group categories with escaped punctuation`() {
        val record = ContactRecord(
            displayName = "Grouped",
            groupNames = listOf("Friends, close", "Work; London"),
        )

        val out = ByteArrayOutputStream()
        VCard3.write(listOf(record), out)
        val back = VCard3.parse(ByteArrayInputStream(out.toByteArray()))

        assertThat(out.toString(Charsets.UTF_8)).contains("CATEGORIES:Friends\\, close")
        assertThat(back.records).containsExactly(record)
    }

    @Test
    fun `parses standard comma-separated categories without splitting escaped commas`() {
        val back = parse(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Grouped\r\n" +
                "CATEGORIES:Friends,Work,Neighbors\\, close\r\nEND:VCARD\r\n",
        )

        assertThat(back.records.single().groupNames)
            .containsExactly("Friends", "Work", "Neighbors, close")
            .inOrder()
    }

    @Test
    fun `writes version 3 with CRLF line endings`() {
        val out = ByteArrayOutputStream()
        VCard3.write(listOf(ContactRecord(displayName = "X")), out)
        val text = out.toString(Charsets.UTF_8)

        assertThat(text).startsWith("BEGIN:VCARD\r\n")
        assertThat(text).contains("VERSION:3.0\r\n")
        assertThat(text).endsWith("END:VCARD\r\n")
    }

    @Test
    fun `a card without FN is counted malformed and does not abort the rest`() {
        val back = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            TEL;TYPE=CELL:+15550000000
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Survivor
            END:VCARD
            """.trimIndent(),
        )

        assertThat(back.malformed).isEqualTo(1)
        assertThat(back.records.map { it.displayName }).containsExactly("Survivor")
    }

    @Test
    fun `unknown properties and noise between cards are ignored`() {
        val back = parse(
            """
            X-GARBAGE:outside any card
            BEGIN:VCARD
            VERSION:3.0
            FN:Keeper
            PHOTO;ENCODING=b:AAAA
            X-CUSTOM;FOO=bar:whatever
            END:VCARD
            trailing noise
            """.trimIndent(),
        )

        assertThat(back.malformed).isEqualTo(0)
        assertThat(back.records.map { it.displayName }).containsExactly("Keeper")
    }

    @Test
    fun `non-starred contacts omit the portage starred extension`() {
        val out = ByteArrayOutputStream()
        VCard3.write(listOf(ContactRecord(displayName = "Plain")), out)

        assertThat(out.toString(Charsets.UTF_8)).doesNotContain("X-PORTAGE-STARRED")
    }

    @Test
    fun `folded lines are unfolded before parsing`() {
        val back = parse(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Long\r\n Name\r\nNOTE:part one\r\n  and part two\r\nEND:VCARD\r\n",
        )

        assertThat(back.records).hasSize(1)
        assertThat(back.records[0].displayName).isEqualTo("LongName")
        assertThat(back.records[0].note).isEqualTo("part one and part two")
    }

    @Test
    fun `TYPE parameter defaults to OTHER when absent and uppercases when present`() {
        val back = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Typed
            TEL:+15551112222
            EMAIL;TYPE=work:t@w.example
            END:VCARD
            """.trimIndent(),
        )

        val record = back.records.single()
        assertThat(record.phones).containsExactly(LabeledValue("+15551112222", "OTHER"))
        assertThat(record.emails).containsExactly(LabeledValue("t@w.example", "WORK"))
    }

    @Test
    fun `long properties are folded on write and survive the round trip`() {
        val longNote = "n".repeat(300)
        val record = ContactRecord(displayName = "Folded", note = longNote)

        val out = ByteArrayOutputStream()
        VCard3.write(listOf(record), out)
        val text = out.toString(Charsets.UTF_8)
        text.split("\r\n").forEach {
            assertThat(it.toByteArray(Charsets.UTF_8).size).isAtMost(75)
        }

        val back = VCard3.parse(ByteArrayInputStream(out.toByteArray()))
        assertThat(back.records.single().note).isEqualTo(longNote)
    }

    @Test
    fun `empty input parses to an empty result`() {
        val back = parse("")
        assertThat(back.records).isEmpty()
        assertThat(back.malformed).isEqualTo(0)
    }

    @Test
    fun `N components with escaped semicolons survive the component split`() {
        val back = roundTrip(
            listOf(ContactRecord(displayName = "D", givenName = "Gi;ven", familyName = "Fam;ily")),
        )
        val record = back.records.single()
        assertThat(record.givenName).isEqualTo("Gi;ven")
        assertThat(record.familyName).isEqualTo("Fam;ily")
    }
}
