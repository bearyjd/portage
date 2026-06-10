/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC. Licensed under AGPL-3.0.
 */
package cc.grepon.portage.settings

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
