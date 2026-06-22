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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ADR-006 D5 — guardrails for the PURE runtime-permission parity planner. */
class PermissionParityPlannerTest {

    private val internet = PermissionAllowlist.INTERNET
    private val otherSensors = PermissionAllowlist.OTHER_SENSORS
    private val camera = "android.permission.CAMERA"
    private val writeSecure = "android.permission.WRITE_SECURE_SETTINGS"

    @Test
    fun `INTERNET captured and declared is auto-granted`() {
        val plan = PermissionParityPlanner.plan(listOf(internet), setOf(internet))
        assertThat(plan.auto).containsExactly(internet)
        assertThat(plan.optIn).isEmpty()
        assertThat(plan.skipped).isEmpty()
    }

    @Test
    fun `OTHER_SENSORS is auto-granted (promoted to default after the Phase 5c hardware re-verify)`() {
        // ADR-006 D5: OTHER_SENSORS is V7-PASS as of 2026-06-21 → in DEFAULT_SAFE → auto-granted.
        val plan = PermissionParityPlanner.plan(listOf(otherSensors), setOf(otherSensors))
        assertThat(plan.auto).containsExactly(otherSensors)
        assertThat(plan.optIn).isEmpty()
    }

    @Test
    fun `a dangerous perm captured and declared is opt-in, never auto`() {
        val plan = PermissionParityPlanner.plan(listOf(camera), setOf(camera))
        assertThat(plan.optIn).containsExactly(camera)
        assertThat(plan.auto).isEmpty()
    }

    @Test
    fun `a captured perm the target does not declare is skipped, never granted`() {
        val plan = PermissionParityPlanner.plan(listOf(internet), targetDeclared = emptySet())
        assertThat(plan.auto).isEmpty()
        assertThat(plan.optIn).isEmpty()
        assertThat(plan.skipped.map { it.permission }).containsExactly(internet)
        assertThat(plan.skipped.single().reason).contains("not declared")
    }

    @Test
    fun `a NEVER perm is skipped even when captured and declared`() {
        val plan = PermissionParityPlanner.plan(listOf(writeSecure), setOf(writeSecure))
        assertThat(plan.auto).isEmpty()
        assertThat(plan.optIn).isEmpty()
        assertThat(plan.skipped.map { it.permission }).containsExactly(writeSecure)
        assertThat(plan.skipped.single().reason).contains("never-grant")
    }

    @Test
    fun `auto is always a subset of DEFAULT_SAFE and of targetDeclared`() {
        val captured = listOf(internet, otherSensors, camera, writeSecure)
        val declared = setOf(internet, otherSensors, camera, writeSecure)
        val plan = PermissionParityPlanner.plan(captured, declared)
        assertThat(PermissionAllowlist.DEFAULT_SAFE).containsAtLeastElementsIn(plan.auto)
        assertThat(declared).containsAtLeastElementsIn(plan.auto)
        // Of this mixed set, INTERNET and OTHER_SENSORS qualify for auto; CAMERA (opt-in) and
        // WRITE_SECURE_SETTINGS (never) do not.
        assertThat(plan.auto).containsExactly(internet, otherSensors)
    }

    @Test
    fun `empty captured yields an empty plan and duplicates are de-duped`() {
        val empty = PermissionParityPlanner.plan(emptyList(), setOf(internet))
        assertThat(empty.auto).isEmpty()
        assertThat(empty.optIn).isEmpty()
        assertThat(empty.skipped).isEmpty()
        val dup = PermissionParityPlanner.plan(listOf(internet, internet), setOf(internet))
        assertThat(dup.auto).containsExactly(internet)
    }

    @Test
    fun `a DEFAULT-bucket perm captured but not declared by the target is skipped, never auto`() {
        // Pins `auto ⊆ targetDeclared` independently of the allowlist: INTERNET (a DEFAULT-bucket perm) is
        // captured but the target does not declare it → it MUST be skipped, not auto-granted. Guards against
        // a future refactor that reorders the DEFAULT arm ahead of the declaration check.
        val plan = PermissionParityPlanner.plan(listOf(internet, camera), setOf(camera))
        assertThat(plan.auto).isEmpty()
        assertThat(plan.optIn).containsExactly(camera)
        assertThat(plan.skipped.map { it.permission }).containsExactly(internet)
        assertThat(plan.skipped.single().reason).contains("not declared")
    }

    @Test
    fun `never-grant and not-declared skip reasons are distinct (auditable)`() {
        val neverReason = PermissionParityPlanner.plan(listOf(writeSecure), setOf(writeSecure))
            .skipped.single().reason
        val notDeclaredReason = PermissionParityPlanner.plan(listOf(internet), emptySet())
            .skipped.single().reason
        assertThat(neverReason).isNotEqualTo(notDeclaredReason)
    }
}
