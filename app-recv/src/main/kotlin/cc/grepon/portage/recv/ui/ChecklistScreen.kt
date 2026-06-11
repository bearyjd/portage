/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.Tier
import cc.grepon.portage.recv.checklist.ChecklistGroup
import cc.grepon.portage.recv.checklist.ChecklistItem
import cc.grepon.portage.recv.checklist.ReceiverChecklist
import cc.grepon.portage.recv.ui.theme.LocalSpacing

/**
 * The centerpiece: the single grouped review. Swiss treatment — the sender's name as an
 * oversized display heading, each [ChecklistGroup] introduced by a tracked-out section header
 * over a hairline rule, and every item a generous row with a designed square toggle. The
 * "Bring it over" action lives in a sticky bottom bar, gated on having a selection.
 */
@Composable
fun ChecklistScreen(
    senderName: String,
    groups: List<ChecklistGroup>,
    onToggle: (Int) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    absentKinds: List<ItemKind> = emptyList(),
) {
    val s = LocalSpacing.current
    val hasSelection = remember(groups) { ReceiverChecklist.hasSelection(groups) }
    val selectedCount = remember(groups) { ReceiverChecklist.selectedIds(groups).size }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = s.gutter, end = s.gutter, top = s.lg, bottom = s.lg,
            ),
        ) {
            item { ChecklistHeader(senderName = senderName) }
            groups.forEach { group ->
                item(key = "h:${group.title}") { GroupHeader(title = group.title) }
                items(group.items, key = { it.meta.itemId }) { item ->
                    ChecklistRow(item = item, onToggle = onToggle)
                }
            }
            if (absentKinds.isNotEmpty()) {
                item(key = "h:absent") { GroupHeader(title = "Not on the old phone") }
                items(absentKinds, key = { "absent:${it.wire}" }) { kind ->
                    AbsentRow(kind = kind)
                }
            }
        }
        ConfirmBar(
            enabled = hasSelection,
            selectedCount = selectedCount,
            onConfirm = onConfirm,
        )
    }
}

/** A grayed, untoggleable line for a kind the sender had nothing of — present, not hidden. */
@Composable
private fun AbsentRow(kind: ItemKind) {
    val s = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.4f)
            .heightIn(min = 48.dp)
            .padding(vertical = s.md),
        verticalAlignment = Alignment.Top,
    ) {
        // An empty, hairline-only square — visibly a non-option, not an unchecked choice.
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, RectangleShape),
        )
        Spacer(Modifier.width(s.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = tierHint(kind),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(s.xs))
            Text(
                text = "Nothing to bring over",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HairlineDivider()
}

@Composable
private fun ChecklistHeader(senderName: String) {
    val s = LocalSpacing.current
    Column {
        Text(
            text = "03 · REVIEW",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.md))
        Text(
            text = "What to carry from",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = senderName,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(s.lg))
    }
}

@Composable
private fun GroupHeader(title: String) {
    val s = LocalSpacing.current
    Column {
        Spacer(Modifier.height(s.md))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = s.sm),
        )
        HairlineDivider()
    }
}

/** One selectable line. The whole row is the hit target; the toggle reflects [item] state. */
@Composable
private fun ChecklistRow(
    item: ChecklistItem,
    onToggle: (Int) -> Unit,
) {
    val s = LocalSpacing.current
    val meta = item.meta
    val isSms = meta.kind == ItemKind.SMS
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rowTint by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
        label = "rowTint",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowTint)
            .clickableNoRipple(
                role = Role.Checkbox,
                interaction = interaction,
                onClick = { onToggle(meta.itemId) },
            )
            .semantics(mergeDescendants = true) { toggleableState = ToggleableState(item.checked) }
            .heightIn(min = 48.dp)
            .padding(vertical = s.md),
        verticalAlignment = Alignment.Top,
    ) {
        SwissCheckbox(checked = item.checked)
        Spacer(Modifier.width(s.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = meta.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(s.xs))
            Text(
                text = tierHint(meta.kind),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isSms) {
                Spacer(Modifier.height(s.xs))
                Text(
                    text = "Needs a one-time default-SMS-app step.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    HairlineDivider()
}

/** A designed square toggle: hairline box, accent fill + drawn check when on. No Material box. */
@Composable
private fun SwissCheckbox(checked: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val outline = MaterialTheme.colorScheme.outline
    val fill by animateColorAsState(
        targetValue = if (checked) accent else Color.Transparent,
        label = "boxFill",
    )
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(fill, RectangleShape)
            .border(1.5.dp, if (checked) accent else outline, RectangleShape)
            .drawCheck(visible = checked, color = onAccent),
    )
}

/** Hand-drawn check stroke so the mark matches the Swiss square, not a Material glyph. */
private fun Modifier.drawCheck(visible: Boolean, color: Color): Modifier = drawBehind {
    if (!visible) return@drawBehind
    val w = size.width
    val h = size.height
    val strokeWidth = 2.dp.toPx()
    // Two segments forming a check, inset from the box edges.
    drawLine(
        color = color,
        start = Offset(w * 0.24f, h * 0.52f),
        end = Offset(w * 0.43f, h * 0.70f),
        strokeWidth = strokeWidth,
    )
    drawLine(
        color = color,
        start = Offset(w * 0.43f, h * 0.70f),
        end = Offset(w * 0.74f, h * 0.30f),
        strokeWidth = strokeWidth,
    )
}

/** Sticky action bar: a hairline rule, the count, and the full-width primary block. */
@Composable
private fun ConfirmBar(
    enabled: Boolean,
    selectedCount: Int,
    onConfirm: () -> Unit,
) {
    val s = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HairlineDivider()
        Column(modifier = Modifier.padding(horizontal = s.gutter, vertical = s.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "SELECTED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selectedCount.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.height(s.md))
            SwissPrimaryButton(
                text = "Bring it over",
                onClick = onConfirm,
                enabled = enabled,
                fullWidth = true,
            )
        }
    }
}

/** Short, human tier hint for a kind — Tier 1 items carry the "advanced" qualifier. */
private fun tierHint(kind: ItemKind): String {
    val base = when (kind) {
        ItemKind.CONTACTS_VCF -> "Contacts"
        ItemKind.CALENDAR_ICS -> "Calendar events"
        ItemKind.CALL_LOG -> "Call history"
        ItemKind.SMS -> "Text messages"
        ItemKind.APP_INVENTORY -> "App list for reinstall"
        ItemKind.APK -> "App package"
        ItemKind.SETTINGS -> "Device settings"
    }
    return if (kind.tier == Tier.TIER1) "$base · advanced" else base
}
