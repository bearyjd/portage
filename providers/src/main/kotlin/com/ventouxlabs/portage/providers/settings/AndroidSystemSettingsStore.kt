/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.settings

import android.content.Context
import android.provider.Settings

/**
 * Thin Settings.System adapter behind [SystemSettingsStore] (Tier 0: ADR-001 reach table,
 * T0_SYSTEM). Reads need no permission. Writes need the user-granted "Modify system
 * settings" special access — the UI requests it via ACTION_MANAGE_WRITE_SETTINGS and
 * [canWrite] reflects the grant.
 */
class AndroidSystemSettingsStore(private val context: Context) : SystemSettingsStore {

    override fun read(name: String): String? =
        runCatching { Settings.System.getString(context.contentResolver, name) }.getOrNull()

    override fun canWrite(): Boolean = Settings.System.canWrite(context)

    override fun write(name: String, value: String): Boolean =
        runCatching { Settings.System.putString(context.contentResolver, name, value) }
            .getOrDefault(false)
}
