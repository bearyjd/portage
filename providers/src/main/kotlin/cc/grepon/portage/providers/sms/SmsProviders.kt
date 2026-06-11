/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.sms

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProvider
import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.providers.wire.JsonLines
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
 * Receiver side: JSON lines → SMS provider rows. HARD-GATED on holding the default-SMS
 * role; orchestration of acquire → apply → relinquish belongs to [SmsHandoff].
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

    /** Record who holds the role BEFORE acquiring it — the teardown target. */
    suspend fun recordPriorDefault(): String? = roleGateway.currentDefault()

    /** Idempotent teardown: prompt the user to hand the role back to [priorHolderPackage]. */
    suspend fun relinquishTo(priorHolderPackage: String?) {
        roleGateway.launchRestore(priorHolderPackage)
    }
}

/**
 * The handoff state machine, pure and testable: record prior holder → acquire role (user
 * gesture) → apply → ALWAYS relinquish toward the recorded holder. Relinquish runs in a
 * `finally` so no apply outcome — success, failure, or throw — can strand the user with
 * portage as their default SMS app (DEVILS_ADVOCATE.md Q4: required, not optional).
 */
object SmsHandoff {

    suspend fun run(
        recordPrior: suspend () -> String?,
        acquire: suspend () -> Boolean,
        apply: suspend () -> ApplyOutcome,
        relinquish: suspend (String?) -> Unit,
    ): ApplyOutcome {
        val prior = recordPrior()
        if (!acquire()) {
            // Deliberately OUTSIDE the finally: the role was never taken, so there is
            // nothing to give back and firing a restore prompt would be noise.
            return ApplyOutcome(ItemStatus.SKIPPED, "default-SMS-app handoff declined")
        }
        return try {
            apply()
        } finally {
            relinquish(prior)
        }
    }
}
