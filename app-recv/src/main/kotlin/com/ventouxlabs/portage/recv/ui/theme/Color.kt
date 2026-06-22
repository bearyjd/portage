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

import androidx.compose.ui.graphics.Color

/**
 * Swiss / International palette: disciplined warm neutrals + one confident accent. Not a
 * Material default scheme — intentional paper/ink contrast with a single International red.
 */
// Light ("paper")
val PaperLight = Color(0xFFF7F5F1)
val InkLight = Color(0xFF15120E)
val MutedLight = Color(0xFF6B6660)
val HairlineLight = Color(0xFFE2DDD4)
val SurfaceLight = Color(0xFFFFFFFF)

// Dark
val PaperDark = Color(0xFF121110)
val InkDark = Color(0xFFF2EFEA)
val MutedDark = Color(0xFF9C968E)
val HairlineDark = Color(0xFF2B2825)
val SurfaceDark = Color(0xFF1B1A18)

// Accent (one confident red, slightly brighter on dark for contrast)
val AccentLight = Color(0xFFD81E2C)
val AccentDark = Color(0xFFFF5A48)
