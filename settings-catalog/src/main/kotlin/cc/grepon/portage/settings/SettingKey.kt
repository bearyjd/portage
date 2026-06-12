/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.settings

/** Settings provider table. */
enum class Namespace { SYSTEM, SECURE, GLOBAL }

/**
 * Safety classification. Source of truth is docs/prp/settings_allowlist.md — this enum
 * mirrors it. ONLY [SAFE] keys are in the default sync set.
 */
enum class Classification { SAFE, RISKY, DEVICE_SPECIFIC }

/** How the receiver actually writes the key (ADR-001 §1 reach table). */
enum class Reach {
    /** Settings.System via user-granted "Modify system settings" — no privilege bridge (Tier 0). */
    T0_SYSTEM,

    /** Settings.Secure/Global after the one-shot WRITE_SECURE_SETTINGS grant. */
    T1_GRANT,

    /** Needs a LIVE shell-uid bridge (role/overlay/pm) at call time (AdbBridge, ADR-003). */
    T1_SHELL,

    /** Excluded — not written by portage. */
    NA,
}

/**
 * Receiver-side validation applied to EVERY value before apply, regardless of class.
 * [None] is reserved for DEVICE_SPECIFIC (excluded) keys only — a SAFE/RISKY key with
 * [None] is a bug, and `SettingsAllowlistTest` enforces that invariant.
 */
sealed interface Validator {
    data object None : Validator
    data class IntRange(val min: Int, val max: Int) : Validator
    data class FloatRange(val min: Float, val max: Float) : Validator
    data class IntEnum(val allowed: Set<Int>) : Validator
    data class StringEnum(val allowed: Set<String>) : Validator

    /** Regex (as a pattern string for stable equality) the value must fully match. */
    data class StringPattern(val pattern: String) : Validator
}

data class SettingKey(
    val name: String,
    val namespace: Namespace,
    val classification: Classification,
    val reach: Reach,
    val reason: String,
    val validator: Validator = Validator.None,
)
