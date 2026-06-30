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

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Thin adapter over Android's public MMS provider tables. Export/restore is limited to inbox and
 * sent history. It preserves message metadata, address rows, and text/binary parts, but does not
 * attempt RCS state, carrier/service-center state, drafts, pending sends, or thread-id fidelity.
 */
class AndroidMmsStore(private val resolver: ContentResolver) : MmsStore {

    private val exportSelection = "$COL_BOX IN ($BOX_INBOX,$BOX_SENT)"

    override fun count(): Int =
        resolver.query(MMS_URI, arrayOf(COL_ID), exportSelection, null, null)
            ?.use { it.count } ?: 0

    override fun writeAllTo(sink: OutputStream, maxBytes: Long): MmsExportSummary {
        var exported = 0
        var skipped = 0
        var written = 0L
        resolver.query(
            MMS_URI,
            MESSAGE_PROJECTION,
            exportSelection,
            null,
            "$COL_DATE ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val record = runCatching { cursor.toMmsRecord() }.getOrNull()
                if (record == null) {
                    skipped++
                    continue
                }
                val line = MmsWire.encodedLine(record)
                if (line.size.toLong() > maxBytes || written + line.size > maxBytes) {
                    skipped++
                    continue
                }
                sink.write(line)
                written += line.size
                exported++
            }
        }
        sink.flush()
        return MmsExportSummary(exported = exported, skipped = skipped, bytes = written)
    }

    override fun insert(record: MmsRecord): Boolean {
        return try {
            val messageUri = resolver.insert(MMS_URI, record.toContentValues()) ?: return false
            val messageId = ContentUris.parseId(messageUri)
            var ok = true
            for (address in record.addresses) {
                ok = insertAddress(messageId, address) && ok
            }
            for (part in record.parts) {
                ok = insertPart(messageId, part) && ok
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "MMS provider insert failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun readAddresses(messageId: Long): List<MmsAddressRecord> {
        val addresses = mutableListOf<MmsAddressRecord>()
        resolver.query(addrUri(messageId), ADDRESS_PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                addresses += MmsAddressRecord(
                    address = address,
                    type = cursor.getInt(1),
                    charset = cursor.getNullableInt(2),
                )
            }
        }
        return addresses
    }

    private fun android.database.Cursor.toMmsRecord(): MmsRecord {
        val id = getLong(0)
        return MmsRecord(
            dateSeconds = getLong(1),
            box = getInt(2),
            read = getInt(3) == 1,
            subject = getString(4),
            subjectCharset = getNullableInt(5),
            contentType = getString(6),
            messageClass = getString(7),
            messageType = getNullableInt(8),
            mmsVersion = getNullableInt(9),
            priority = getNullableInt(10),
            readReport = getNullableInt(11),
            deliveryReport = getNullableInt(12),
            seen = getInt(13) == 1,
            addresses = readAddresses(id),
            parts = readParts(id),
        )
    }

    private fun readParts(messageId: Long): List<MmsPartRecord> {
        val parts = mutableListOf<MmsPartRecord>()
        var remainingRawBytes = MAX_RECORD_RAW_BYTES
        resolver.query(partUri(messageId), PART_PROJECTION, null, null, "$COL_PART_SEQ ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val partId = cursor.getLong(0)
                val contentType = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val dataMarker = cursor.getString(9)
                val data = if (dataMarker != null) {
                    val partData = readPartData(partId, minOf(MAX_PART_BYTES, remainingRawBytes)) ?: continue
                    remainingRawBytes -= partData.rawBytes
                    partData.base64
                } else {
                    null
                }
                parts += MmsPartRecord(
                    contentType = contentType,
                    text = cursor.getString(2),
                    dataBase64 = data,
                    charset = cursor.getNullableInt(3),
                    name = cursor.getString(4),
                    fileName = cursor.getString(5),
                    contentId = cursor.getString(6),
                    contentLocation = cursor.getString(7),
                    contentDisposition = cursor.getString(8),
                    sequence = cursor.getNullableInt(10),
                )
            }
        }
        return parts
    }

    private fun readPartData(partId: Long, maxBytes: Int): PartData? =
        runCatching {
            resolver.openInputStream(partDataUri(partId))?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > maxBytes) return@runCatching null
                    out.write(buffer, 0, read)
                }
                PartData(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), total)
            }
        }.getOrNull()

    private fun insertAddress(messageId: Long, address: MmsAddressRecord): Boolean =
        runCatching {
            resolver.insert(addrUri(messageId), ContentValues().apply {
                put(COL_ADDR_ADDRESS, address.address)
                put(COL_ADDR_TYPE, address.type)
                address.charset?.let { put(COL_ADDR_CHARSET, it) }
            }) != null
        }.getOrDefault(false)

    private fun insertPart(messageId: Long, part: MmsPartRecord): Boolean =
        runCatching {
            val inserted = resolver.insert(partUri(messageId), part.toContentValues()) ?: return false
            val data = part.dataBase64
            if (data != null) {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                resolver.openOutputStream(inserted)?.use { it.write(bytes) } ?: return false
            }
            true
        }.getOrDefault(false)

    private fun MmsRecord.toContentValues(): ContentValues = ContentValues().apply {
        put(COL_DATE, dateSeconds)
        put(COL_BOX, box)
        put(COL_READ, if (read) 1 else 0)
        put(COL_SEEN, if (seen) 1 else 0)
        put(COL_SUBJECT, subject)
        subjectCharset?.let { put(COL_SUBJECT_CHARSET, it) }
        put(COL_CONTENT_TYPE, contentType ?: DEFAULT_MMS_CONTENT_TYPE)
        put(COL_MESSAGE_CLASS, messageClass ?: DEFAULT_MESSAGE_CLASS)
        put(COL_MESSAGE_TYPE, messageType ?: DEFAULT_RETRIEVE_CONF)
        put(COL_MMS_VERSION, mmsVersion ?: DEFAULT_MMS_VERSION)
        priority?.let { put(COL_PRIORITY, it) }
        readReport?.let { put(COL_READ_REPORT, it) }
        deliveryReport?.let { put(COL_DELIVERY_REPORT, it) }
    }

    private fun MmsPartRecord.toContentValues(): ContentValues = ContentValues().apply {
        put(COL_PART_CONTENT_TYPE, contentType)
        put(COL_PART_TEXT, text)
        charset?.let { put(COL_PART_CHARSET, it) }
        name?.let { put(COL_PART_NAME, it) }
        fileName?.let { put(COL_PART_FILENAME, it) }
        contentId?.let { put(COL_PART_CONTENT_ID, it) }
        contentLocation?.let { put(COL_PART_CONTENT_LOCATION, it) }
        contentDisposition?.let { put(COL_PART_CONTENT_DISPOSITION, it) }
        sequence?.let { put(COL_PART_SEQ, it) }
    }

    private fun android.database.Cursor.getNullableInt(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private data class PartData(val base64: String, val rawBytes: Int)

    private companion object {
        const val TAG = "PortageMms"
        val MMS_URI: Uri = Uri.parse("content://mms")
        const val BOX_INBOX = 1
        const val BOX_SENT = 2
        const val DEFAULT_RETRIEVE_CONF = 132
        const val DEFAULT_MMS_VERSION = 18
        const val DEFAULT_MESSAGE_CLASS = "personal"
        const val DEFAULT_MMS_CONTENT_TYPE = "application/vnd.wap.multipart.related"
        // Keep sender memory bounded before base64 expansion. Larger video-style MMS parts are
        // skipped in v1 rather than producing a payload the receiver will reject or the sender
        // cannot stage safely.
        const val MAX_PART_BYTES = 8 * 1024 * 1024
        const val MAX_RECORD_RAW_BYTES = 16 * 1024 * 1024

        const val COL_ID = "_id"
        const val COL_DATE = "date"
        const val COL_BOX = "msg_box"
        const val COL_READ = "read"
        const val COL_SEEN = "seen"
        const val COL_SUBJECT = "sub"
        const val COL_SUBJECT_CHARSET = "sub_cs"
        const val COL_CONTENT_TYPE = "ct_t"
        const val COL_MESSAGE_CLASS = "m_cls"
        const val COL_MESSAGE_TYPE = "m_type"
        const val COL_MMS_VERSION = "v"
        const val COL_PRIORITY = "pri"
        const val COL_READ_REPORT = "rr"
        const val COL_DELIVERY_REPORT = "d_rpt"

        const val COL_ADDR_ADDRESS = "address"
        const val COL_ADDR_TYPE = "type"
        const val COL_ADDR_CHARSET = "charset"

        const val COL_PART_ID = "_id"
        const val COL_PART_CONTENT_TYPE = "ct"
        const val COL_PART_TEXT = "text"
        const val COL_PART_CHARSET = "chset"
        const val COL_PART_NAME = "name"
        const val COL_PART_FILENAME = "fn"
        const val COL_PART_CONTENT_ID = "cid"
        const val COL_PART_CONTENT_LOCATION = "cl"
        const val COL_PART_CONTENT_DISPOSITION = "cd"
        const val COL_PART_DATA = "_data"
        const val COL_PART_SEQ = "seq"

        val MESSAGE_PROJECTION = arrayOf(
            COL_ID, COL_DATE, COL_BOX, COL_READ, COL_SUBJECT, COL_SUBJECT_CHARSET,
            COL_CONTENT_TYPE, COL_MESSAGE_CLASS, COL_MESSAGE_TYPE, COL_MMS_VERSION,
            COL_PRIORITY, COL_READ_REPORT, COL_DELIVERY_REPORT, COL_SEEN,
        )
        val ADDRESS_PROJECTION = arrayOf(COL_ADDR_ADDRESS, COL_ADDR_TYPE, COL_ADDR_CHARSET)
        val PART_PROJECTION = arrayOf(
            COL_PART_ID, COL_PART_CONTENT_TYPE, COL_PART_TEXT, COL_PART_CHARSET,
            COL_PART_NAME, COL_PART_FILENAME, COL_PART_CONTENT_ID, COL_PART_CONTENT_LOCATION,
            COL_PART_CONTENT_DISPOSITION, COL_PART_DATA, COL_PART_SEQ,
        )

        fun addrUri(messageId: Long): Uri = Uri.parse("content://mms/$messageId/addr")
        fun partUri(messageId: Long): Uri = Uri.parse("content://mms/$messageId/part")
        fun partDataUri(partId: Long): Uri = Uri.parse("content://mms/part/$partId")
    }
}
