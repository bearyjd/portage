/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.roles

import com.ventouxlabs.portage.adbbridge.AdbBridge
import com.ventouxlabs.portage.providers.roles.RestorableRole
import com.ventouxlabs.portage.providers.roles.RoleRestorer

/**
 * degoogle-only adapter: restores a captured default-app role through the bridge (#122).
 *
 * This is the ONLY edge between `:providers`' role vocabulary and `:adb-bridge`'s. Both sides keep
 * their own closed enum — `:providers` cannot depend on `:adb-bridge` (the seam rule that keeps the
 * play flavor bridge-free, and that ADR-003 relies on), so the two are joined here by an EXHAUSTIVE
 * `when`. That is deliberate: adding a role to either enum fails to compile until both sides and
 * this mapping agree, which makes a privilege-surface change impossible to land by accident.
 *
 * CONSENT: this performs a real role change with NO system confirm dialog. It must only ever be
 * called from an explicit per-role user action. The apply provider deliberately restores nothing.
 *
 * PRIVILEGE DISCIPLINE: the bridge is process-scoped and shared (`AdbBridges.local`); this adapter
 * does not connect or disconnect it, matching the other degoogle adapters.
 */
class AdbRoleRestorer(private val bridge: AdbBridge) : RoleRestorer {

    override suspend fun restore(role: RestorableRole, packageName: String): RoleRestorer.Outcome {
        val target = when (role) {
            RestorableRole.BROWSER -> AdbBridge.RoleTarget.BROWSER
            RestorableRole.DIALER -> AdbBridge.RoleTarget.DIALER
            RestorableRole.HOME -> AdbBridge.RoleTarget.HOME
        }
        return when (bridge.setRoleHolder(target, packageName)) {
            is AdbBridge.OpResult.Ok -> RoleRestorer.Outcome.RESTORED
            // The bridge answered but the platform refused — most often role qualification (the
            // target app does not declare the role's components). Distinct from UNAVAILABLE so the
            // UI can say "that app can't be the default" rather than "setup isn't ready".
            is AdbBridge.OpResult.Failed -> RoleRestorer.Outcome.REJECTED
            is AdbBridge.OpResult.BridgeUnavailable -> RoleRestorer.Outcome.UNAVAILABLE
        }
    }
}
