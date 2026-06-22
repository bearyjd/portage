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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ventouxlabs.portage.model.PairingPayload
import com.ventouxlabs.portage.recv.ui.theme.LocalSpacing

/**
 * Scan step. When the CAMERA permission is granted the live [QrScanner] runs behind a thin
 * Swiss reticle; the paste fallback stays available beneath as the resilient path. When the
 * permission is denied (or not yet asked), the paste field is promoted to the primary action
 * so the whole flow still works with no camera at all.
 */
@Composable
fun ScanScreen(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val s = LocalSpacing.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // Ask once per session if we don't already hold the grant. Two correctness points:
    //  - It MUST run from an effect, not the composition body: rememberLauncherForActivityResult
    //    registers the underlying launcher in an effect that commits only AFTER composition, so a
    //    launch() call inline throws IllegalStateException("Launcher has not been initialized") —
    //    which crashed the scan screen whenever CAMERA was not already granted.
    //  - The `asked` latch is rememberSaveable so a denied user isn't re-prompted on every
    //    composition-from-scratch (fold/unfold or process recreation — the common case on the
    //    foldable target); the paste fallback stays their path. It survives process death, which a
    //    plain remember would not.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasPermission && !asked) {
            asked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = s.gutter),
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(s.lg))
        Text(
            text = "02 · PAIR",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.md))
        Text(
            text = if (hasPermission) "Aim at the code" else "Enter the code",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.lg))

        if (hasPermission) {
            Viewfinder(onScanned = onScanned, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(s.lg))
            Text(
                text = "Trouble scanning?",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "No camera access. Paste the pairing link shown on your old phone " +
                    "instead — it starts with “portage1:”.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(Modifier.height(s.md))
        PasteFallback(onScanned = onScanned)
        Spacer(Modifier.height(s.xl))
    }
}

/** Live camera with the thin square reticle drawn over it (Swiss framing, no scrim chrome). */
@Composable
private fun Viewfinder(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reticle = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        QrScanner(onResult = onScanned, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().reticle(reticle))
    }
}

/** A thin square reticle with four corner ticks — drawn, not asset-backed. */
private fun Modifier.reticle(color: Color): Modifier = drawWithContent {
    drawContent()
    val inset = size.minDimension * 0.12f
    val side = size.minDimension - inset * 2
    val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
    val strokeWidth = 1.5.dp.toPx()
    val tick = side * 0.12f

    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x, y), Offset(x + dx, y), strokeWidth)
        drawLine(color, Offset(x, y), Offset(x, y + dy), strokeWidth)
    }
    // Faint full frame.
    drawRect(
        color = color.copy(alpha = 0.25f),
        topLeft = topLeft,
        size = Size(side, side),
        style = Stroke(width = strokeWidth),
    )
    // Confident corner ticks.
    corner(topLeft.x, topLeft.y, tick, tick)
    corner(topLeft.x + side, topLeft.y, -tick, tick)
    corner(topLeft.x, topLeft.y + side, tick, -tick)
    corner(topLeft.x + side, topLeft.y + side, -tick, -tick)
}

/**
 * The resilient paste path. Accepts a `portage1:` URI; the field validates only the scheme
 * prefix here (the codec does the real decode) and hands a non-blank value to [onScanned].
 */
@Composable
private fun PasteFallback(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalSpacing.current
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim()
    val looksValid = trimmed.startsWith(PairingPayload.SCHEME)

    fun submit() {
        if (looksValid) onScanned(trimmed)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        HairlineDivider()
        Spacer(Modifier.height(s.md))
        Text(
            text = "PASTE LINK INSTEAD",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.sm))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("portage1:…", style = MaterialTheme.typography.bodyLarge)
            },
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
        Spacer(Modifier.height(s.md))
        SwissPrimaryButton(
            text = "Use this link",
            onClick = ::submit,
            enabled = looksValid,
            fullWidth = true,
        )
    }
}
