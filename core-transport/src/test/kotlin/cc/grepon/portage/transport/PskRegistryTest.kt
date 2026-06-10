/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.transport

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PskRegistryTest {

    @Test
    fun `a sid can be consumed exactly once`() {
        val reg = PskRegistry()
        val sid = ByteArray(16) { it.toByte() }
        assertThat(reg.tryConsume(sid)).isTrue()
        // Same VALUE, different array instance — must still be rejected (keyed by content).
        assertThat(reg.tryConsume(sid.copyOf())).isFalse()
    }

    @Test
    fun `distinct sids are independent`() {
        val reg = PskRegistry()
        assertThat(reg.tryConsume(ByteArray(16) { it.toByte() })).isTrue()
        assertThat(reg.tryConsume(ByteArray(16) { (it + 1).toByte() })).isTrue()
    }
}
