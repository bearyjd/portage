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

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.wire.JsonLines
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * One SMS. [type] uses the Telephony.Sms MESSAGE_TYPE constants verbatim
 * (1=inbox, 2=sent, …) — passed through, never interpreted.
 */
@Serializable
data class SmsRecord(
    val address: String,
    val body: String,
    val dateMillis: Long,
    val type: Int,
    val read: Boolean = true,
)

/**
 * The ContentResolver seam for SMS. Reads MAY throw [SecurityException] when READ_SMS is
 * denied. Inserts only succeed while this app holds the default-SMS role; outside the role
 * the platform silently drops or rejects them — which is why [SmsApplyProvider] hard-gates
 * on the role instead of trusting the write.
 */
interface SmsStore {
    fun count(): Int
    fun readAll(): List<SmsRecord>
    fun insert(record: SmsRecord): Boolean
}

/**
 * The default-SMS-app role seam. On Android 10+ an app can request the role only for
 * ITSELF, so "relinquish" is necessarily a guided user action: [launchRestore] surfaces
 * the system UI pointing back at the prior holder. See DEVILS_ADVOCATE.md Q4.
 */
interface SmsRoleGateway {
    fun isSelfDefault(): Boolean
    fun currentDefault(): String?

    /** Fire the restore prompt toward [priorHolderPackage]. True if a prompt launched. */
    fun launchRestore(priorHolderPackage: String?): Boolean
}

/** Sender side: SMS → JSON lines. Denied permission ⇒ unavailable, empty export. */
class SmsExportProvider(private val store: SmsStore) : ExportProvider {

    override val kind = ItemKind.SMS
    override val displayName = "Text messages"
    override val group = "History"

    override suspend fun available(): Boolean =
        runCatching { store.count() > 0 }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val records = runCatching { store.readAll() }.getOrDefault(emptyList())
        JsonLines.writeTo(sink, records)
    }
}

/**
 * Receiver side: JSON lines → SMS provider rows. HARD-GATED on holding the default-SMS role —
 * outside the role this self-skips and writes nothing. Acquiring and relinquishing the role is
 * orchestrated receiver-side by the SmsRoleCoordinator (app-recv), which wraps the whole transfer
 * in `acquire → apply → finally relinquish` (plus a persistent strand backstop for process death)
 * so that a declined role still lets the other, non-SMS items through.
 */
class SmsApplyProvider(
    private val store: SmsStore,
    private val roleGateway: SmsRoleGateway,
) : ApplyProvider {

    override val kind = ItemKind.SMS

    override suspend fun apply(source: InputStream): ApplyOutcome {
        if (!roleGateway.isSelfDefault()) {
            return ApplyOutcome(
                ItemStatus.SKIPPED,
                "not the default SMS app — restore needs the one-time handoff",
            )
        }
        val parsed = JsonLines.readFrom<SmsRecord>(source)
        var applied = 0
        var skipped = parsed.malformed
        for (record in parsed.records) {
            if (runCatching { store.insert(record) }.getOrDefault(false)) applied++ else skipped++
        }
        val status = if (parsed.records.isNotEmpty() && applied == 0) ItemStatus.WRITE_ERROR else ItemStatus.OK
        return ApplyOutcome(status, "applied $applied, skipped $skipped")
    }
}
