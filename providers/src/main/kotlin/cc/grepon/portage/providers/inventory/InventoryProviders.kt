/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.inventory

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.providers.ApplyOutcome
import cc.grepon.portage.providers.ApplyProvider
import cc.grepon.portage.providers.ExportProvider
import cc.grepon.portage.providers.wire.JsonLines
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/** One user-installed app: identity, version, and which store it came from. */
@Serializable
data class AppRecord(
    val packageName: String,
    val versionCode: Long,
    val installer: String?,
    val label: String,
)

/** The whole inventory as a single JSON document. */
@Serializable
data class AppInventory(val packages: List<AppRecord>)

/** The PackageManager seam. Listing user packages needs no runtime grant on GOS (ADR-001). */
interface InventorySource {
    fun installedUserApps(): List<AppRecord>
    fun installedPackageNames(): Set<String>
}

/** Where an install deep link points. */
enum class InstallStore { PLAY, FDROID, AURORA, UNKNOWN }

/**
 * A reinstall checklist entry: one user tap per app, fired as a VIEW intent on [uri]
 * toward the app's source store. NEVER a silent install (PRP §2 hard constraint) —
 * without Tier 1 each install is a user gesture by design.
 */
data class InstallAction(
    val packageName: String,
    val label: String,
    val store: InstallStore,
    val uri: String,
) {
    companion object {
        fun from(record: AppRecord): InstallAction {
            val store = when (record.installer) {
                "com.android.vending" -> InstallStore.PLAY
                "org.fdroid.fdroid", "org.fdroid.basic" -> InstallStore.FDROID
                "com.aurora.store" -> InstallStore.AURORA
                else -> InstallStore.UNKNOWN
            }
            val uri = when (store) {
                InstallStore.PLAY -> "https://play.google.com/store/apps/details?id=${record.packageName}"
                InstallStore.FDROID -> "https://f-droid.org/packages/${record.packageName}"
                // Aurora registers the market: scheme; unknown installers get the same
                // neutral deep link so whatever store the user runs can claim it.
                InstallStore.AURORA, InstallStore.UNKNOWN -> "market://details?id=${record.packageName}"
            }
            return InstallAction(record.packageName, record.label, store, uri)
        }
    }
}

/** Sender side: installed user packages → one JSON document. */
class AppInventoryExportProvider(private val source: InventorySource) : ExportProvider {

    override val kind = ItemKind.APP_INVENTORY
    override val displayName = "App list"
    override val group = "Apps"

    override suspend fun available(): Boolean =
        runCatching { source.installedUserApps().isNotEmpty() }.getOrDefault(false)

    override suspend fun exportTo(sink: OutputStream) {
        val apps = runCatching { source.installedUserApps() }.getOrDefault(emptyList())
        val writer = sink.bufferedWriter(Charsets.UTF_8)
        writer.write(JsonLines.format.encodeToString(AppInventory.serializer(), AppInventory(apps)))
        writer.flush()
    }
}

/**
 * Receiver side: parse the inventory, drop what's already installed, and hand the
 * remaining [InstallAction]s to the UI via [onActions]. Applying never installs anything
 * itself — it produces the checklist the user taps through.
 */
class AppInventoryApplyProvider(
    private val inventorySource: InventorySource,
    private val onActions: (List<InstallAction>) -> Unit,
) : ApplyProvider {

    override val kind = ItemKind.APP_INVENTORY

    override suspend fun apply(source: InputStream): ApplyOutcome {
        val inventory = runCatching {
            JsonLines.format.decodeFromString(
                AppInventory.serializer(),
                source.bufferedReader(Charsets.UTF_8).readText(),
            )
        }.getOrElse {
            return ApplyOutcome(ItemStatus.WRITE_ERROR, "unreadable app inventory")
        }

        val present = runCatching { inventorySource.installedPackageNames() }.getOrDefault(emptySet())
        val (alreadyInstalled, missing) = inventory.packages.partition { it.packageName in present }
        val actions = missing.map(InstallAction::from)
        onActions(actions)
        return ApplyOutcome(
            ItemStatus.OK,
            "${actions.size} to reinstall, ${alreadyInstalled.size} already installed",
        )
    }
}
