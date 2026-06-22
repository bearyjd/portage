/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Safety-critical guardrails (docs/prp/DEVILS_ADVOCATE.md Q2). If any of these fail, a
 * DEVICE_SPECIFIC key could degrade or brick the new phone's UX.
 */
class SettingsAllowlistTest {

    @Test
    fun `default sync set contains only SAFE keys`() {
        val nonSafe = SettingsAllowlist.defaultSyncSet.filter { it.classification != Classification.SAFE }
        assertThat(nonSafe).isEmpty()
    }

    @Test
    fun `excluded keys are never reachable for writing`() {
        val leaks = SettingsAllowlist.all
            .filter { it.classification == Classification.DEVICE_SPECIFIC }
            .filter { it.reach != Reach.NA }
        assertThat(leaks).isEmpty()
    }

    @Test
    fun `known trap keys are not classified SAFE`() {
        val traps = setOf(
            "screen_brightness", "ringtone", "enabled_accessibility_services",
            "default_input_method", "adb_enabled",
        )
        val misclassified = SettingsAllowlist.all
            .filter { it.name in traps && it.classification == Classification.SAFE }
        assertThat(misclassified).isEmpty()
    }

    @Test
    fun `key names are globally unique across the table`() {
        // The receiver routes a write to its seam by looking the key up by NAME alone
        // (SettingsAllowlist.byName) — the wire never carries a namespace. A duplicate name
        // would make that lookup ambiguous and could send a value to the wrong provider table.
        val duplicates = SettingsAllowlist.all
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys
        assertThat(duplicates).isEmpty()
    }

    @Test
    fun `reach agrees with namespace — T0_SYSTEM is SYSTEM, T1_GRANT is SECURE or GLOBAL`() {
        // The apply router picks the store from reach and the namespace from the matched key; if
        // the two disagree a Secure write could land on the System store (or vice versa). This
        // pins the correspondence the router depends on for the two writable tiers.
        val mismatched = SettingsAllowlist.all.filter { key ->
            when (key.reach) {
                Reach.T0_SYSTEM -> key.namespace != Namespace.SYSTEM
                Reach.T1_GRANT -> key.namespace !in setOf(Namespace.SECURE, Namespace.GLOBAL)
                Reach.T1_SHELL, Reach.NA -> false
            }
        }
        assertThat(mismatched).isEmpty()
    }

    @Test
    fun `device_name is present and classified GLOBAL SAFE via the one-shot grant`() {
        // The user-chosen device/Bluetooth display name is a portable preference, not
        // hardware-bound, so it rides the default SAFE cut over the existing T1_GRANT path.
        val key = SettingsAllowlist.byName("device_name")
        assertThat(key).isNotNull()
        assertThat(key?.namespace).isEqualTo(Namespace.GLOBAL)
        assertThat(key?.classification).isEqualTo(Classification.SAFE)
        assertThat(key?.reach).isEqualTo(Reach.T1_GRANT)
        // Being SAFE, it must be in the default sync set.
        assertThat(SettingsAllowlist.defaultSyncSet).contains(key)
    }

    @Test
    fun `every applied key has a concrete validator (None is reserved for excluded keys)`() {
        // The documented invariant: every value applied to the device is validated. A
        // SAFE/RISKY key with Validator.None is a bug (e.g. the volume_alarm 0-hazard).
        val unvalidated = SettingsAllowlist.all
            .filter { it.classification != Classification.DEVICE_SPECIFIC }
            .filter { it.validator == Validator.None }
            .map { it.name }
        assertThat(unvalidated).isEmpty()
    }
}
