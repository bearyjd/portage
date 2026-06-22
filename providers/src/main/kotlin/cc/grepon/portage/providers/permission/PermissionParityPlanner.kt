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
 * The PURE runtime-permission parity plan (ADR-006 D5). Given the source app's captured granted perms
 * ([captured]) and the set the TARGET app declares ([targetDeclared]), classify each into auto-grant /
 * opt-in / skipped — no Android types, and NO grant is executed here (Phase 5b/5d own the privileged
 * call site). Mirrors `apk.ApkReconcile`'s pure-decision shape.
 *
 * Invariants (pinned by tests): nothing is ever planned for grant outside `captured ∩ targetDeclared`;
 * [GrantPlan.auto] is always a subset of [PermissionAllowlist.DEFAULT_SAFE]; dangerous perms (and any
 * future provisional perm) land in [GrantPlan.optIn] (an explicit per-item confirm in Phase 5d), never in `auto`;
 * [PermissionAllowlist.NEVER] and target-undeclared perms are [GrantPlan.skipped] with a reason (every
 * decision is auditable — ADR-006 D5 "every grant logged by name + result").
 */
object PermissionParityPlanner {

    data class SkippedPermission(val permission: String, val reason: String)

    data class GrantPlan(
        /** captured ∩ targetDeclared ∩ DEFAULT_SAFE — granted best-effort, no prompt. */
        val auto: List<String>,
        /** captured ∩ targetDeclared, bucket OPT_IN — needs an explicit per-item confirm (Phase 5d). */
        val optIn: List<String>,
        val skipped: List<SkippedPermission>,
    )

    /** Classify [captured] against [targetDeclared]. De-dupes; preserves first-seen order per list. */
    fun plan(captured: List<String>, targetDeclared: Set<String>): GrantPlan {
        val auto = mutableListOf<String>()
        val optIn = mutableListOf<String>()
        val skipped = mutableListOf<SkippedPermission>()
        for (permission in captured.distinct()) {
            val bucket = PermissionAllowlist.bucket(permission)
            when {
                // NEVER is reported as never-grant regardless of target declaration (it's the stronger fact).
                bucket == PermissionAllowlist.Bucket.NEVER ->
                    skipped += SkippedPermission(permission, "never-grant (signature/system/special)")
                permission !in targetDeclared ->
                    skipped += SkippedPermission(permission, "not declared by target")
                bucket == PermissionAllowlist.Bucket.DEFAULT -> auto += permission
                else -> optIn += permission
            }
        }
        return GrantPlan(auto = auto, optIn = optIn, skipped = skipped)
    }
}
