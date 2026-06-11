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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import cc.grepon.portage.send.ui.theme.LocalSpacing
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

/**
 * The trust anchor on screen: a large QR carrying `{ip, port, psk}` (PROTOCOL.md §1).
 * The Activity holds FLAG_SECURE the whole session so this can't be screenshotted or
 * cast. The QR sits on a true-white card regardless of theme — scanners want contrast,
 * not aesthetics. The listener is already armed; this screen just waits.
 */
@Composable
fun PairingScreen(
    qrText: String,
    itemCount: Int,
    totalBytes: Long,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalSpacing.current
    val qrBitmap = remember(qrText) {
        runCatching {
            BarcodeEncoder().encodeBitmap(qrText, BarcodeFormat.QR_CODE, QR_PIXELS, QR_PIXELS)
        }.getOrNull()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = s.gutter),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "02 · PAIR",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.md))
        Text(
            text = "Scan from the new phone",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.lg))
        if (qrBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color.White)
                    .padding(s.md),
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            Text(
                text = "Could not render the QR code — cancel and try again.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(s.lg))
        Text(
            text = "$itemCount items · ${formatBytes(totalBytes)} ready. Open portage·receive, " +
                "tap Scan, and point it here. This code is single-use and dies in two minutes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.xl))
        SwissTextAction(text = "Cancel", onClick = onCancel)
    }
}

/** 1.5 KB / 3.4 MB / 1.2 GB — one decimal per tier; a glance value, not accounting. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private const val QR_PIXELS = 768
