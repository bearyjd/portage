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

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import com.ventouxlabs.portage.providers.apk.InstalledApp
import com.ventouxlabs.portage.providers.relay.RelayCandidate
import com.ventouxlabs.portage.send.relay.RelayFile
import com.ventouxlabs.portage.send.ui.theme.LocalSpacing

/** Static device facts shown on the landing screen (computed once by the Activity). */
data class DeviceSummary(
    val deviceName: String,
    val batteryPercent: Int,
    val freeStorage: String,
)

/** The read permissions the exporter asks for up front. Denial degrades, never blocks. */
val SENDER_PERMISSIONS = arrayOf(
    android.Manifest.permission.READ_CONTACTS,
    android.Manifest.permission.READ_CALENDAR,
    android.Manifest.permission.READ_CALL_LOG,
    android.Manifest.permission.READ_SMS,
    // PRP-07: BLUETOOTH_CONNECT gates the PUBLIC getBondedDevices() read — a normal runtime
    // permission, not an escalation. Denial just drops the bonded-roster item from the manifest.
    android.Manifest.permission.BLUETOOTH_CONNECT,
)

/**
 * Landing: this device's name + battery/storage, then "Start transfer". The click asks for
 * the runtime read permissions first; whatever the user grants, the flow proceeds —
 * denied domains simply drop out of the manifest (providers degrade by design).
 */
@Composable
fun HomeScreen(
    summary: DeviceSummary,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    relayCandidates: List<RelayCandidate> = emptyList(),
    relayPicks: List<RelayFile> = emptyList(),
    onResolveRelayPick: (resolve: () -> RelayFile?) -> Unit = {},
    onRemoveRelayPick: (Long) -> Unit = {},
    availableApps: List<InstalledApp> = emptyList(),
    selectedAppPackages: Set<String> = emptySet(),
    onToggleApp: (String) -> Unit = {},
    onSelectAllApps: () -> Unit = {},
    onClearAppSelection: () -> Unit = {},
) {
    val s = LocalSpacing.current
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onStart() }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Scroll so the optional relay section (and its picked-file list) never clips the
            // Start button on shorter screens; centered when content is short.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = s.gutter),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "01 · THIS PHONE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.lg))
        Text(
            text = summary.deviceName,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(s.lg))
        FactRow(label = "BATTERY", value = "${summary.batteryPercent}%")
        FactRow(label = "FREE STORAGE", value = summary.freeStorage)
        Spacer(Modifier.height(s.lg))
        HairlineDivider()
        Spacer(Modifier.height(s.lg))
        Text(
            text = "Carry contacts, calendar, history, your app list and settings to the new phone — over your own Wi-Fi, nothing leaves the room.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // App-backup relay (PRP-06): only renders when Signal/Molly/Aegis is installed. Lets the user
        // pick the encrypted file they exported IN the app so it rides along with the Tier-0 items.
        if (relayCandidates.isNotEmpty()) {
            Spacer(Modifier.height(s.lg))
            HairlineDivider()
            Spacer(Modifier.height(s.lg))
            RelayPickSection(
                candidates = relayCandidates,
                picks = relayPicks,
                onResolvePick = onResolveRelayPick,
                onRemovePick = onRemoveRelayPick,
            )
        }
        // Apps to carry (ADR-006 Phase 1b): only renders when the installed-app seam found apps. Lets the
        // user select which installed apps ride along as their own APK items — default none, clear total.
        if (availableApps.isNotEmpty()) {
            Spacer(Modifier.height(s.lg))
            HairlineDivider()
            Spacer(Modifier.height(s.lg))
            AppCarrySection(
                apps = availableApps,
                selected = selectedAppPackages,
                onToggleApp = onToggleApp,
                onSelectAll = onSelectAllApps,
                onClear = onClearAppSelection,
            )
        }
        Spacer(Modifier.height(s.xl))
        SwissPrimaryButton(
            text = "Start transfer",
            fullWidth = true,
            onClick = {
                val missing = SENDER_PERMISSIONS.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) onStart() else launcher.launch(missing.toTypedArray())
            },
        )
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    val s = LocalSpacing.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(s.sm))
    }
}
