/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.wire

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.Serializable
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@Serializable
private data class Probe(val name: String, val n: Int = 0)

class JsonLinesTest {

    private fun bytes(vararg lines: String): ByteArrayInputStream =
        ByteArrayInputStream(lines.joinToString("\n").toByteArray(Charsets.UTF_8))

    @Test
    fun `round trips records one per line`() {
        val out = ByteArrayOutputStream()
        JsonLines.writeTo(out, listOf(Probe("a", 1), Probe("b", 2)))

        val text = out.toString(Charsets.UTF_8)
        assertThat(text.trim().lines()).hasSize(2)

        val back = JsonLines.readFrom<Probe>(ByteArrayInputStream(out.toByteArray()))
        assertThat(back.records).containsExactly(Probe("a", 1), Probe("b", 2)).inOrder()
        assertThat(back.malformed).isEqualTo(0)
    }

    @Test
    fun `a malformed line is counted and skipped, not fatal`() {
        val back = JsonLines.readFrom<Probe>(
            bytes("""{"name":"ok","n":1}""", "{not json at all", """{"name":"also ok","n":2}"""),
        )
        assertThat(back.records.map { it.name }).containsExactly("ok", "also ok").inOrder()
        assertThat(back.malformed).isEqualTo(1)
    }

    @Test
    fun `blank lines are ignored`() {
        val back = JsonLines.readFrom<Probe>(bytes("", """{"name":"x"}""", "   ", ""))
        assertThat(back.records).containsExactly(Probe("x"))
        assertThat(back.malformed).isEqualTo(0)
    }

    @Test
    fun `unknown keys are ignored for forward compat`() {
        val back = JsonLines.readFrom<Probe>(bytes("""{"name":"x","n":3,"future_field":true}"""))
        assertThat(back.records).containsExactly(Probe("x", 3))
        assertThat(back.malformed).isEqualTo(0)
    }

    @Test
    fun `a record missing a required field is malformed`() {
        val back = JsonLines.readFrom<Probe>(bytes("""{"n":3}"""))
        assertThat(back.records).isEmpty()
        assertThat(back.malformed).isEqualTo(1)
    }

    @Test
    fun `empty payload yields empty result`() {
        val back = JsonLines.readFrom<Probe>(ByteArrayInputStream(ByteArray(0)))
        assertThat(back.records).isEmpty()
        assertThat(back.malformed).isEqualTo(0)
    }
}
