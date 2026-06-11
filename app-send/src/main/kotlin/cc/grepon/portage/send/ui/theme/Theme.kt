/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.send.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val LightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = PaperLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = PaperLight,
    onSurfaceVariant = MutedLight,
    outline = HairlineLight,
    outlineVariant = HairlineLight,
    error = AccentLight,
    onError = PaperLight,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = PaperDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = MutedDark,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    error = AccentDark,
    onError = PaperDark,
)

/**
 * portage sender theme — the receiver's Swiss language verbatim, so the two apps read as
 * one product. Both light and dark are deliberate (not Material defaults, not
 * dynamic color) — paper/ink contrast with one International-red accent (web/design-quality).
 */
@Composable
fun PortageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PortageTypography,
            content = content,
        )
    }
}
