/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.grepon.portage.recv.ui.ReceiverApp

/**
 * Importer entry point. Real flow (portage-prp-prompt.md §7): scan QR → handshake → receive
 * manifest → single grouped checklist (SAFE pre-checked) → "Bring it over" → progress → done
 * summary (moved / use Seedvault for app data). [ReceiverApp] owns the whole Compose tree and
 * wraps itself in [cc.grepon.portage.recv.ui.theme.PortageTheme]; this Activity just hosts it.
 *
 * Tier 1 is an optional "Unlock advanced settings transfer (Shizuku)" step; everything in
 * Tier 0 works without it (DEVILS_ADVOCATE.md Q1).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReceiverApp(viewModel = viewModel())
        }
    }
}
