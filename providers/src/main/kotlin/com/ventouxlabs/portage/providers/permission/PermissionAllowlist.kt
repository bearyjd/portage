/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.permission

/**
 * The PURE runtime-permission classification for parity (ADR-006 D5). Pure Kotlin — no Android types,
 * fully unit-testable, lives beside the other pure decision functions (cf. `apk.ApkReconcile`).
 *
 * Default mode auto-grants ONLY [DEFAULT_SAFE]; everything else a migrated app held is opt-in (an
 * explicit per-item confirm, Phase 5d) or refused. [PROVISIONAL] is the verify-first seam (currently
 * empty): a perm sits there, modeled but NOT in [DEFAULT_SAFE], until it is hardware-verified
 * `pm grant`-controllable. `OTHER_SENSORS` was promoted out of it into [DEFAULT_SAFE] after the
 * 2026-06-21 GOS A16 round-trip re-verify (ADR-006 D5 / V7). [NEVER] is a defense-in-depth denylist — the PRIMARY control is the
 * capture-time `protectionLevel` filter (Phase 5b) that keeps signature/system perms out of the captured
 * set entirely; this belt refuses them even if one slips through.
 */
object PermissionAllowlist {
    const val INTERNET = "android.permission.INTERNET"
    const val OTHER_SENSORS = "android.permission.OTHER_SENSORS"

    /**
     * Auto-granted in default mode (best-effort). On GOS these are user-controllable network/sensor toggles
     * modeled AS permissions reachable via `pm grant/revoke` (ADR-001 §2 row 5) — unlike stock Android, where
     * `INTERNET` is a normal/install-time perm. Both are hardware-verified `pm grant`-controllable on GOS A16:
     * `INTERNET` (V7 PASS) and `OTHER_SENSORS` (re-verified 2026-06-21 against a manifest-declared app —
     * `app.grapheneos.camera` granted=true → revoke → grant round-trip honored; closes the V7 TENTATIVE gap).
     */
    val DEFAULT_SAFE: Set<String> = setOf(INTERNET, OTHER_SENSORS)

    /**
     * No currently-provisional perms. `OTHER_SENSORS` was promoted into [DEFAULT_SAFE] after the Phase 5c
     * hardware re-verify (2026-06-21). Retained as the seam for any future verify-first candidate.
     */
    val PROVISIONAL: Set<String> = emptySet()

    /** Never granted by parity, in any mode (signature/system/special). Defense-in-depth (see class KDoc). */
    val NEVER: Set<String> = setOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.WRITE_SETTINGS",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
    )

    enum class Bucket { DEFAULT, OPT_IN, NEVER }

    /**
     * Classify a permission: a [NEVER] entry → [Bucket.NEVER]; a [DEFAULT_SAFE] entry → [Bucket.DEFAULT];
     * everything else (any dangerous perm, e.g. `CAMERA`, and any future [PROVISIONAL] perm) → [Bucket.OPT_IN].
     */
    fun bucket(permission: String): Bucket = when (permission) {
        in NEVER -> Bucket.NEVER
        in DEFAULT_SAFE -> Bucket.DEFAULT
        else -> Bucket.OPT_IN
    }
}
