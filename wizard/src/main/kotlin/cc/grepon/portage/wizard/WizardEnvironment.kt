/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.wizard

import android.content.Context
import android.provider.Settings

/**
 * What the wizard needs to know about the device's debug state. Reads only — flipping these
 * is the user's job in Settings (the wizard deep-links there); both keys are world-readable.
 */
interface WizardEnvironment {
    fun developerOptionsEnabled(): Boolean
    fun wirelessDebuggingEnabled(): Boolean
}

class AndroidWizardEnvironment(context: Context) : WizardEnvironment {

    private val resolver = context.applicationContext.contentResolver

    override fun developerOptionsEnabled(): Boolean = runCatching {
        Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }.getOrDefault(false)

    override fun wirelessDebuggingEnabled(): Boolean = runCatching {
        // No SDK constant for this one; the key is stable AOSP ("adb_wifi_enabled") and is what
        // LADB polls for the same purpose. Absent ⇒ off.
        Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    internal companion object {
        /**
         * AOSP `Settings.Global` key for the Wireless Debugging toggle (no SDK constant exists).
         * REPLICATED from `:adb-bridge`'s `LibAdbDeviceGate` (the two modules are dependency-
         * isolated by design). Each module's `adb_wifi_enabled key is pinned` test hardcodes this
         * canonical string, so a divergent edit fails CI — keep both copies in lockstep.
         */
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}
