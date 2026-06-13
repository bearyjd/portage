/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send.relay

import cc.grepon.portage.providers.relay.AppBackupRelayExportProvider
import cc.grepon.portage.providers.relay.RelayApp
import java.io.InputStream

/**
 * COURIER, NOT BACKUP (PRP-06 §2). The user-driven staging path on the sender. portage CANNOT trigger
 * an app's backup export — Signal/Molly/Aegis deny programmatic backup by design — so the USER first
 * exports the encrypted file IN the app, then points portage at it via SAF. This file turns those
 * user picks into [AppBackupRelayExportProvider]s; it reuses that provider's codec verbatim and never
 * reimplements or interprets the opaque bytes.
 */

/**
 * One user-picked, app-encrypted backup file staged for relay. Pure value: it carries the OPAQUE
 * file's coordinates and a re-open seam ([openStream]) — never the bytes, never the passphrase. The
 * Android layer (the Compose SAF picker + ContentResolver) produces these; the ViewModel stays
 * JVM-testable because [openStream]/[byteLength] are plain lambdas/values, not a Context.
 *
 * [pickId] is a stable per-pick key for the UI list (SAF can return the same content Uri twice; the
 * id keeps two picks distinct so the list never collapses or throws a Compose duplicate-key error).
 * [app] is the typed target (derives the package the receiver re-links to); [targetPackage] is the
 * advisory package the receiver re-validates against [app]. [originalName] is display-only (never a
 * path). [restoreNote] is the human reminder shown on the new phone — defaulted per app
 * ([RelayRestoreNotes]) but always carried so the receiver's blank-note gate is satisfied.
 */
data class RelayFile(
    val pickId: Long,
    val app: RelayApp,
    val targetPackage: String,
    val originalName: String,
    val restoreNote: String,
    val byteLength: Long,
    val openStream: () -> InputStream,
)

/**
 * Per-app default restore note shown to the user on the NEW phone (PRP-06 §4). portage never holds
 * the passphrase, so each note makes the user-only secret explicit. These are display strings, not
 * protocol — safe to evolve without a wire change. Bounded well under [RelayHeader.MAX_NOTE_LENGTH].
 */
object RelayRestoreNotes {
    fun defaultFor(app: RelayApp): String = when (app) {
        RelayApp.SIGNAL ->
            "Open Signal on the new phone and restore from this file with your 30-digit passphrase."
        RelayApp.MOLLY ->
            "Open Molly on the new phone and restore from this file with your passphrase."
        RelayApp.AEGIS ->
            "Open Aegis on the new phone, choose Import, and unlock this file with your password."
        RelayApp.OTHER ->
            "Open the matching app on the new phone and import this encrypted file with your passphrase."
    }
}

/**
 * Turn the user's picked relay files into [AppBackupRelayExportProvider]s, ready to append to the
 * sender's provider list so [cc.grepon.portage.send.transfer.ManifestBuilder] stages each as its own
 * item (distinct item id + distinct staging file, exactly like every other provider). Each pick
 * becomes one provider; multiple picks (e.g. Signal AND Aegis) stay distinct because each provider is
 * a separate manifest item. A blank/missing pick self-omits: the provider's available() returns false
 * for an empty file or blank note, so a half-finished pick never enters the manifest.
 */
fun relayExportProviders(picks: List<RelayFile>): List<AppBackupRelayExportProvider> =
    picks.map { pick ->
        AppBackupRelayExportProvider(
            app = pick.app,
            targetPackage = pick.targetPackage,
            originalName = pick.originalName,
            restoreNote = pick.restoreNote,
            openPickedFile = pick.openStream,
            pickedFileLength = pick.byteLength,
        )
    }
