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

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.grepon.portage.recv.ui.theme.LocalSpacing
import cc.grepon.portage.adbbridge.AdbBridge
import cc.grepon.portage.wizard.PrivilegeWizard
import cc.grepon.portage.wizard.WizardCopy

/**
 * Thin renderer over the [PrivilegeWizard] state machine (ADR-003): enable Developer options →
 * enable Wireless Debugging → enter the 6-digit pairing code → capability summary. Every step
 * offers SKIP (Tier 0 must always work); the wizard is re-runnable from Home at any time.
 */
@Composable
fun WizardScreen(
    wizard: PrivilegeWizard,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step by wizard.step.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val s = LocalSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = s.gutter),
    ) {
        Spacer(Modifier.height(s.xl))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "S",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(s.sm))
            Text(
                text = "ADVANCED TRANSFER SETUP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(s.lg))

        when (val current = step) {
            is PrivilegeWizard.Step.Idle,
            is PrivilegeWizard.Step.Checking,
            -> PendingStep("Checking", "Looking for an existing setup…")

            is PrivilegeWizard.Step.EnableDevOptions -> InstructionStep(
                headline = "Turn on\nDeveloper\noptions.",
                body = "Open Settings → About phone, then tap \"Build number\" seven times. " +
                    "Come back here when it says you're a developer.",
                actionText = "Open About phone",
                onAction = { context.open(Settings.ACTION_DEVICE_INFO_SETTINGS) },
                onSkip = { wizard.skip() },
            )

            is PrivilegeWizard.Step.EnableWirelessDebug -> InstructionStep(
                headline = "Turn on\nWireless\ndebugging.",
                body = "Go to Developer options → Wireless debugging → turn it on. " +
                    "Then tap \"Pair device with pairing code\" and keep that dialog open.",
                actionText = "Open Developer options",
                onAction = { context.open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                onSkip = { wizard.skip() },
            )

            is PrivilegeWizard.Step.EnterPairingCode -> PairingStep(
                step = current,
                onOpenSettings = { context.open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                onSubmit = { code, port -> wizard.submitPairingCode(code, port) },
                onSkip = { wizard.skip() },
            )

            is PrivilegeWizard.Step.Pairing ->
                PendingStep("Pairing", "Securing the link to this phone's debug service…")

            is PrivilegeWizard.Step.Probing ->
                PendingStep("Checking access", "Seeing what this device allows…")

            is PrivilegeWizard.Step.Ready -> ReadyStep(
                capabilities = current.capabilities,
                onDone = onClose,
            )

            is PrivilegeWizard.Step.Skipped -> SkippedStep(onDone = onClose)
        }

        Spacer(Modifier.height(s.xl))
    }
}

private fun Context.open(action: String) {
    runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun PendingStep(headline: String, caption: String) {
    Column {
        Text(
            text = headline,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstructionStep(
    headline: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
    onSkip: () -> Unit,
) {
    val s = LocalSpacing.current
    Column {
        Text(
            text = headline,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.lg))
        HairlineDivider()
        Spacer(Modifier.height(s.lg))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.xl))
        SwissPrimaryButton(text = actionText, onClick = onAction, fullWidth = true)
        Spacer(Modifier.height(s.md))
        SwissTextAction(text = "Skip — basic transfer only", onClick = onSkip)
    }
}

@Composable
private fun PairingStep(
    step: PrivilegeWizard.Step.EnterPairingCode,
    onOpenSettings: () -> Unit,
    onSubmit: (String, Int?) -> Unit,
    onSkip: () -> Unit,
) {
    val s = LocalSpacing.current
    // TextFieldValue (not a bare String) carries cursor/selection, so filtered keystrokes and the
    // recompositions the mDNS search triggers mid-typing can't jump the caret or drop characters.
    // Plain remember for the code: rememberSaveable would serialize the pairing code into the
    // saved-instance Bundle (security review 2026-06-12). Codes expire in seconds anyway —
    // losing the field on rotation just means re-reading it from the dialog. The port is not
    // sensitive (shown in plaintext in the dialog), so it keeps its rememberSaveable.
    var code by remember { mutableStateOf(TextFieldValue("")) }
    var portText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }

    // mDNS auto-fills the port when it resolves, but NEVER blocks: GOS NsdManager is flaky, and
    // the pairing dialog shows the port in plain text, so manual entry is always available and
    // Pair enables on a valid code+port regardless of the search (found on-device, GOS A16).
    LaunchedEffect(step.detectedPort) {
        if (portText.text.isBlank()) step.detectedPort?.let {
            val p = it.toString()
            portText = TextFieldValue(p, selection = TextRange(p.length))
        }
    }
    val port = portText.text.toIntOrNull()

    Column {
        Text(
            text = "Enter the\npairing code.",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.lg))
        HairlineDivider()
        Spacer(Modifier.height(s.lg))
        Text(
            text = "Open portage and Settings side by side in SPLIT SCREEN first — the " +
                "pairing dialog must stay visible while you type. Then in Settings → Developer " +
                "options → Wireless debugging, tap \"Pair device with pairing code\" and enter " +
                "the 6-digit code and the port it shows, in the fields below. Don't close or " +
                "reopen the dialog: the port changes every time it opens.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.md))
        Text(
            text = when {
                step.detectedPort != null -> "PAIRING SERVICE FOUND · PORT ${step.detectedPort}"
                step.detecting -> "LOOKING FOR THE PAIRING SERVICE… OR ENTER THE PORT BELOW"
                else -> "ENTER THE PORT SHOWN IN THE PAIRING DIALOG"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.md))
        OutlinedTextField(
            value = code,
            onValueChange = { new ->
                if (new.text.length <= PrivilegeWizard.PAIRING_CODE_LENGTH && new.text.all(Char::isDigit)) code = new
            },
            label = { Text("6-digit pairing code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Always available — manual entry is the reliable path; mDNS only auto-fills it.
        Spacer(Modifier.height(s.sm))
        OutlinedTextField(
            value = portText,
            onValueChange = { new -> if (new.text.length <= 5 && new.text.all(Char::isDigit)) portText = new },
            label = { Text("Pairing port (shown in the dialog)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        step.error?.let {
            Spacer(Modifier.height(s.sm))
            Text(
                text = errorCopy(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(s.lg))
        SwissPrimaryButton(
            text = "Pair",
            onClick = { onSubmit(code.text, port) },
            fullWidth = true,
            // Enabled on a valid code + port — never gated on the mDNS search finishing.
            enabled = code.text.length == PrivilegeWizard.PAIRING_CODE_LENGTH &&
                port != null && port in 1..PrivilegeWizard.MAX_PORT,
        )
        Spacer(Modifier.height(s.md))
        SwissTextAction(text = "Open Developer options", onClick = onOpenSettings)
        Spacer(Modifier.height(s.sm))
        SwissTextAction(text = "Skip — basic transfer only", onClick = onSkip)
    }
}

private fun errorCopy(error: PrivilegeWizard.PairingError): String = when (error) {
    PrivilegeWizard.PairingError.WRONG_CODE ->
        "That code didn't match. Codes expire fast — read the current one and try again."
    PrivilegeWizard.PairingError.TIMEOUT ->
        "Pairing timed out. Make sure the pairing dialog is still open, then retry."
    PrivilegeWizard.PairingError.ENDPOINT_DOWN ->
        "The pairing dialog closed before pairing finished. In split screen, reopen \"Pair " +
            "device with pairing code\" and enter the fresh code AND port — both change each time."
    PrivilegeWizard.PairingError.CONNECT_FAILED ->
        "Paired, but the debug connection failed. Toggle Wireless debugging off and on, then retry."
    PrivilegeWizard.PairingError.BAD_INPUT ->
        "Enter the 6-digit code (and the port, if no service was found)."
}

@Composable
private fun ReadyStep(
    capabilities: Set<AdbBridge.PrivilegedCapability>,
    onDone: () -> Unit,
) {
    val s = LocalSpacing.current
    val advanced = AdbBridge.PrivilegedCapability.SETTINGS_SECURE in capabilities &&
        AdbBridge.PrivilegedCapability.PERMISSION_PARITY in capabilities
    val basic = AdbBridge.PrivilegedCapability.SHELL in capabilities

    Column {
        Text(
            text = when {
                advanced -> "Advanced\ntransfer\nready."
                basic -> "Basic\ntransfer\nready."
                else -> "Setup\nfinished —\nlimited access."
            },
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.lg))
        HairlineDivider()
        Spacer(Modifier.height(s.lg))
        Text(
            text = "What portage can set up for you — a summary, not a checklist, so there's " +
                "nothing here to tap. Re-run Advanced transfer setup to turn more of these Automatic.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.md))
        CapabilityRow("Secure settings", AdbBridge.PrivilegedCapability.SETTINGS_SECURE in capabilities)
        CapabilityRow("App permission parity", AdbBridge.PrivilegedCapability.PERMISSION_PARITY in capabilities)
        CapabilityRow("Batched app reinstall", AdbBridge.PrivilegedCapability.SILENT_INSTALL in capabilities)
        CapabilityRow("Navigation mode", AdbBridge.PrivilegedCapability.NAV_MODE in capabilities)
        CapabilityRow("Texting-app restore", AdbBridge.PrivilegedCapability.SMS_ROLE in capabilities)
        Spacer(Modifier.height(s.lg))
        Text(
            text = WizardCopy.GOS_REBOOT_WARNING,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(s.xl))
        SwissPrimaryButton(text = "Done", onClick = onDone, fullWidth = true)
    }
}

@Composable
private fun CapabilityRow(label: String, available: Boolean) {
    val s = LocalSpacing.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = s.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (available) "AUTOMATIC" else "MANUAL",
                style = MaterialTheme.typography.labelSmall,
                color = if (available) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        HairlineDivider()
    }
}

@Composable
private fun SkippedStep(onDone: () -> Unit) {
    val s = LocalSpacing.current
    Column {
        Text(
            text = "Skipped.\nBasic transfer\nstill works.",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.lg))
        Text(
            text = "Contacts, calendar, call log, messages, and the app list all move without " +
                "this. You can set up advanced transfer from Home any time.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(s.xl))
        SwissPrimaryButton(text = "Done", onClick = onDone, fullWidth = true)
    }
}
