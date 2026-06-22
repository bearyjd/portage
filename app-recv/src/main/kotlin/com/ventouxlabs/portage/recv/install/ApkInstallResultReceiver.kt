/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * The status-receiver for the Tier-0 `PackageInstaller` commit (ADR-006 D3/D6). When
 * [PackageInstallerApkInstaller.commit] dispatches a session over our own carried bytes, the platform
 * reports back here. The ONE reply that needs handling is [PackageInstaller.STATUS_PENDING_USER_ACTION]:
 * the system hands us a confirm [Intent] which we must launch so the user sees the install-confirm dialog
 * — without this the committed session sits pending forever and tapping INSTALL silently does nothing.
 *
 * Terminal statuses (SUCCESS / FAILURE*) are best-effort no-ops: the carried-app set is reconciled and
 * the Done screen already reflects "ready to install", so there is no per-app UI to update from here.
 * Nothing PII-bearing is logged — the status int and package name are the only fields ever read.
 *
 * EXPLICIT same-app broadcast → manifest receiver (declared `exported="false"`): a manifest receiver is
 * the robust pattern here because a runtime receiver registered in an Activity can miss the reply if the
 * Activity is backgrounded while the system prepares the confirm intent.
 */
class ApkInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        when (routeStatus(status, confirm != null)) {
            InstallResultAction.LAUNCH_CONFIRM ->
                // The system-built confirm intent must start a NEW task — it is launched from a
                // BroadcastReceiver context, not an Activity (FLAG_ACTIVITY_NEW_TASK is mandatory).
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            InstallResultAction.NONE -> Unit // terminal SUCCESS/FAILURE — best-effort no-op
        }
    }

    /** What [onReceive] should do for a given status reply (factored out so it is JVM-unit-testable). */
    enum class InstallResultAction {
        /** STATUS_PENDING_USER_ACTION with a confirm intent present — launch the system confirm dialog. */
        LAUNCH_CONFIRM,

        /** A terminal status (SUCCESS / FAILURE*), or a pending-action reply with no confirm intent. */
        NONE,
    }

    companion object {
        /**
         * Pure routing decision (ADR-006 D6): only [PackageInstaller.STATUS_PENDING_USER_ACTION] carrying
         * a confirm intent launches the dialog; everything else (terminal status, or a pending-action
         * reply that somehow lacks the intent) is a no-op. Exposed so the broadcast→confirm routing can be
         * unit-tested without the instrumented broadcast plumbing.
         */
        fun routeStatus(status: Int, hasConfirmIntent: Boolean): InstallResultAction =
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && hasConfirmIntent) {
                InstallResultAction.LAUNCH_CONFIRM
            } else {
                InstallResultAction.NONE
            }
    }
}
