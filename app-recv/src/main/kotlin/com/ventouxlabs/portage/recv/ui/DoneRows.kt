/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ventouxlabs.portage.providers.permission.PermissionAllowlist
import com.ventouxlabs.portage.recv.FailedItem
import com.ventouxlabs.portage.recv.OptInPermissions
import com.ventouxlabs.portage.recv.RestoredPermissions
import com.ventouxlabs.portage.recv.ui.theme.LocalSpacing

/** One restored-permissions row: the carried app over the friendly names of what was switched back on. */
@Composable
internal fun RestoredPermissionsRow(restored: RestoredPermissions) {
    val s = LocalSpacing.current
    Column(Modifier.padding(vertical = s.md)) {
        Text(
            text = restored.packageName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(s.xs))
        Text(
            text = restored.permissions.joinToString(", ") { friendlyPermissionName(it) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One failed item: display name + human reason + optional wire detail, with the verdict word right-aligned. */
@Composable
internal fun FailedItemRow(item: FailedItem) {
    val s = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = s.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = s.md)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            statusReason(item.status)?.let { reason ->
                Spacer(Modifier.height(s.xs))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.detail?.let { detail ->
                Spacer(Modifier.height(s.xs))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = statusWord(item.status),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The user-facing name for a permission surfaced on the Done screen. The DEFAULT_SAFE specials map to the
 * GrapheneOS toggle names ("Network" / "Sensors"); the opt-in dangerous perms (Phase 5d) map to the
 * permission-GROUP name the system permission dialog uses ("Camera", "Location", …). An unmapped perm
 * falls back to a humanized form of its bare constant suffix — safe, and only reached for a perm we don't
 * yet have a friendly label for.
 */
private fun friendlyPermissionName(permission: String): String = when (permission) {
    PermissionAllowlist.INTERNET -> "Network"
    PermissionAllowlist.OTHER_SENSORS -> "Sensors"
    "android.permission.CAMERA" -> "Camera"
    "android.permission.RECORD_AUDIO" -> "Microphone"
    "android.permission.ACCESS_FINE_LOCATION" -> "Precise location"
    "android.permission.ACCESS_COARSE_LOCATION" -> "Approximate location"
    "android.permission.ACCESS_BACKGROUND_LOCATION" -> "Background location"
    "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS" -> "Contacts"
    "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR" -> "Calendar"
    "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG" -> "Call log"
    "android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS" -> "Phone"
    "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS" -> "SMS"
    "android.permission.BODY_SENSORS" -> "Body sensors"
    "android.permission.ACTIVITY_RECOGNITION" -> "Physical activity"
    "android.permission.POST_NOTIFICATIONS" -> "Notifications"
    "android.permission.READ_MEDIA_IMAGES" -> "Photos"
    "android.permission.READ_MEDIA_VIDEO" -> "Videos"
    "android.permission.READ_MEDIA_AUDIO" -> "Music & audio"
    "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE" -> "Files & media"
    else -> permission.substringAfterLast('.')
        .split('_')
        .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
}

/**
 * The opt-in dangerous-permission review (ADR-006 D5, Phase 5d). Collapsed to a tracked-out header the
 * user can ignore; expanding it and tapping a grant IS the explicit opt-in — nothing is granted until
 * then. portage only ever lists perms it captured from the source app AND this device's installed copy
 * declares (the planner's opt-in set); the ViewModel re-checks that belt before any `pm grant`.
 */
@Composable
internal fun OptInPermissionsSection(
    optInPermissions: List<OptInPermissions>,
    onGrantOptIn: (packageName: String, permissions: List<String>) -> Unit,
) {
    val s = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(bottom = s.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = sectionHeading("ADVANCED PERMISSIONS", optInPermissions.size, "APP", "APPS"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (expanded) "HIDE" else "REVIEW",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HairlineDivider()
        if (expanded) {
            optInPermissions.forEach { app ->
                OptInAppCard(app = app, onGrantOptIn = onGrantOptIn)
            }
        } else {
            Spacer(Modifier.height(s.md))
            Text(
                text = "These apps had sensitive permissions — like camera or location — on your old phone. portage won't switch those on by itself. Tap to review and choose.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One app's opt-in review: the package over a checkable list of the offered perms, then a "grant selected"
 * (gated on a selection) and a "grant all" affordance. Selection is local UI state keyed on the offered
 * set, so a partial grant — which shrinks [OptInPermissions.permissions] when the row re-emits — resets
 * the checkboxes cleanly. The grant itself is the ViewModel's job; this only reports the user's choice.
 */
@Composable
private fun OptInAppCard(
    app: OptInPermissions,
    onGrantOptIn: (packageName: String, permissions: List<String>) -> Unit,
) {
    val s = LocalSpacing.current
    val selected = remember(app.packageName, app.permissions) { mutableStateListOf<String>() }
    Column(Modifier.padding(top = s.md)) {
        Text(
            text = app.packageName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(s.xs))
        app.permissions.forEach { perm ->
            val checked = perm in selected
            PermissionCheckRow(
                label = friendlyPermissionName(perm),
                checked = checked,
                onToggle = { if (checked) selected.remove(perm) else selected.add(perm) },
            )
        }
        Spacer(Modifier.height(s.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(s.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwissPrimaryButton(
                text = "Grant selected",
                onClick = {
                    onGrantOptIn(app.packageName, selected.toList())
                    selected.clear()
                },
                enabled = selected.isNotEmpty(),
            )
            SwissTextAction(
                text = "Grant all",
                onClick = {
                    // "Grant all" acts on the whole offered list for this app, not the checkbox subset —
                    // clear the selection so the ticks don't linger out of sync with what was just granted.
                    onGrantOptIn(app.packageName, app.permissions.toList())
                    selected.clear()
                },
            )
        }
        Spacer(Modifier.height(s.md))
        HairlineDivider()
    }
}

/** A Swiss-square check row: a filled accent box when checked, a hairline outline when not. */
@Composable
private fun PermissionCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val s = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = s.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.md),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .then(
                    if (checked) {
                        Modifier.background(MaterialTheme.colorScheme.primary, RectangleShape)
                    } else {
                        Modifier.border(s.hairline, MaterialTheme.colorScheme.outline, RectangleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
