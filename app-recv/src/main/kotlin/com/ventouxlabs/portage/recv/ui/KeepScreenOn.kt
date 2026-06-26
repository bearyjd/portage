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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Cheap belt for #85: while [enabled], hold the host window awake (FLAG_KEEP_SCREEN_ON via the
 * View) so the screen doesn't time out during an active, foregrounded transfer. The real keep-alive
 * is the transfer foreground service + wakelock ([com.ventouxlabs.portage.recv.TransferKeepAlive]);
 * this only reduces how often a foregrounded transfer hits screen-off in the first place. The flag
 * is always cleared on dispose (leaving the active screen, or [enabled] going false).
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
