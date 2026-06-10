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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * SCAFFOLD entry point. Real flow (portage-prp-prompt.md §7): scan QR → handshake →
 * receive manifest → single grouped checklist (SAFE pre-checked) → "Bring it over" →
 * progress → done summary (moved / needs a tap / use Seedvault for app data).
 *
 * Tier 1 is an optional "Unlock advanced settings transfer (Shizuku)" step; everything
 * in Tier 0 works without it (DEVILS_ADVOCATE.md Q1).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RecvScreenPlaceholder() } }
    }
}

@Composable
private fun RecvScreenPlaceholder() {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("portage · recv", style = MaterialTheme.typography.headlineMedium)
            Text("Importer scaffold — scan + checklist UI lands after ADR-001 verification.")
        }
    }
}
