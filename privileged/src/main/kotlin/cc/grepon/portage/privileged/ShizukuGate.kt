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
 * Narrow seam over the Shizuku statics + UserService binding. It exists so [ShizukuPrivilegedOps]
 * can be unit-tested with a fake: all of the un-mockable, device-only Shizuku surface lives behind
 * this interface in [AndroidShizukuGate], and the bridge's DECISION logic (availability mapping,
 * the grant gate) is pure and testable. Every method is total — implementations must not throw
 * (the real Shizuku calls throw if the binder is dead, so the impl guards each one).
 */
internal interface ShizukuGate {

    /** Shizuku manager package present on the device (independent of whether it is running). */
    fun isInstalled(): Boolean

    /** The Shizuku binder is currently alive (server running and reachable). */
    fun isBinderAlive(): Boolean

    /** Server is a pre-v11 build whose self-permission model portage does not support. */
    fun isPreV11(): Boolean

    /** portage holds the Shizuku API permission. Meaningful only when [isBinderAlive] is true. */
    fun hasPermission(): Boolean

    /**
     * Run [command] as an argv (no shell) at the shell uid via a one-shot UserService. Returns the
     * process exit code, or null if the bind/exec could not be carried out. PRECONDITION (enforced
     * by the caller): binder alive and permission held.
     */
    suspend fun runAsShell(command: List<String>): Int?

    /**
     * Issue the Shizuku permission request and suspend until the user answers, or return false
     * immediately if the request could not be issued. Returns true iff the user granted. Total —
     * a dead binder / un-issuable request fails closed to false. PRECONDITION (enforced by the
     * caller): binder alive, modern server, permission not already held. The caller bounds the wait
     * with a timeout; cancelling this await removes the registered listener.
     */
    suspend fun requestPermission(): Boolean
}
