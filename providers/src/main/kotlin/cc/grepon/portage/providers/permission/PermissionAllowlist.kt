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
 * The PURE runtime-permission classification for parity (ADR-006 D5). Pure Kotlin — no Android types,
 * fully unit-testable, lives beside the other pure decision functions (cf. `apk.ApkReconcile`).
 *
 * Default mode auto-grants ONLY [DEFAULT_SAFE]; everything else a migrated app held is opt-in (an
 * explicit per-item confirm, Phase 5d) or refused. [PROVISIONAL] is modeled but deliberately NOT in
 * [DEFAULT_SAFE]: `OTHER_SENSORS` is V7-TENTATIVE (`pm grant` returned exit 0 against an app that did not
 * declare it) and must be re-verified against a manifest-declared sensor app (Phase 5c) before it is
 * trusted in the default-grant set. [NEVER] is a defense-in-depth denylist — the PRIMARY control is the
 * capture-time `protectionLevel` filter (Phase 5b) that keeps signature/system perms out of the captured
 * set entirely; this belt refuses them even if one slips through.
 */
object PermissionAllowlist {
    const val INTERNET = "android.permission.INTERNET"
    const val OTHER_SENSORS = "android.permission.OTHER_SENSORS"

    /**
     * Auto-granted in default mode (best-effort). On GOS, `INTERNET` is a user-controllable network toggle
     * reachable via `pm grant/revoke` (ADR-001 §2 row 5) — unlike stock Android, where it is a normal /
     * install-time perm needing no grant. This is why a normal-protection perm legitimately sits in the
     * runtime-parity default set. `INTERNET` is V7-verified (`OTHER_SENSORS` is not — see [PROVISIONAL]).
     */
    val DEFAULT_SAFE: Set<String> = setOf(INTERNET)

    /** Modeled but NOT auto-granted yet — joins [DEFAULT_SAFE] only after the Phase 5c hardware re-verify. */
    val PROVISIONAL: Set<String> = setOf(OTHER_SENSORS)

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
     * everything else (incl. [PROVISIONAL]/`OTHER_SENSORS` and any dangerous perm) → [Bucket.OPT_IN].
     */
    fun bucket(permission: String): Bucket = when (permission) {
        in NEVER -> Bucket.NEVER
        in DEFAULT_SAFE -> Bucket.DEFAULT
        else -> Bucket.OPT_IN
    }
}
