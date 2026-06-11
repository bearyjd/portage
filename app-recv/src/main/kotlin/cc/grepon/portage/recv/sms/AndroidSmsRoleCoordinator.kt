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

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import cc.grepon.portage.providers.sms.AndroidSmsRoleGateway
import cc.grepon.portage.providers.sms.SmsRoleGateway
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges the interactive ROLE_SMS grant into a suspend call. [acquireRole] launches the
 * platform role dialog through [requestLauncher] (wired by the host Activity to an
 * ActivityResultLauncher) and suspends on a [CompletableDeferred] until the Activity routes
 * the result back via [onRoleResult]. Prior-holder bookkeeping and the relinquish prompt
 * delegate to the providers-layer [SmsRoleGateway], the single source of truth for the
 * default-SMS state, so this class only adds the acquisition step.
 */
class AndroidSmsRoleCoordinator(
    context: Context,
    private val gateway: SmsRoleGateway = AndroidSmsRoleGateway(context),
) : SmsRoleCoordinator {

    private val roleManager: RoleManager? = context.getSystemService(RoleManager::class.java)

    /** Set by the host Activity: launches the role-request intent. */
    var requestLauncher: ((Intent) -> Unit)? = null

    private var pending: CompletableDeferred<Boolean>? = null

    override fun priorDefaultPackage(): String? = gateway.currentDefault()

    override suspend fun acquireRole(): Boolean {
        val rm = roleManager ?: return false
        if (!rm.isRoleAvailable(RoleManager.ROLE_SMS)) return false
        if (rm.isRoleHeld(RoleManager.ROLE_SMS)) return true
        val launch = requestLauncher ?: return false
        val intent = runCatching { rm.createRequestRoleIntent(RoleManager.ROLE_SMS) }.getOrNull() ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        launch(intent)
        return deferred.await()
    }

    /** Called by the host Activity with the role-request ActivityResult outcome. */
    fun onRoleResult(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    override suspend fun relinquishTo(priorPackage: String?) {
        gateway.launchRestore(priorPackage)
    }
}
