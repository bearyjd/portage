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
 * The `play` (Tier-0-only) privilege integration. The `play` flavor compiles with NEITHER `:adb-bridge`
 * NOR `:wizard` (a Google Play policy surface — those modules bundle libadb / spake2 / conscrypt), so
 * this source set holds ZERO privilege: every seam is the no-op `:providers` default, and there is no
 * advanced-setup affordance. The receiver therefore runs exactly the Tier-0 path (settings via the
 * normal Settings.* API, APK install via the system PackageInstaller confirm dialog) with no escalation.
 *
 * This is the regression belt for the play no-bridge invariant: if a privilege adapter is ever wired in
 * here, [PlayPrivilegeIntegrationTest] fails. See [DegooglePrivilegeIntegration] for the full-Tier-1 path.
 */
object PlayPrivilegeIntegration : PrivilegeIntegration {

    override val offersAdvancedSetup: Boolean = false

    override fun wiring(context: Context): PrivilegeWiring = playWiring()

    override fun onAdvancedSetupRequested(context: Context) = Unit

    override fun onResume(context: Context) = Unit

    @Composable
    override fun AdvancedSetup(onClose: () -> Unit, modifier: Modifier) {
        // Never invoked: offersAdvancedSetup is false, so ReceiverApp never routes to this surface.
    }
}

/**
 * The no-op (Tier-0-only) wiring: every seam is a `:providers` default that holds no privilege. Kept
 * Context-free so the regression belt can assert it without an Android Context.
 */
internal fun playWiring(): PrivilegeWiring = PrivilegeWiring(
    silentInstaller = ApkSilentInstaller.Deferred,
    hasSilentInstall = { false },
    permissionGranter = RuntimePermissionGranter.NoOp,
    targetDeclaredPermissions = TargetDeclaredPermissions.None,
    tierOneGrant = TierOneGrant.Unavailable,
    optInPermissionGranter = RuntimePermissionGranter.NoOp,
    // No bridge in this flavor, so role restore is structurally impossible — not merely disabled.
    roleRestorer = RoleRestorer.Unavailable,
    canRestoreRoles = false,
)

/** `play`: the Tier-0 stub — no `:adb-bridge`, no `:wizard`, no privilege. */
fun providePrivilegeIntegration(context: Context): PrivilegeIntegration = PlayPrivilegeIntegration
