/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.sms

/**
 * The default-SMS-app role handoff, as the ViewModel needs it (DEVILS_ADVOCATE.md Q4).
 * Acquiring the role is an interactive system gesture, so [acquireRole] suspends until the
 * user answers the platform dialog; the Android implementation bridges that dialog's
 * ActivityResult back into the coroutine. JVM tests inject a fake.
 *
 * Restoring SMS requires portage to hold ROLE_SMS transiently while it writes the messages,
 * then hand the role BACK to the prior holder. The teardown is REQUIRED, not optional — the
 * caller wraps apply in a `finally` that always calls [relinquishTo].
 */
interface SmsRoleCoordinator {

    /** Who holds the default-SMS role right now — recorded BEFORE acquiring, the teardown target. */
    fun priorDefaultPackage(): String?

    /**
     * Request the default-SMS role and suspend until the user answers. Returns true only if
     * portage now holds it. Returns false (no role taken) if the role is unavailable, the
     * dialog can't be shown, or the user declines — the caller then skips SMS gracefully.
     */
    suspend fun acquireRole(): Boolean

    /**
     * Hand the role back toward [priorPackage]. Idempotent and best-effort: on Android 10+ a
     * third-party app can only request the role for itself, so this surfaces the system
     * change-default prompt aimed at the prior holder rather than silently reassigning it.
     */
    suspend fun relinquishTo(priorPackage: String?)

    /** A no-op coordinator: SMS is never grantable, so the apply path always self-skips. */
    object Inert : SmsRoleCoordinator {
        override fun priorDefaultPackage(): String? = null
        override suspend fun acquireRole(): Boolean = false
        override suspend fun relinquishTo(priorPackage: String?) = Unit
    }
}
