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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role

/**
 * Press without a Material ripple — the Swiss buttons render their own designed press feedback
 * (fill shift + scale dip), so the default ripple would fight the aesthetic. Routes the press
 * to the supplied [interaction] source so callers can react to `collectIsPressedAsState`.
 */
fun Modifier.clickableNoRipple(
    enabled: Boolean = true,
    role: Role? = null,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interaction,
    indication = null,
    enabled = enabled,
    role = role,
    onClick = onClick,
)

/** Uniform graphics scale around the center — used for the press-dip on action blocks. */
fun Modifier.graphicsScale(value: Float): Modifier = scale(value)
