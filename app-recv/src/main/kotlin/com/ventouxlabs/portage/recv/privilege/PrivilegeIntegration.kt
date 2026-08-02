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
import androidx.compose.ui.Modifier
import com.ventouxlabs.portage.providers.apk.ApkSilentInstaller
import com.ventouxlabs.portage.providers.apk.RuntimePermissionGranter
import com.ventouxlabs.portage.providers.apk.TargetDeclaredPermissions
import com.ventouxlabs.portage.providers.roles.RoleRestorer
import com.ventouxlabs.portage.providers.settings.TierOneGrant

/**
 * The `main`-side privilege seam (product-flavor split, ADR-003 distribution flavors). `main` must
 * compile for the `play` (Tier-0-only) flavor, which carries NO `:adb-bridge` / `:wizard` code, so no
 * type from those modules may appear here. Every apply-time privilege concern is expressed purely in
 * `:providers` seam types, supplied per flavor:
 *
 *  - `degoogle` (full Tier-1) wires the real [com.ventouxlabs.portage.recv.install.AdbApkInstaller],
 *    [com.ventouxlabs.portage.recv.install.AdbRuntimePermissionGranter], and the AdbBridge self-grant.
 *  - `play` (Tier-0-only) returns the no-op `:providers` defaults so the binary holds zero privilege.
 *
 * The active flavor's source set DEFINES [providePrivilegeIntegration]; `main` only calls it
 * (standard Android flavor pattern — main + the active flavor compile as one unit).
 */
class PrivilegeWiring(
    val silentInstaller: ApkSilentInstaller,
    val hasSilentInstall: () -> Boolean,
    val permissionGranter: RuntimePermissionGranter,
    val targetDeclaredPermissions: TargetDeclaredPermissions,
    val tierOneGrant: TierOneGrant,
    val optInPermissionGranter: RuntimePermissionGranter,
    /**
     * Restores a captured default-app role (#122). degoogle maps onto the bridge's typed
     * RoleTarget; play gets [RoleRestorer.Unavailable] because it ships no bridge. Only ever
     * invoked from an explicit per-role user action — the shell path shows no confirm dialog.
     */
    val roleRestorer: RoleRestorer,
    /**
     * Whether this BUILD can restore roles at all. False on play, which ships no bridge — so the
     * receiver must not offer a "set as default" affordance it can never honour. Distinct from the
     * per-attempt outcome: on degoogle this is true even before the bridge is connected, and a
     * failed attempt then leaves the row offered rather than claiming success.
     */
    val canRestoreRoles: Boolean,
)

/**
 * The flavor seam the receiver is wired through. Holds the apply-time [PrivilegeWiring] and the
 * optional in-app advanced-setup surface. The `play` implementation returns no-op wiring and never
 * offers advanced setup; the `degoogle` implementation wires the self-contained ADB bridge (ADR-003).
 */
interface PrivilegeIntegration {

    /** Whether this flavor exposes the optional privilege-bootstrap (advanced transfer setup). */
    val offersAdvancedSetup: Boolean

    /** Build the apply-time privilege seams. Called once when the receiver ViewModel is constructed. */
    fun wiring(context: Context): PrivilegeWiring

    /**
     * Called exactly when the user taps the "Advanced transfer setup" affordance — not on composition
     * entry or config changes. `degoogle` starts (or resumes) the privilege wizard on this tap; `play`
     * is a no-op. Taking [context] (not a Composable receiver) keeps `main` free of
     * `:wizard` / `:adb-bridge` types.
     */
    fun onAdvancedSetupRequested(context: Context)

    /**
     * Re-evaluate flavor state when the Activity resumes (e.g. returning from Settings mid-wizard).
     * `degoogle` rechecks the privilege wizard; `play` is a no-op.
     */
    fun onResume(context: Context)

    /**
     * The optional advanced-setup surface, shown only when [offersAdvancedSetup] is true. `degoogle`
     * renders the privilege wizard (starting it on entry and recording/resetting its outcome on close);
     * `play` is never invoked.
     */
    @Composable
    fun AdvancedSetup(onClose: () -> Unit, modifier: Modifier)
}
