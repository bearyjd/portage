/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.mms

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.providers.ApplyOutcome
import com.ventouxlabs.portage.providers.ApplyProvider
import com.ventouxlabs.portage.providers.ExportProvider
import com.ventouxlabs.portage.providers.sms.SmsRoleGateway
import com.ventouxlabs.portage.providers.wire.JsonLines
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class MmsAddressRecord(
    val address: String,
    val type: Int,
    val charset: Int? = null,
)

@Serializable
data class MmsPartRecord(
    val contentType: String,
    val text: String? = null,
    val dataBase64: String? = null,
    val charset: Int? = null,
    val name: String? = null,
    val fileName: String? = null,
    val contentId: String? = null,
    val contentLocation: String? = null,
    val contentDisposition: String? = null,
    val sequence: Int? = null,
)

@Serializable
data class MmsRecord(
    val dateSeconds: Long,
    val box: Int,
    val read: Boolean = true,
    val seen: Boolean = true,
    val subject: String? = null,
    val subjectCharset: Int? = null,
    val contentType: String? = null,
    val messageClass: String? = null,
    val messageType: Int? = null,
    val mmsVersion: Int? = null,
    val priority: Int? = null,
    val readReport: Int? = null,
    val deliveryReport: Int? = null,
    val addresses: List<MmsAddressRecord> = emptyList(),
    val parts: List<MmsPartRecord> = emptyList(),
)

interface MmsStore {
    fun count(): Int
    fun writeAllTo(sink: OutputStream, maxBytes: Long = MmsWire.MAX_ITEM_BYTES): MmsExportSummary
    fun insert(record: MmsRecord): Boolean
}

data class MmsExportSummary(
    val exported: Int,
    val skipped: Int,
    val bytes: Long,
)

object MmsWire {
    /**
     * MMS stays under the receiver's default Tier-0 item cap. The exporter enforces this before
     * manifest staging so the receiver does not advertise/apply an item it will reject as OVERSIZE.
     */
    const val MAX_ITEM_BYTES = 64L * 1024 * 1024

    fun encodedLine(record: MmsRecord): ByteArray =
        (JsonLines.format.encodeToString(record) + "\n").toByteArray(Charsets.UTF_8)

    fun writeBounded(
        records: Sequence<MmsRecord>,
        sink: OutputStream,
        maxBytes: Long = MAX_ITEM_BYTES,
    ): MmsExportSummary {
        var exported = 0
        var skipped = 0
        var written = 0L
        for (record in records) {
            val line = encodedLine(record)
            if (line.size.toLong() > maxBytes || written + line.size > maxBytes) {
                skipped++
                continue
            }
            sink.write(line)
            written += line.size
            exported++
        }
        sink.flush()
        return MmsExportSummary(exported = exported, skipped = skipped, bytes = written)
    }
}

class MmsExportProvider(private val store: MmsStore) : ExportProvider {
    override val kind = ItemKind.MMS
    override val displayName = "MMS messages"
    override val group = "History"

    override suspend fun available(): Boolean =
        runCatching { store.count() > 0 }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        runCatching { store.writeAllTo(sink, MmsWire.MAX_ITEM_BYTES) }
    }
}

class MmsApplyProvider(
    private val store: MmsStore,
    private val roleGateway: SmsRoleGateway,
) : ApplyProvider {
    override val kind = ItemKind.MMS

    override suspend fun apply(source: InputStream): ApplyOutcome {
        if (!roleGateway.isSelfDefault()) {
            return ApplyOutcome(ItemStatus.SKIPPED, "MMS restore needs the temporary default-SMS role")
        }
        val parsed = JsonLines.readFrom<MmsRecord>(source)
        var written = 0
        var skipped = parsed.malformed
        for (record in parsed.records) {
            if (runCatching { store.insert(record) }.getOrDefault(false)) written++ else skipped++
        }
        val status = if (parsed.records.isNotEmpty() && written == 0) ItemStatus.WRITE_ERROR else ItemStatus.OK
        return ApplyOutcome(status, "imported $written MMS; skipped $skipped")
    }
}
