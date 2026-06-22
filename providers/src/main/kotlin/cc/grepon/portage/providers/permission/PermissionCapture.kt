/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.permission

/**
 * Sender-side capture filter for runtime-permission parity (ADR-006 D5, Phase 5b). PURE — decides which
 * of a source app's requested permissions to record in the wire container's `capturedPermissions`, given
 * each permission's grant state + protection level (gathered from `PackageManager` by the app-send
 * adapter, which keeps the Android types out of `:providers`).
 *
 * This is the PRIMARY control the receiver's [PermissionParityPlanner] relies on (the planner's `NEVER`
 * denylist is only a belt): it keeps signature/system perms OUT of the captured set entirely, so they
 * never even reach the receiver as parity candidates. A permission is captured iff it was GRANTED on the
 * source AND it is either dangerous-protection OR a GOS network/sensor special toggle. Normal-protection
 * perms (auto-granted at install, no action needed) and signature/system perms are dropped.
 */
object PermissionCapture {

    /**
     * The GOS network/sensor special toggles, modeled AS permissions reachable by `pm grant` (ADR-001 §2
     * row 5). They are NOT dangerous-protection, so a protection-level filter alone would drop them —
     * captured explicitly here. Same canonical strings as [PermissionAllowlist] (single source of truth).
     */
    val NETWORK_SENSOR_SPECIALS: Set<String> =
        setOf(PermissionAllowlist.INTERNET, PermissionAllowlist.OTHER_SENSORS)

    /** One requested permission as seen on the SOURCE device (gathered from `PackageManager`). */
    data class RequestedPermission(
        val name: String,
        val granted: Boolean,
        /** True iff its `protectionLevel` is `dangerous` (a runtime permission). */
        val dangerous: Boolean,
    )

    /**
     * The subset of [requested] to record as `capturedPermissions`: GRANTED AND (dangerous OR a
     * [NETWORK_SENSOR_SPECIALS] toggle). De-duped and sorted for a stable, deterministic wire order.
     */
    fun capturable(requested: List<RequestedPermission>): List<String> =
        requested.asSequence()
            .filter { it.granted && (it.dangerous || it.name in NETWORK_SENSOR_SPECIALS) }
            .map { it.name }
            .distinct()
            .sorted()
            .toList()
}
