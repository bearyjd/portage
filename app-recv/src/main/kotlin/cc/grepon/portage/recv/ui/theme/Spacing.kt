/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Deliberate spacing scale (Swiss rhythm — generous, not uniform). Access via
 * [LocalSpacing] so screens compose against tokens, not magic numbers.
 */
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 40.dp,
    val xxl: Dp = 64.dp,
    val gutter: Dp = 24.dp,
    val hairline: Dp = 1.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
