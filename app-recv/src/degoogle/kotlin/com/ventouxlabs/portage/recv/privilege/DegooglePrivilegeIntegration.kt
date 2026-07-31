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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ventouxlabs.portage.adbbridge.AdbBridge
import com.ventouxlabs.portage.adbbridge.AdbBridges
import com.ventouxlabs.portage.providers.settings.TierOneGrant
import com.ventouxlabs.portage.recv.install.AdbApkInstaller
import com.ventouxlabs.portage.recv.install.AdbRuntimePermissionGranter
import com.ventouxlabs.portage.recv.install.androidTargetDeclaredPermissions
import com.ventouxlabs.portage.recv.install.hasSilentInstall
import com.ventouxlabs.portage.recv.roles.AdbRoleRestorer
import com.ventouxlabs.portage.recv.ui.WizardScreen
import com.ventouxlabs.portage.wizard.PrivilegeWizard

/**
 * The `degoogle` (full Tier-1) privilege integration: it ties `:providers` to the self-contained ADB
 * bridge (ADR-003) and renders the privilege wizard. This is the ONLY place in `:app-recv` (outside the
 * moved install adapters) that touches `:adb-bridge` / `:wizard`; the `play` flavor's
 * [providePrivilegeIntegration] returns no-op wiring with neither dependency compiled in.
 *
 * The wiring is EXACTLY what the receiver factory used before the flavor split — one process-scoped
 * bridge (AdbBridges.local caches a single instance) shared by the silent APK installer, the
 * runtime-permission granters, and the Tier-1 settings grant; the capability read comes from the
 * process-scoped privilege wizard (PrivilegeWizardHolder).
 */
object DegooglePrivilegeIntegration : PrivilegeIntegration {

    override val offersAdvancedSetup: Boolean = true

    override fun wiring(context: Context): PrivilegeWiring {
        // One process-scoped bridge: the silent APK installer and the Tier-1 settings grant both go
        // through it (AdbBridges.local caches a single instance).
        val bridge = AdbBridges.local(context)
        return PrivilegeWiring(
            // The P6 stdin-streaming silent installer (AdbApkInstaller → pm install-write -S .. - over
            // the bridge); self-guards via AdbBridge.connect() and degrades to Tier-0 when unavailable.
            silentInstaller = AdbApkInstaller(bridge),
            // SILENT_INSTALL capability read from the wizard's probed set (Ready → caps, else emptySet).
            hasSilentInstall = { hasSilentInstall(PrivilegeWizardHolder.get(context).step.value) },
            // Runtime-permission parity (ADR-006 D5), silent-install path only.
            permissionGranter = AdbRuntimePermissionGranter(bridge),
            // The target's declared set, read from PackageManager post-install.
            targetDeclaredPermissions = androidTargetDeclaredPermissions(context),
            // The lazy Tier-1 WRITE_SECURE_SETTINGS grant fallback (ADR-001 Phase A).
            tierOneGrant = adbTierOneGrant(bridge),
            // Phase 5d-2 user-driven opt-in dangerous-perm grant on the Done screen; same process-scoped
            // bridge, a distinct granter from the apply provider's auto-grant one.
            optInPermissionGranter = AdbRuntimePermissionGranter(bridge),
            // Default-app role restore (#122) — same process-scoped bridge. Only invoked from an
            // explicit per-role tap; the shell path shows no system confirm dialog.
            roleRestorer = AdbRoleRestorer(bridge),
            canRestoreRoles = true,
        )
    }

    override fun onAdvancedSetupRequested(context: Context) {
        // Called exactly on the user tap — not on recomposition or config changes. Starting here
        // (not inside AdvancedSetup's composition) means a rotation while the wizard is in
        // Ready/Skipped state never silently restarts it via a re-fired LaunchedEffect.
        PrivilegeWizardHolder.get(context).start()
    }

    override fun onResume(context: Context) {
        // Returning from Settings mid-wizard: Developer options / Wireless debugging may have just been
        // toggled — let the privilege wizard advance (ADR-003).
        PrivilegeWizardHolder.get(context).recheck()
    }

    @Composable
    override fun AdvancedSetup(onClose: () -> Unit, modifier: Modifier) {
        val context = LocalContext.current
        // Process-scoped: a config change mid-wizard recreates the Activity but must not lose
        // pairing/probe progress (PrivilegeWizardHolder). start() is NOT called here — the caller
        // already invoked onAdvancedSetupRequested() on the user tap; calling start() again on
        // recomposition (e.g. rotation while Ready/Skipped) would silently restart the wizard.
        val wizard = remember(context) { PrivilegeWizardHolder.get(context) }
        val step by wizard.step.collectAsStateWithLifecycle()
        WizardScreen(
            wizard = wizard,
            onClose = {
                // A finished run (Ready/Skipped) keeps its recorded outcome; an abandoned run resets so
                // the next entry starts clean.
                if (step !is PrivilegeWizard.Step.Ready && step !is PrivilegeWizard.Step.Skipped) {
                    wizard.dismiss()
                }
                onClose()
            },
            modifier = modifier,
        )
    }
}

/** `degoogle`: the real integration with the self-contained ADB bridge wired in (ADR-003). */
fun providePrivilegeIntegration(context: Context): PrivilegeIntegration = DegooglePrivilegeIntegration

/** Adapt the AdbBridge self-grant (ADR-003) to the providers' narrow [TierOneGrant] seam. */
private fun adbTierOneGrant(bridge: AdbBridge) = TierOneGrant {
    when (bridge.selfGrant(WRITE_SECURE_SETTINGS_PERMISSION)) {
        AdbBridge.GrantResult.GRANTED -> TierOneGrant.Outcome.GRANTED
        AdbBridge.GrantResult.REJECTED -> TierOneGrant.Outcome.REJECTED
        AdbBridge.GrantResult.BRIDGE_UNAVAILABLE -> TierOneGrant.Outcome.UNAVAILABLE
    }
}

private const val WRITE_SECURE_SETTINGS_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
