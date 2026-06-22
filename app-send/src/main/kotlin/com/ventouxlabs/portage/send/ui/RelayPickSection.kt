/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.ventouxlabs.portage.providers.relay.RelayApp
import com.ventouxlabs.portage.providers.relay.RelayCandidate
import com.ventouxlabs.portage.send.relay.AndroidRelayFileResolver
import com.ventouxlabs.portage.send.relay.RelayFile
import com.ventouxlabs.portage.send.ui.theme.LocalSpacing

/**
 * The user-driven relay staging surface on the Home screen (PRP-06 §3-4). For each installed
 * relay-capable app (Signal/Molly/Aegis), it makes clear the user must FIRST export the backup IN
 * the app — portage CANNOT trigger it — then pick that encrypted file via SAF
 * ([ActivityResultContracts.OpenDocument], NO storage permission). Picked files are listed so the
 * user can confirm or remove them before starting the transfer. portage holds only each file's
 * coordinates and a re-open seam; the opaque bytes ride untouched.
 *
 * Hidden entirely when no relay-capable app is installed (the common case) — relay is purely additive
 * and never clutters the landing screen for users who don't have these apps.
 */
@Composable
fun RelayPickSection(
    candidates: List<RelayCandidate>,
    picks: List<RelayFile>,
    onResolvePick: (resolve: () -> RelayFile?) -> Unit,
    onRemovePick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    val s = LocalSpacing.current
    val context = LocalContext.current
    val resolver = AndroidRelayFileResolver(context)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "APP BACKUPS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.sm))
        Text(
            text = "Signal, Molly and Aegis keep their own encrypted backups. Export the backup IN " +
                "the app first, then pick that file here — portage carries it sealed and never sees " +
                "your passphrase.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.md))

        candidates.forEach { candidate ->
            RelayCandidateRow(
                candidate = candidate,
                resolver = resolver,
                onResolvePick = onResolvePick,
            )
        }

        if (picks.isNotEmpty()) {
            Spacer(Modifier.height(s.sm))
            HairlineDivider()
            Spacer(Modifier.height(s.sm))
            picks.forEach { pick ->
                PickedRelayRow(pick = pick, onRemove = { onRemovePick(pick.pickId) })
            }
        }
        Spacer(Modifier.height(s.lg))
        HairlineDivider()
    }
}

@Composable
private fun RelayCandidateRow(
    candidate: RelayCandidate,
    resolver: AndroidRelayFileResolver,
    onResolvePick: (resolve: () -> RelayFile?) -> Unit,
) {
    val s = LocalSpacing.current
    // SAF document pick — user-mediated, NO storage permission. Any MIME so Signal's backup, Aegis's
    // JSON, etc. are all selectable; portage never inspects the type, only ferries the bytes.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Hand the resolve work to the VM so the SIZE-absent whole-file read runs off the main
            // thread (Dispatchers.IO) — the picker callback returns immediately.
            onResolvePick { resolver.resolve(uri, candidate.app, candidate.targetPackage) }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = s.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = candidate.app.appLabel(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        SwissTextAction(text = "Pick file", onClick = { launcher.launch(arrayOf("*/*")) })
    }
}

@Composable
private fun PickedRelayRow(pick: RelayFile, onRemove: () -> Unit) {
    val s = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = s.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.7f)) {
            Text(
                text = "${pick.app.appLabel()} · ${pick.originalName}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Expired = the read grant was lost (process death / revoke) so this file did NOT ship.
            // Surface it loudly instead of letting the item silently self-omit.
            if (pick.expired) {
                Text(
                    text = "Expired — re-pick this file",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = formatBytes(pick.byteLength),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SwissTextAction(text = "Remove", onClick = onRemove)
    }
}

/** Display label for a relay app (display-only; never used for dispatch — the enum is the key). */
private fun RelayApp.appLabel(): String = when (this) {
    RelayApp.SIGNAL -> "Signal"
    RelayApp.MOLLY -> "Molly"
    RelayApp.AEGIS -> "Aegis"
    RelayApp.OTHER -> "App backup"
}
