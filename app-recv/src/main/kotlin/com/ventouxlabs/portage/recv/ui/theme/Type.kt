/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Swiss type treatment: strong scale contrast, heavy display weight, negative tracking on
 * large sizes, comfortable body line-height. Uses the platform grotesque (SansSerif) — a
 * deliberate choice that compiles without bundled font binaries; swapping in Inter/Helvetica
 * Neue (res/font) is a later polish step that only touches [fontFamily] here.
 */
private val Grotesque = FontFamily.SansSerif

val PortageTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.Bold,
        fontSize = 46.sp, lineHeight = 48.sp, letterSpacing = (-1.2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.Bold,
        fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.9).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.Medium,
        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Grotesque, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp,
    ),
)
