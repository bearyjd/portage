/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.sms

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SmsRoleLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ledger(name: String = "sms-role.ledger") = SmsRoleLedger(File(tmp.root, name))

    @Test
    fun `arm records the prior holder, disarm clears it`() {
        val l = ledger()
        assertThat(l.isArmed()).isFalse()
        assertThat(l.prior()).isNull()

        l.arm("org.fossify.messages")
        assertThat(l.isArmed()).isTrue()
        assertThat(l.prior()).isEqualTo("org.fossify.messages")

        l.disarm()
        assertThat(l.isArmed()).isFalse()
        assertThat(l.prior()).isNull()
    }

    @Test
    fun `arming with a null prior is still armed but has no restore target`() {
        val l = ledger()
        l.arm(null)
        assertThat(l.isArmed()).isTrue() // a handoff is outstanding…
        assertThat(l.prior()).isNull() // …but the restore must fall back to system settings
    }

    @Test
    fun `the marker survives a fresh ledger over the same file (process-death analogue)`() {
        val file = File(tmp.root, "sms-role.ledger")
        SmsRoleLedger(file).arm("com.example.sms")

        // A new process re-opens the same on-disk marker — the whole point of the safety net.
        assertThat(SmsRoleLedger(file).prior()).isEqualTo("com.example.sms")
        assertThat(SmsRoleLedger(file).isArmed()).isTrue()
    }

    @Test
    fun `disarm is idempotent when nothing is armed`() {
        val l = ledger()
        l.disarm()
        l.disarm()
        assertThat(l.isArmed()).isFalse()
    }
}
