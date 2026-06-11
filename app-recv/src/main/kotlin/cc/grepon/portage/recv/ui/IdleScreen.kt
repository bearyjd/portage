/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cc.grepon.portage.recv.shizuku.ShizukuAccessStrand
import cc.grepon.portage.recv.ui.theme.LocalSpacing

/**
 * Landing. Swiss editorial: a small indexed running head, an oversized display headline pinned
 * to the gutter, a single explanatory line, then the primary "Scan" call. The app-data
 * division-of-labor note sits as tracked-out fine print above the action — context, not noise.
 *
 * Below the primary call, an OPTIONAL secondary section ([SecureSettingsSection]) surfaces the
 * Tier-1 secure-settings unlock — quiet and indexed "02", and only when Shizuku is at least present.
 */
@Composable
fun IdleScreen(
    onScan: () -> Unit,
    shizukuStrand: ShizukuAccessStrand,
    onUnlockSecureSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = s.gutter),
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(s.xxl))

        // Indexed section marker — a Swiss tell.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "01",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(s.sm))
            Text(
                text = "START",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(s.lg))

        Text(
            text = "Bring your\nold phone\nover.",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(s.lg))
        HairlineDivider()
        Spacer(Modifier.height(s.lg))

        Text(
            text = "On your old phone, open portage and start a transfer. " +
                "It will show a one-time pairing code. Point this phone at it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(0.92f),
        )

        Spacer(Modifier.height(s.xl))

        Text(
            text = "APP DATA NEEDS A BACKUP · PORTAGE MOVES THE REST",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )

        Spacer(Modifier.height(s.md))

        SwissPrimaryButton(
            text = "Scan the other phone",
            onClick = onScan,
            fullWidth = true,
        )

        SecureSettingsSection(strand = shizukuStrand, onUnlock = onUnlockSecureSettings)

        Spacer(Modifier.height(s.xl))
    }
}

/**
 * The optional Tier-1 "secure settings" affordance (ADR-001). Deliberately SECONDARY to the Scan
 * call — portage moves system settings at Tier 0 without it — so it reads as a quiet indexed "02"
 * section, uses the restrained [SwissTextAction] rather than the red primary block, and stays hidden
 * entirely when Shizuku isn't installed (no nagging the 95% who never use it). The unlock is one
 * gesture: authorize Shizuku, then a one-shot WRITE_SECURE_SETTINGS grant the ViewModel runs.
 */
@Composable
private fun SecureSettingsSection(
    strand: ShizukuAccessStrand,
    onUnlock: () -> Unit,
) {
    if (strand == ShizukuAccessStrand.NOT_INSTALLED) return // fully usable at Tier 0 — keep Home clean
    val s = LocalSpacing.current

    Spacer(Modifier.height(s.xl))
    HairlineDivider()
    Spacer(Modifier.height(s.lg))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "02",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(s.sm))
        Text(
            text = if (strand == ShizukuAccessStrand.UNLOCKED) "SECURE SETTINGS · UNLOCKED" else "OPTIONAL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(s.md))

    Text(
        text = "Secure system settings",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )

    Spacer(Modifier.height(s.sm))

    Text(
        text = secureSettingsCaption(strand),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(0.92f),
    )

    val action: Pair<String, Boolean>? = when (strand) {
        ShizukuAccessStrand.LOCKED -> "Unlock secure settings" to true
        ShizukuAccessStrand.GRANT_FAILED -> "Try again" to true
        ShizukuAccessStrand.UNLOCKING -> "Unlocking…" to false
        // Guidance-only states: the caption carries the whole message, no action rule.
        ShizukuAccessStrand.NOT_RUNNING,
        ShizukuAccessStrand.OUTDATED,
        ShizukuAccessStrand.UNLOCKED,
        ShizukuAccessStrand.NOT_INSTALLED, // unreachable (early return) — keeps the when exhaustive
        -> null
    }
    if (action != null) {
        Spacer(Modifier.height(s.md))
        SwissTextAction(text = action.first, onClick = onUnlock, enabled = action.second)
    }
}

/** One intentional line per [ShizukuAccessStrand] — the Swiss caption that does the explaining. */
private fun secureSettingsCaption(strand: ShizukuAccessStrand): String = when (strand) {
    ShizukuAccessStrand.LOCKED ->
        "Display, sound, and input settings that Android keeps locked. Authorize Shizuku once to " +
            "include them in the transfer."
    ShizukuAccessStrand.UNLOCKING -> "Authorizing Shizuku and unlocking…"
    ShizukuAccessStrand.UNLOCKED -> "These will come across on your next transfer. Nothing else to do."
    ShizukuAccessStrand.NOT_RUNNING ->
        "Start Shizuku on this phone, then come back, to also bring locked system settings."
    ShizukuAccessStrand.OUTDATED ->
        "Your Shizuku is too old for portage to use. Update it to also bring locked system settings."
    ShizukuAccessStrand.GRANT_FAILED ->
        "Shizuku is authorized, but the one-time unlock didn't take. Try again."
    ShizukuAccessStrand.NOT_INSTALLED -> "" // unreachable (early return)
}
