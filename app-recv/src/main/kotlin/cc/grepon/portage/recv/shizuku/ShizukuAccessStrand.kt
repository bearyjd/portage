/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.shizuku

import cc.grepon.portage.privileged.PrivilegedOps

/**
 * UI-facing status of the OPTIONAL Tier-1 "secure settings" unlock (ADR-001). portage moves system
 * settings at Tier 0 without it; this only governs the extra `Settings.Secure`/`Global` parity that
 * needs a one-shot WRITE_SECURE_SETTINGS grant via Shizuku.
 *
 * Derived purely from (Shizuku availability, whether WRITE_SECURE_SETTINGS is already held) by
 * [strandFor]. The two transient states [UNLOCKING]/[GRANT_FAILED] are NOT derivable from a
 * point-in-time read — the ViewModel sets them around an unlock attempt.
 */
enum class ShizukuAccessStrand {
    /** Shizuku not installed — the affordance is hidden; portage is fully usable at Tier 0 without it. */
    NOT_INSTALLED,

    /** Installed but too old for portage to drive (pre-v11) — the fix is "update Shizuku". */
    OUTDATED,

    /** Installed but not running — the fix is "start Shizuku". */
    NOT_RUNNING,

    /** Reachable but portage isn't authorized yet — the actionable state ("Unlock secure settings"). */
    LOCKED,

    /** An unlock is in flight (authorizing and/or running the one-shot grant). */
    UNLOCKING,

    /** WRITE_SECURE_SETTINGS is held — secure system settings will come across. Terminal. */
    UNLOCKED,

    /** Shizuku is authorized but the one-shot grant did not take — offer a retry. */
    GRANT_FAILED,
}

/**
 * Pure mapping from a point-in-time bridge read to a [ShizukuAccessStrand]. A held grant wins over
 * everything (it outlives Shizuku — ADR-001 §1), so [canWriteSecureSettings] short-circuits to
 * [ShizukuAccessStrand.UNLOCKED]. LIVE-but-not-yet-granted maps to [ShizukuAccessStrand.LOCKED]:
 * authorization alone isn't the finish line — the unlock action still has to run the `pm grant`.
 */
fun strandFor(
    availability: PrivilegedOps.Availability,
    canWriteSecureSettings: Boolean,
): ShizukuAccessStrand = when {
    canWriteSecureSettings -> ShizukuAccessStrand.UNLOCKED
    else -> when (availability) {
        PrivilegedOps.Availability.NOT_INSTALLED -> ShizukuAccessStrand.NOT_INSTALLED
        PrivilegedOps.Availability.OUTDATED -> ShizukuAccessStrand.OUTDATED
        PrivilegedOps.Availability.INSTALLED_NOT_RUNNING -> ShizukuAccessStrand.NOT_RUNNING
        PrivilegedOps.Availability.PERMISSION_DENIED -> ShizukuAccessStrand.LOCKED
        PrivilegedOps.Availability.LIVE -> ShizukuAccessStrand.LOCKED
    }
}
