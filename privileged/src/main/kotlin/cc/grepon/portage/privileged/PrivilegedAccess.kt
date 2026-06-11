/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.privileged

/**
 * The interactive surface that turns the dormant Shizuku bridge reachable: read availability, drive
 * the user-facing Shizuku authorization, and run the one-shot WRITE_SECURE_SETTINGS grant. It is a
 * seam so the receiver UI can offer an "unlock secure settings" affordance WITHOUT importing the
 * Shizuku API — every `rikka.shizuku.*` call stays inside [AndroidPrivilegedAccess], and the
 * ViewModel's unlock orchestration is testable against a fake.
 *
 * Distinct from [PrivilegedOps] (the apply-time privilege boundary): this models the one-time
 * "unlock" gesture, not a per-transfer operation. After [ensureWriteSecureSettingsGranted] succeeds
 * once, settings writes use the normal `Settings.*` API with no live bridge (ADR-001 §1), so
 * [canWriteSecureSettings] — not Shizuku liveness — is the real finish line.
 *
 * All methods are total: implementations must not throw (the Shizuku statics throw on a dead
 * binder), so each fails closed to the safe value.
 */
interface PrivilegedAccess {

    /** Current liveness of the Shizuku bridge (delegates to [PrivilegedOps.availability]). */
    fun availability(): PrivilegedOps.Availability

    /**
     * True once portage holds WRITE_SECURE_SETTINGS. The grant persists across reboots and across
     * the bridge dying (ADR-001 §1), so this — not [availability] — is the terminal "unlocked" signal.
     */
    fun canWriteSecureSettings(): Boolean

    /**
     * Show the Shizuku authorization dialog and suspend until the user answers (or a bounded wait
     * elapses). Returns true only if portage now holds the Shizuku API permission. A dead binder, a
     * pre-v11 server, a declined prompt, or a timeout all return false (the UI then shows the
     * "start / update Shizuku" guidance rather than hanging).
     */
    suspend fun requestAccess(): Boolean

    /**
     * Phase-A one-shot: have the shell uid `pm grant` us WRITE_SECURE_SETTINGS (delegates to
     * [PrivilegedOps.ensureWriteSecureSettingsGranted]). Assumes [requestAccess] already succeeded.
     */
    suspend fun ensureWriteSecureSettingsGranted(): PrivilegedOps.GrantOutcome

    /**
     * No-op access: the bridge is never reachable. The default for the receiver ViewModel and the
     * stand-in JVM tests use, so the unlock affordance simply reports "not installed" and self-skips.
     */
    object Inert : PrivilegedAccess {
        override fun availability(): PrivilegedOps.Availability = PrivilegedOps.Availability.NOT_INSTALLED
        override fun canWriteSecureSettings(): Boolean = false
        override suspend fun requestAccess(): Boolean = false
        override suspend fun ensureWriteSecureSettingsGranted(): PrivilegedOps.GrantOutcome =
            PrivilegedOps.GrantOutcome.BRIDGE_UNAVAILABLE
    }
}
