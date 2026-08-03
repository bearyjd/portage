/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.privilege

import android.content.Context
import com.ventouxlabs.portage.adbbridge.AdbBridges
import com.ventouxlabs.portage.adbbridge.MdnsPairingPortDetector
import com.ventouxlabs.portage.recv.install.hasSilentInstall
import com.ventouxlabs.portage.wizard.AndroidWizardEnvironment
import com.ventouxlabs.portage.wizard.PrivilegeWizard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process-scoped wizard, same pattern as SmsRoleCoordinatorHolder: a config change mid-wizard
 * recreates the Activity but must not lose pairing/probe progress, so the state machine (and
 * the bridge underneath it) outlives any single Activity.
 */
object PrivilegeWizardHolder {

    @Volatile
    private var instance: PrivilegeWizard? = null

    fun get(context: Context): PrivilegeWizard {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return PrivilegeWizard(
                bridge = AdbBridges.local(app),
                environment = AndroidWizardEnvironment(app),
                portDetector = MdnsPairingPortDetector(app),
                scope = scope,
            ).also { wizard ->
                // A probe is authoritative only when it completes. Persist BOTH verdicts: a positive
                // result survives leaving Ready/process recreation (#86), while a later unsupported
                // probe clears an old positive so Tier-0 stays the fallback (P1 review).
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                scope.launch {
                    wizard.step.collect { step ->
                        if (step is PrivilegeWizard.Step.Ready) {
                            prefs.edit().putBoolean(SILENT_INSTALL, hasSilentInstall(step.capabilities)).apply()
                        }
                    }
                }
                instance = wizard
            }
        }
    }

    /** False until a completed probe explicitly says this device supports the silent install verb. */
    fun hasSilentInstall(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(SILENT_INSTALL, false)

    private const val PREFS = "privilege-capabilities"
    private const val SILENT_INSTALL = "silent-install"
}
