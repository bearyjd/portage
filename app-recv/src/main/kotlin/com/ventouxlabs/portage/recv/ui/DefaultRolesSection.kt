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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.ventouxlabs.portage.providers.roles.RestorableRole
import com.ventouxlabs.portage.providers.roles.RoleRestoreCandidate
import com.ventouxlabs.portage.recv.ReceiverState
import com.ventouxlabs.portage.recv.ui.theme.LocalSpacing

/** Human label for a carried default-app role. */
private fun roleLabel(role: RestorableRole): String = when (role) {
    RestorableRole.BROWSER -> "Browser"
    RestorableRole.DIALER -> "Phone"
    RestorableRole.HOME -> "Home screen"
}

/**
 * The user-facing reason a role restore did not take. Null when there is nothing to say — the role
 * is untouched, still running, or succeeded (success removes the row rather than annotating it).
 *
 * The two failures are deliberately worded differently because the user's next move differs:
 * REJECTED is terminal for that app, UNAVAILABLE is worth retrying once the bridge is up.
 */
private fun roleAttemptMessage(attempt: ReceiverState.RoleAttempt?): String? = when (attempt) {
    ReceiverState.RoleAttempt.REJECTED ->
        "This phone wouldn't let that app take the role — it may not support being the default."
    ReceiverState.RoleAttempt.UNAVAILABLE ->
        "Couldn't reach the setup bridge. Turn Wireless debugging back on and try again."
    ReceiverState.RoleAttempt.IN_FLIGHT, null -> null
}

/**
 * The default-app restore surface (#122).
 *
 * Consent lives HERE and nowhere else. Restoring a role through the bridge shows **no system
 * confirm dialog** — the platform will not ask on portage's behalf — so this tap is the only thing
 * standing between "portage knows your old default" and "portage changed your default". Hence:
 * one explicit tap per role, no "restore all", and nothing pre-selected.
 *
 * Only roles whose app is actually installed here are ever offered, so a tap cannot point a role at
 * something missing. That filter is NOT in the apply provider (it cannot be — Tier-0 installs land
 * after apply returns); the ViewModel applies it against a live installed-set read when it builds
 * Done, on every resume, and once more at tap time. A role that fails to apply stays offered rather
 * than moving to "set" — portage must not claim a default it did not set.
 */
@Composable
internal fun DefaultRolesSection(
    candidates: List<RoleRestoreCandidate>,
    restored: List<RestorableRole>,
    attempts: Map<RestorableRole, ReceiverState.RoleAttempt>,
    onRestoreRole: (RestorableRole, String) -> Unit,
) {
    val s = LocalSpacing.current
    Column {
        Text(
            text = "DEFAULT APPS · ${candidates.size + restored.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(s.sm))
        HairlineDivider()
        Spacer(Modifier.height(s.md))
        Text(
            text = "These were your defaults on the old phone. portage won't switch them over by " +
                "itself — choose each one you want.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        candidates.forEach { candidate ->
            val attempt = attempts[candidate.role]
            val inFlight = attempt == ReceiverState.RoleAttempt.IN_FLIGHT
            Column(Modifier.fillMaxWidth().padding(top = s.md)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = roleLabel(candidate.role),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = candidate.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Disabled while the bridge round-trip runs. It can take up to 90 s (it may
                    // have to connect first), and without this the tap looked like it did nothing,
                    // inviting more taps that each queued another attempt behind the first.
                    SwissTextAction(
                        text = if (inFlight) "SETTING…" else "SET",
                        enabled = !inFlight,
                        onClick = { onRestoreRole(candidate.role, candidate.packageName) },
                    )
                }
                // Say why it failed. The two reasons call for different actions, and saying
                // nothing — the previous behaviour — was indistinguishable from a dead button.
                roleAttemptMessage(attempt)?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = s.xs),
                    )
                }
            }
        }
        restored.forEach { role ->
            Row(
                // The completed row is a STATE, not an action. It previously read "SET" — the same
                // word as the tappable affordance, separated only by colour — so a user could
                // reasonably tap the inert one and a screen reader announced both identically. The
                // wording now differs, and stateDescription carries the distinction non-visually,
                // which matters here because an honest consent surface is the whole point of the
                // feature.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = s.md)
                    .semantics { stateDescription = "Set as default" },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = roleLabel(role),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "DEFAULT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
