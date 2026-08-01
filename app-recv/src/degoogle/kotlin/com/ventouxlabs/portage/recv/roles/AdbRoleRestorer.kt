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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

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
 * PRIVILEGE DISCIPLINE — connect-if-needed, then ALWAYS disconnect. This matches
 * [com.ventouxlabs.portage.recv.install.AdbRuntimePermissionGranter] and
 * [com.ventouxlabs.portage.recv.install.AdbApkInstaller]; the wizard disconnects right after its
 * capability probe (ADR-003), so by the time the user taps SET on the Done screen NOTHING is
 * holding the bridge open. An earlier version of this class omitted the connect and was therefore
 * a dead button — the op always returned `BridgeUnavailable`.
 *
 * The [attemptTimeoutMs] ceiling is NOT optional now that this connects: `connect()` can block
 * indefinitely when Wireless Debugging is off (#70), and a reboot silently turns it off
 * (`SPIKE-RESULTS-2026-07-31.md` §8.3), which makes that a routine state rather than an edge case.
 * On timeout the restore reports UNAVAILABLE and the row stays offered — never a false success.
 */
class AdbRoleRestorer(
    private val bridge: AdbBridge,
    private val attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS,
) : RoleRestorer {

    override suspend fun restore(role: RestorableRole, packageName: String): RoleRestorer.Outcome {
        val target = when (role) {
            RestorableRole.BROWSER -> AdbBridge.RoleTarget.BROWSER
            RestorableRole.DIALER -> AdbBridge.RoleTarget.DIALER
            RestorableRole.HOME -> AdbBridge.RoleTarget.HOME
        }
        return try {
            withTimeoutOrNull(attemptTimeoutMs) {
                if (!bridge.isConnected()) {
                    when (bridge.connect()) {
                        is AdbBridge.ConnectionResult.Connected -> Unit // proceed
                        // No live bridge (commonly: Wireless Debugging off after a reboot).
                        else -> return@withTimeoutOrNull RoleRestorer.Outcome.UNAVAILABLE
                    }
                }
                when (bridge.setRoleHolder(target, packageName)) {
                    // Exit 0 is NOT proof the role moved. `add-role-holder` was only ever exercised
                    // on the success path during the A17 spike — role-qualification failure is
                    // recorded there as UNTESTED — so a platform that exits 0 while silently
                    // refusing a non-qualifying package would have portage display "DEFAULT" for a
                    // role it never set, and drop the row so the user cannot even retry. Read the
                    // role back and believe the readback, not the exit code.
                    is AdbBridge.OpResult.Ok -> verify(target, packageName)
                    // The bridge answered but the platform refused — most often role qualification
                    // (the target app does not declare the role's components). Distinct from
                    // UNAVAILABLE so the UI can say "that app can't be the default" rather than
                    // "setup isn't ready".
                    is AdbBridge.OpResult.Failed -> RoleRestorer.Outcome.REJECTED
                    is AdbBridge.OpResult.BridgeUnavailable -> RoleRestorer.Outcome.UNAVAILABLE
                }
            } ?: RoleRestorer.Outcome.UNAVAILABLE // timed out → not restored, row stays offered
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // The bridge is a network client over localhost TLS: connect() and setRoleHolder() can
            // throw (IO, TLS, protocol) as readily as they can answer. Report it as UNAVAILABLE —
            // the row stays offered and the user can retry. Letting it propagate would reach
            // viewModelScope's uncaught handler and take the process down on the Done screen.
            RoleRestorer.Outcome.UNAVAILABLE
        } finally {
            // Never hold shell uid open (ADR-003). The bridge reconnects with the persisted key.
            bridge.disconnect()
        }
    }

    /**
     * Confirm the role actually moved before claiming success.
     *
     * An UNVERIFIABLE readback (bridge died between the write and the read, command failed) is
     * reported as UNAVAILABLE, never RESTORED: "I could not check" and "it worked" must not
     * collapse, or the verification fails OPEN and buys nothing. The cost of being wrong in this
     * direction is a row that stays offered and a retry that is harmless — `add-role-holder` is
     * idempotent.
     */
    private suspend fun verify(
        target: AdbBridge.RoleTarget,
        packageName: String,
    ): RoleRestorer.Outcome {
        // ONE read. Calling roleHolders() per branch would both double the round-trip and let the
        // two branches disagree if the role changed between them.
        val holders = bridge.roleHolders(target) ?: return RoleRestorer.Outcome.UNAVAILABLE
        return if (packageName in holders) {
            RoleRestorer.Outcome.RESTORED
        } else {
            // The command succeeded but the package does not hold the role: the platform accepted
            // the call and declined the change. That is the qualification case, so REJECTED is the
            // honest verdict — "that app can't be the default", not "setup isn't ready".
            RoleRestorer.Outcome.REJECTED
        }
    }

    private companion object {
        /**
         * One tap's ceiling, matching the permission granter's. Covers connect + one `cmd role`
         * round-trip with room to spare; short enough that a wedged connect cannot hang the
         * Done screen.
         */
        const val ATTEMPT_TIMEOUT_MS = 90_000L
    }
}
