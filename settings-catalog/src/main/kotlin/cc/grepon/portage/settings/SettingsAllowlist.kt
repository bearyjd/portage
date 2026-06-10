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

import cc.grepon.portage.settings.Classification.DEVICE_SPECIFIC
import cc.grepon.portage.settings.Classification.RISKY
import cc.grepon.portage.settings.Classification.SAFE
import cc.grepon.portage.settings.Namespace.GLOBAL
import cc.grepon.portage.settings.Namespace.SECURE
import cc.grepon.portage.settings.Namespace.SYSTEM
import cc.grepon.portage.settings.Reach.NA
import cc.grepon.portage.settings.Reach.T0_SYSTEM
import cc.grepon.portage.settings.Reach.T1_GRANT
import cc.grepon.portage.settings.Reach.T1_SHELL

/**
 * The compiled allowlist. The receiver applies a key ONLY if it is present here — the
 * sender's manifest can never introduce keys (docs/prp/THREAT_MODEL.md, malicious-sender).
 *
 * This is a representative seed, NOT exhaustive. docs/prp/settings_allowlist.md is the
 * source of truth; complete this table against it. DEVICE_SPECIFIC entries are listed
 * deliberately so the "looks SAFE but isn't" traps are encoded, not merely omitted.
 */
object SettingsAllowlist {

    val all: List<SettingKey> = listOf(
        // --- Display / UI ---
        SettingKey("font_scale", SYSTEM, SAFE, T0_SYSTEM,
            "Accessibility-relevant cosmetic.", Validator.FloatRange(0.85f, 1.30f)),
        SettingKey("screen_off_timeout", SYSTEM, SAFE, T0_SYSTEM,
            "Behavioral.", Validator.IntRange(15_000, 1_800_000)),
        SettingKey("screen_brightness_mode", SYSTEM, SAFE, T0_SYSTEM,
            "Auto vs manual; behavioral.", Validator.IntEnum(setOf(0, 1))),
        SettingKey("screen_brightness", SYSTEM, DEVICE_SPECIFIC, NA,
            "TRAP: panel-relative backlight scale; same int = different nits per model."),
        SettingKey("accelerometer_rotation", SYSTEM, SAFE, T0_SYSTEM,
            "Auto-rotate on/off.", Validator.IntEnum(setOf(0, 1))),
        SettingKey("ui_night_mode", SECURE, SAFE, T1_GRANT,
            "Dark theme preference.", Validator.IntEnum(setOf(0, 1, 2))),

        // --- Sound / haptics ---
        SettingKey("haptic_feedback_enabled", SYSTEM, SAFE, T0_SYSTEM,
            "Behavioral.", Validator.IntEnum(setOf(0, 1))),
        SettingKey("sound_effects_enabled", SYSTEM, SAFE, T0_SYSTEM,
            "Cosmetic UI sounds.", Validator.IntEnum(setOf(0, 1))),
        SettingKey("vibrate_when_ringing", SYSTEM, SAFE, T0_SYSTEM,
            "Behavioral.", Validator.IntEnum(setOf(0, 1))),
        SettingKey("volume_alarm", SYSTEM, RISKY, T0_SYSTEM,
            "TRAP: a copied alarm volume of 0 is a missed-alarm hazard. Opt-in."),
        SettingKey("ringtone", SYSTEM, DEVICE_SPECIFIC, NA,
            "TRAP: content URI to on-device media absent on the new phone → silent/crash."),

        // --- Accessibility ---
        SettingKey("accessibility_display_daltonizer_enabled", SECURE, SAFE, T1_GRANT,
            "Color-correction toggle.", Validator.IntEnum(setOf(0, 1))),
        SettingKey("high_text_contrast_enabled", SECURE, SAFE, T1_GRANT,
            "Cosmetic a11y.", Validator.IntEnum(setOf(0, 1))),
        SettingKey("enabled_accessibility_services", SECURE, DEVICE_SPECIFIC, NA,
            "TRAP: re-grants powerful a11y access to possibly-absent service components."),

        // --- Input / locale ---
        SettingKey("time_12_24", SYSTEM, SAFE, T0_SYSTEM,
            "12/24h format.", Validator.IntEnum(setOf(12, 24))),
        SettingKey("default_input_method", SECURE, RISKY, T1_GRANT,
            "TRAP: IME component; only valid if that keyboard is installed. Apply post-install."),

        // --- Animation ---
        SettingKey("window_animation_scale", GLOBAL, SAFE, T1_GRANT,
            "Cosmetic.", Validator.FloatRange(0f, 1f)),
        SettingKey("transition_animation_scale", GLOBAL, SAFE, T1_GRANT,
            "Cosmetic.", Validator.FloatRange(0f, 1f)),
        SettingKey("animator_duration_scale", GLOBAL, SAFE, T1_GRANT,
            "Cosmetic.", Validator.FloatRange(0f, 1f)),
        SettingKey("adb_enabled", GLOBAL, DEVICE_SPECIFIC, NA,
            "TRAP: security state; never auto-enable debugging on a fresh device."),

        // --- System UI ---
        SettingKey("sysui_qs_tiles", SECURE, RISKY, T1_GRANT,
            "References tile specs that may not exist on the new build. Filter to resolvable."),
    )

    /** The ONLY set synced without explicit opt-in. */
    val defaultSyncSet: List<SettingKey> = all.filter { it.classification == SAFE }

    fun byName(name: String, namespace: Namespace): SettingKey? =
        all.firstOrNull { it.name == name && it.namespace == namespace }
}
