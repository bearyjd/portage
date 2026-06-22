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

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * The four components Android requires before an app is ELIGIBLE to hold the default-SMS role
 * (RoleManager.ROLE_SMS): an SMS_DELIVER receiver, a WAP_PUSH_DELIVER (MMS) receiver, a compose
 * Activity, and a RESPOND_VIA_MESSAGE service. portage is NOT a messaging app — it requests the
 * role only TRANSIENTLY to write restored messages, then hands it straight back
 * ([SmsRoleCoordinator]). So these are intentionally inert.
 *
 * Tradeoff (surfaced for review): while portage transiently holds the role, an *incoming* SMS or
 * MMS is dropped here rather than written to the provider. The window is a few seconds during an
 * explicit, user-initiated restore, and the role is relinquished immediately after — an accepted
 * v1 limitation for a courier tool, documented in the PR for the security gate.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    // android.provider.Telephony.SMS_DELIVER — inert: portage does not receive live SMS.
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** android.provider.Telephony.WAP_PUSH_DELIVER (MMS) — inert, same rationale as [SmsDeliverReceiver]. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/**
 * Compose target for `sms:`/`mms:` send intents. portage does not compose messages, so it closes
 * immediately — its presence only satisfies the role-eligibility requirement.
 */
class SmsComposeActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

/** RESPOND_VIA_MESSAGE quick-reply service — inert; bound by the platform but does nothing. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
