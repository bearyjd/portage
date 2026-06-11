/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.settings

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProvider
import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.providers.wire.JsonLines
import cc.grepon.portage.settings.Classification
import cc.grepon.portage.settings.Namespace
import cc.grepon.portage.settings.Reach
import cc.grepon.portage.settings.SettingKey
import cc.grepon.portage.settings.SettingsAllowlist
import cc.grepon.portage.settings.accepts
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/** One settings key/value pair on the wire. Tier-0 carries SYSTEM-namespace keys only. */
@Serializable
data class SettingEntry(val name: String, val value: String)

@Serializable
data class SettingsSnapshot(val entries: List<SettingEntry>)

/** JSON (de)serialization for the snapshot — kept separate so tests can frame payloads. */
object SettingsCodec {
    fun encode(snapshot: SettingsSnapshot): String =
        JsonLines.format.encodeToString(SettingsSnapshot.serializer(), snapshot)

    fun decode(source: InputStream): SettingsSnapshot? = runCatching {
        JsonLines.format.decodeFromString(
            SettingsSnapshot.serializer(),
            source.bufferedReader(Charsets.UTF_8).readText(),
        )
    }.getOrNull()
}

/**
 * The Settings.System seam. Reading needs no permission; writing needs the user-granted
 * "Modify system settings" special access ([canWrite], ACTION_MANAGE_WRITE_SETTINGS).
 */
interface SystemSettingsStore {
    fun read(name: String): String?
    fun canWrite(): Boolean
    fun write(name: String, value: String): Boolean
}

/** The Tier-0 settings cut: SAFE, SYSTEM-namespace, writable without any privilege bridge. */
private fun tier0SafeKeys(): List<SettingKey> = SettingsAllowlist.all.filter {
    it.classification == Classification.SAFE &&
        it.namespace == Namespace.SYSTEM &&
        it.reach == Reach.T0_SYSTEM
}

/** Sender side: read the SAFE Tier-0 system keys that have a value on this device. */
class SettingsExportProvider(private val store: SystemSettingsStore) : ExportProvider {

    override val kind = ItemKind.SETTINGS
    override val displayName = "Device settings"
    override val group = "Settings"

    private fun snapshot(): SettingsSnapshot = SettingsSnapshot(
        tier0SafeKeys().mapNotNull { key ->
            runCatching { store.read(key.name) }.getOrNull()?.let { SettingEntry(key.name, it) }
        },
    )

    override suspend fun available(): Boolean =
        runCatching { snapshot().entries.isNotEmpty() }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val writer = sink.bufferedWriter(Charsets.UTF_8)
        writer.write(SettingsCodec.encode(snapshot()))
        writer.flush()
    }
}

/**
 * Receiver side: apply a key ONLY if every gate passes — present in the compiled allowlist
 * under the SYSTEM namespace, classified SAFE, reachable at Tier 0, and the value accepted
 * by the catalog validator. The sender's payload can never introduce keys
 * (THREAT_MODEL.md, malicious-sender row; DEVILS_ADVOCATE Q2). A failed key is a per-key
 * skip, never a transport error (PROTOCOL.md §4).
 */
class SettingsApplyProvider(private val store: SystemSettingsStore) : ApplyProvider {

    override val kind = ItemKind.SETTINGS

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val snapshot = SettingsCodec.decode(source)
            ?: return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable settings payload")

        if (!runCatching { store.canWrite() }.getOrDefault(false)) {
            return ApplyOutcome(
                ItemStatus.SKIPPED,
                "needs the 'Modify system settings' grant",
            )
        }

        var applied = 0
        var skipped = 0
        for (entry in snapshot.entries) {
            val key = SettingsAllowlist.byName(entry.name, Namespace.SYSTEM)
            val admissible = key != null &&
                key.classification == Classification.SAFE &&
                key.reach == Reach.T0_SYSTEM &&
                key.validator.accepts(entry.value)
            if (admissible && runCatching { store.write(entry.name, entry.value) }.getOrDefault(false)) {
                applied++
            } else {
                skipped++
            }
        }
        return ApplyOutcome(ItemStatus.OK, "applied $applied, skipped $skipped")
    }
}
