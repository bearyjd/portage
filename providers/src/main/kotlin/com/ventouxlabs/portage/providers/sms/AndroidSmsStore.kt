/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.sms

import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony
import android.util.Log

/**
 * Thin Telephony.Sms adapter behind [SmsStore]. Exports inbox + sent only (drafts and
 * queued outbox are transient state, not history). Reads propagate [SecurityException];
 * writes return false on failure.
 */
class AndroidSmsStore(private val resolver: ContentResolver) : SmsStore {

    private val exportSelection =
        "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX},${Telephony.Sms.MESSAGE_TYPE_SENT})"

    override fun count(): Int =
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), exportSelection, null, null)
            ?.use { it.count } ?: 0

    override fun readAll(): List<SmsRecord> {
        val projection = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.TYPE, Telephony.Sms.READ,
        )
        val records = mutableListOf<SmsRecord>()
        resolver.query(
            Telephony.Sms.CONTENT_URI, projection, exportSelection, null, "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val body = cursor.getString(1) ?: continue
                records += SmsRecord(
                    address = cursor.getString(0).orEmpty(),
                    body = body,
                    dateMillis = cursor.getLong(2),
                    type = cursor.getInt(3),
                    read = cursor.getInt(4) == 1,
                )
            }
        }
        return records
    }

    override fun insert(record: SmsRecord): Boolean {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, record.address)
            put(Telephony.Sms.BODY, record.body)
            put(Telephony.Sms.DATE, record.dateMillis)
            put(Telephony.Sms.TYPE, record.type)
            put(Telephony.Sms.READ, if (record.read) 1 else 0)
            put(Telephony.Sms.SEEN, 1)
        }
        return try {
            val uri = resolver.insert(Telephony.Sms.CONTENT_URI, values)
            if (uri == null) Log.w(TAG, "SMS provider returned no URI for an insert")
            uri != null
        } catch (t: Throwable) {
            // Never log message/address values. The exception class and platform reason are enough
            // to distinguish role propagation, app-op, and provider failures on hardware.
            Log.w(TAG, "SMS provider insert failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private companion object {
        const val TAG = "PortageSms"
    }
}

/**
 * Role seam implementation. On Android 10+ a third-party app can request the SMS role only
 * for itself, so the teardown is a guided user action: we fire the platform's
 * change-default prompt aimed at the prior holder, falling back to the default-apps
 * settings screen. The prompt itself is the relinquish — portage cannot (and must not)
 * silently reassign roles.
 */
class AndroidSmsRoleGateway(private val context: Context) : SmsRoleGateway {
    private val roleManager: RoleManager? = context.getSystemService(RoleManager::class.java)

    override fun isSelfDefault(): Boolean =
        roleManager?.isRoleHeld(RoleManager.ROLE_SMS)
            ?: (Telephony.Sms.getDefaultSmsPackage(context) == context.packageName)

    override fun currentDefault(): String? = Telephony.Sms.getDefaultSmsPackage(context)

    override fun launchRestore(priorHolderPackage: String?): Boolean {
        // ACTION_CHANGE_DEFAULT has been unsupported since Android 10. RoleManager can request the
        // role only for the calling app, not give it to [priorHolderPackage], so the supported
        // teardown is the system default-apps screen plus Portage's persistent restore banner.
        val settings = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(settings) }.isSuccess
    }

}
