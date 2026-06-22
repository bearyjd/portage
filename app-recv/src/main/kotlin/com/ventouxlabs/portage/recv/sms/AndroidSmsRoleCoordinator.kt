/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import com.ventouxlabs.portage.providers.sms.AndroidSmsRoleGateway
import com.ventouxlabs.portage.providers.sms.SmsRoleGateway
import java.io.File

/**
 * Bridges the interactive ROLE_SMS grant into a suspend call. [acquireRole] launches the
 * platform role dialog through [requestLauncher] (wired by the host Activity to an
 * ActivityResultLauncher) and suspends on a [CompletableDeferred] until the Activity routes
 * the result back via [onRoleResult]. Prior-holder bookkeeping and the relinquish prompt
 * delegate to the providers-layer [SmsRoleGateway], the single source of truth for the
 * default-SMS state, so this class only adds the acquisition step.
 *
 * Two hardening measures back DEVILS_ADVOCATE.md Q4's stranding analysis:
 *  - [ROLE_DIALOG_TIMEOUT_MS]: the await can't hang forever (e.g. the user walks away from the
 *    dialog). A timeout returns false → the transfer proceeds, SMS self-skips, nobody is stranded.
 *  - [SmsRoleLedger]: a persistent marker armed when the role is taken, so a process death mid-
 *    handoff is recoverable on the next launch via [currentStrand] (the `finally` relinquish
 *    cannot survive a kill). This instance is meant to be process-scoped (one bridge for the
 *    whole app) so the dialog result still lands when the host Activity is recreated mid-dialog.
 */
class AndroidSmsRoleCoordinator(
    context: Context,
    private val gateway: SmsRoleGateway = AndroidSmsRoleGateway(context),
    private val ledger: SmsRoleLedger = SmsRoleLedger(File(context.filesDir, LEDGER_FILE)),
) : SmsRoleCoordinator {

    private val roleManager: RoleManager? = context.getSystemService(RoleManager::class.java)

    // The await/timeout bridge is an internal impl detail (unit-tested in isolation), so it is a
    // private property rather than a public-constructor parameter exposing an internal type.
    private val grant = InteractiveGrant(ROLE_DIALOG_TIMEOUT_MS)

    /** Set by the host Activity: launches the role-request intent. */
    var requestLauncher: ((Intent) -> Unit)? = null

    // Snapshotted once on priorDefaultPackage() so acquire/arm reuse a single read (no TOCTOU).
    private var priorSnapshot: String? = null

    override fun priorDefaultPackage(): String? = gateway.currentDefault().also { priorSnapshot = it }

    override suspend fun acquireRole(): Boolean {
        val rm = roleManager ?: return false
        if (!rm.isRoleAvailable(RoleManager.ROLE_SMS)) return false
        if (rm.isRoleHeld(RoleManager.ROLE_SMS)) {
            ledger.arm(priorSnapshot)
            return true
        }
        val launch = requestLauncher ?: return false
        val intent = runCatching { rm.createRequestRoleIntent(RoleManager.ROLE_SMS) }.getOrNull() ?: return false
        // InteractiveGrant bounds the await: a never-answered dialog returns false, never hangs.
        val granted = grant.await { launch(intent) }
        if (granted) ledger.arm(priorSnapshot)
        return granted
    }

    /** Called by the host Activity with the role-request ActivityResult outcome. */
    fun onRoleResult(granted: Boolean) = grant.complete(granted)

    override suspend fun relinquishTo(priorPackage: String?) {
        gateway.launchRestore(priorPackage)
        // The ledger stays armed: launchRestore only *prompts*, so the role may still be held.
        // currentStrand()/onRoleRestored() reconcile against the real state on the next launch.
    }

    override fun currentStrand(): SmsRoleStrand? =
        if (gateway.isSelfDefault()) SmsRoleStrand(ledger.prior()) else null

    override fun onRoleRestored() = ledger.disarm()

    companion object {
        /** App-private marker file for the process-death safety net. */
        const val LEDGER_FILE = "sms-role.ledger"

        /**
         * Cap on awaiting the system role dialog. Generous (a user may read it), but finite — a
         * never-answered dialog must not hang the transfer. 2 minutes.
         */
        const val ROLE_DIALOG_TIMEOUT_MS = 120_000L
    }
}

/**
 * Process-scoped holder for the one role bridge. The host Activity is recreated on a config
 * change (rotation, dark-mode, locale) — including mid role-dialog — but the ViewModel awaiting
 * [AndroidSmsRoleCoordinator.acquireRole] survives. A per-Activity coordinator would route the
 * dialog result to a fresh instance while the surviving await blocks forever (DEVILS_ADVOCATE.md
 * Q4 stranding). Sharing ONE instance keeps result delivery and the await in sync. Holds only the
 * application context, so there is nothing to leak.
 */
object SmsRoleCoordinatorHolder {
    @Volatile
    private var instance: AndroidSmsRoleCoordinator? = null

    fun get(context: Context): AndroidSmsRoleCoordinator =
        instance ?: synchronized(this) {
            instance ?: AndroidSmsRoleCoordinator(context.applicationContext).also { instance = it }
        }
}
