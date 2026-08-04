/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv

import com.ventouxlabs.portage.providers.ApplyProviderRegistry
import com.ventouxlabs.portage.providers.bluetooth.RePairEntry
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.relay.RelayRestorePrompt
import com.ventouxlabs.portage.providers.roles.RoleRestoreCandidate
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt

/**
 * The Done-screen sinks the apply providers feed (ADR-006). All are checklists/prompts/summaries the
 * user works through on the Done screen, NEVER silent side effects: [onInstallActions] the app-inventory
 * reinstall list, [onRepairEntries] the bonded-Bluetooth re-pair list (PRP-07 Phase 1 — display only,
 * never createBond), [onRelayPrompt] the per-app-backup re-link reminder (PRP-06 — portage hands off the
 * OPAQUE file and never imports it), [onApkInstallPrompt] the Tier-0 install rows, [onPermissionsRestored]
 * the auto-granted default-safe perms summary (ADR-006 D5), [onOptInPermissions] the opt-in dangerous-perm
 * surface (ADR-006 D5, Phase 5d — DATA ONLY, nothing granted from it).
 *
 * Bundled into one holder so adding a sink is a one-line change here, not a positional-param churn across
 * every [ApplyRegistryFactory] call site (production + tests).
 */
data class DoneSinks(
    val onInstallActions: (List<InstallAction>) -> Unit,
    val onRepairEntries: (List<RePairEntry>) -> Unit,
    val onRelayPrompt: (RelayRestorePrompt) -> Unit,
    val onApkInstallPrompt: (ApkInstallPrompt) -> Unit,
    val onPermissionsRestored: (packageName: String, permissions: List<String>) -> Unit,
    val onOptInPermissions: (packageName: String, permissions: List<String>) -> Unit,
    /** Default-app roles the sender had, that are installed here and could be restored (#122). */
    val onRoleCandidates: (List<RoleRestoreCandidate>) -> Unit,
)

/** Builds the compiled apply registry, wired to the receiver's Done-screen [DoneSinks]. A `fun interface`
 *  so production wires real providers while tests pass a trivial lambda. */
fun interface ApplyRegistryFactory {
    fun create(sinks: DoneSinks): ApplyProviderRegistry
}
