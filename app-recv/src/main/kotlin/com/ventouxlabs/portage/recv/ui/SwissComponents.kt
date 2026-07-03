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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ventouxlabs.portage.recv.ui.theme.LocalSpacing

/**
 * Swiss masthead: a small uppercase, tracked-out running head — the deliberate alternative
 * to a default Material TopAppBar. Renders the dot-separated label with the divider rule
 * underneath, anchored to the gutter so every screen shares one structural baseline.
 */
@Composable
fun SwissMasthead(
    modifier: Modifier = Modifier,
    label: String = "PORTAGE · RECEIVE",
) {
    val s = LocalSpacing.current
    Box(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = s.gutter, vertical = s.md),
        )
        HairlineDivider(Modifier.align(Alignment.BottomStart))
    }
}

/** A single hairline rule — the workhorse of the Swiss structure. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    val s = LocalSpacing.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = s.hairline)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/**
 * Primary action button — flat International-red block, square corners, no elevation. Press
 * is designed: the fill deepens and the whole block dips in scale via a custom interaction
 * source (no ripple). Disabled reads as a quiet outline, not a greyed Material default.
 */
@Composable
fun SwissPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val s = LocalSpacing.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    val fill by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            pressed -> accent.copy(alpha = 0.84f)
            else -> accent
        },
        label = "primaryFill",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        label = "primaryScale",
    )
    val content = if (enabled) onAccent else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .graphicsScale(scale)
            .heightIn(min = 56.dp)
            .background(fill, RectangleShape)
            .then(
                if (enabled) Modifier
                else Modifier.border(BorderStroke(s.hairline, MaterialTheme.colorScheme.outline)),
            )
            .clickableNoRipple(enabled = enabled, role = Role.Button, interaction = interaction, onClick = onClick)
            .padding(horizontal = s.lg, vertical = s.md),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = content,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Secondary action — a tracked-out text affordance with a hairline underline rule, used for
 * the "Done" / "Try again" / "Paste link instead" exits. No box, no fill: Swiss restraint.
 */
@Composable
fun SwissTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val s = LocalSpacing.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        label = "textActionTint",
    )
    Row(
        modifier = modifier
            .clickableNoRipple(enabled = enabled, role = Role.Button, interaction = interaction, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.sm),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            modifier = Modifier.padding(vertical = s.sm),
        )
    }
}

/**
 * Swiss progress rule — the 2dp / square / `outline`-track / `primary` reading of a progress bar,
 * in two forms so the idiom has ONE home and its halves can't drift (portage #58).
 * [SwissDeterminateRule] fills to a known [fraction]; [SwissIndeterminateRule] sweeps an accent
 * segment across the track for unbounded waits.
 */
@Composable
fun SwissDeterminateRule(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .progressSemantics(fraction.coerceIn(0f, 1f))
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * The INDETERMINATE sibling: a thin accent segment sweeping across the neutral track for unbounded
 * waits (a handshake, a cold-connect retry) so the step never looks frozen. The accent is a moving
 * sliver rather than a full fill, so it whispers "working" instead of reading as loud as the primary
 * CTA. The segment sweeps from fully off the left to fully off the right, so the loop restart happens
 * off-screen (no jump); [clipToBounds] keeps it from bleeding into the gutter at the extremes.
 */
@Composable
fun SwissIndeterminateRule(modifier: Modifier = Modifier) {
    val sweep by rememberInfiniteTransition(label = "swissIndeterminate").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    BoxWithConstraints(
        modifier = modifier
            .progressSemantics()
            .fillMaxWidth()
            .height(2.dp)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.outline),
    ) {
        val trackPx = constraints.maxWidth.toFloat()
        Box(
            modifier = Modifier
                .fillMaxWidth(INDETERMINATE_SEGMENT)
                .height(2.dp)
                // Compositor-only translate (draw phase): no per-frame recomposition or relayout.
                .graphicsLayer { translationX = sweepTranslation(trackPx, INDETERMINATE_SEGMENT, sweep) }
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Fraction of the track width occupied by the indeterminate sweep segment. */
private const val INDETERMINATE_SEGMENT = 0.30f

/**
 * Pure translationX for the indeterminate sweep: maps [sweep] 0f→1f to a segment travelling from
 * fully off the left (`-segment · trackPx`) to fully off the right (`trackPx`). Extracted as a pure
 * function for a JVM unit test (portage #58).
 */
internal fun sweepTranslation(trackPx: Float, segment: Float, sweep: Float): Float =
    trackPx * ((1f + segment) * sweep - segment)
