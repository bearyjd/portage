/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.grepon.portage.providers.apk.InstalledApp
import cc.grepon.portage.send.ui.theme.LocalSpacing

/**
 * The "apps to carry" surface on the Home screen (ADR-006 Phase 1b). Lets the user SELECT which
 * installed apps ride along as their own split-APK items. The carry is READ-ONLY (PackageManager + file
 * reads, no privilege) — this screen only drives WHICH apps; selection is sender-side so only chosen
 * apps are staged/hashed/manifested.
 *
 * Design (matching [RelayPickSection]'s Swiss language): a tracked-out section label, a one-line summary
 * that always shows the running selected COUNT and TOTAL size so a multi-GB pick can never surprise the
 * user, and a select-all / clear affordance. Because a phone can have dozens of apps, the list itself is
 * COLLAPSED by default behind a "Choose apps" toggle and, when open, scrolls within a bounded height —
 * it never becomes a wall that buries the Start button. Hidden entirely when no apps are available.
 */
@Composable
fun AppCarrySection(
    apps: List<InstalledApp>,
    selected: Set<String>,
    onToggleApp: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) return
    val s = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }

    val selectedApps = apps.filter { it.packageName in selected }
    val selectedBytes = selectedApps.sumOf { it.totalBytes }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "APPS TO CARRY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwissTextAction(
                text = if (expanded) "Hide" else "Choose apps",
                onClick = { expanded = !expanded },
            )
        }
        Spacer(Modifier.height(s.sm))
        // The running summary is ALWAYS visible (even collapsed) so the user never loses sight of how
        // many apps and how many bytes they have committed to carry.
        Text(
            text = carrySummary(selectedApps.size, apps.size, selectedBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (expanded) {
            Spacer(Modifier.height(s.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(s.lg),
            ) {
                SwissTextAction(text = "Select all", onClick = onSelectAll)
                SwissTextAction(text = "Clear", onClick = onClear)
            }
            Spacer(Modifier.height(s.sm))
            HairlineDivider()
            // Bound the list height so a long app list scrolls inside its own pane instead of pushing
            // Start off-screen — the section stays intentional, never a wall.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                apps.forEach { app ->
                    AppCarryRow(
                        app = app,
                        checked = app.packageName in selected,
                        onToggle = { onToggleApp(app.packageName) },
                    )
                }
            }
            HairlineDivider()
        }
    }
}

@Composable
private fun AppCarryRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    val s = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .heightIn(min = 48.dp)
            .padding(vertical = s.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A square mark (filled when selected) — the Swiss alternative to a Material Checkbox; the whole
        // row is the tap target so selection is one easy click per app. The glyph is suppressed from
        // TalkBack (clearAndSetSemantics) so the state is announced via the parent toggleable's role,
        // not as a literal "[×]" string read aloud.
        Text(
            text = if (checked) "[×]" else "[ ]",
            style = MaterialTheme.typography.titleMedium,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.width(s.md))
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.62f),
        )
        Text(
            text = formatBytes(app.totalBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The always-visible one-liner: how many of the available apps are selected and the running byte total.
 * Pure + JVM-friendly so it reads as a glance value, not accounting. "None selected" when the carry set
 * is empty (the default) so the section never implies a transfer the user did not choose.
 */
internal fun carrySummary(selectedCount: Int, availableCount: Int, selectedBytes: Long): String =
    if (selectedCount == 0) {
        "None selected · $availableCount apps available"
    } else {
        "$selectedCount of $availableCount selected · ${formatBytes(selectedBytes)}"
    }
