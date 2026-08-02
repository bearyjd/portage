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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.relay.RelayRestorePrompt
import com.ventouxlabs.portage.recv.ReceiverState
import com.ventouxlabs.portage.recv.ReceiverViewModel
import com.ventouxlabs.portage.recv.checklist.ReceiverChecklist
import com.ventouxlabs.portage.recv.install.ApkInstallPrompt
import com.ventouxlabs.portage.recv.install.InstallLaunch
import com.ventouxlabs.portage.recv.install.PackageInstallerApkInstaller
import com.ventouxlabs.portage.recv.privilege.PrivilegeIntegration
import com.ventouxlabs.portage.recv.ui.theme.LocalSpacing
import com.ventouxlabs.portage.recv.ui.theme.PortageTheme

/**
 * Root of the receiver UI. Collects the single [ReceiverState] flow and crossfades the matching
 * screen beneath a fixed Swiss masthead. The masthead is the shared structural anchor (not a
 * Material TopAppBar); each state renders into the body below it.
 *
 * The optional advanced-setup surface is supplied by the active distribution flavor via
 * [PrivilegeIntegration] (ADR-003 flavor split): degoogle renders the privilege wizard; play offers
 * none, so `main` references no `:wizard` type here.
 */
@Composable
fun ReceiverApp(
    viewModel: ReceiverViewModel,
    integration: PrivilegeIntegration,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val smsRoleStrand by viewModel.smsRoleStrand.collectAsStateWithLifecycle()
    // Advanced-setup visibility is UI chrome; the integration owns the underlying progress (degoogle's
    // process-scoped wizard survives recreation). play never opens this surface.
    var wizardOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Belt for #85: keep the screen awake across the network window (pairing → item stream) so a
    // foregrounded transfer doesn't hit a screen timeout. The foreground service is the real fix.
    KeepScreenOn(enabled = state.keepsScreenAwake())

    PortageTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { SwissMasthead() },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(snackbarData = data)
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // Persistent safety net, above every screen EXCEPT the legitimate role window
                // (Transferring): portage must never be left the default texting app outside a
                // transfer (DEVILS_ADVOCATE.md Q4 §3). Scoping to one screen would let a stranded
                // user who navigates away lose the only way back.
                if (smsRoleStrand != null && state !is ReceiverState.Transferring) {
                    SmsRoleRestoreBanner(onRestore = viewModel::restoreSmsRole)
                }
                if (wizardOpen && integration.offersAdvancedSetup) {
                    // degoogle's privilege wizard: it starts itself on entry and records/resets its own
                    // outcome on close (logic moved into the flavor's AdvancedSetup so main stays
                    // :wizard-free). play never reaches here (offersAdvancedSetup is false).
                    integration.AdvancedSetup(
                        onClose = { wizardOpen = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    AnimatedContent(
                        targetState = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(260)) togetherWith
                                fadeOut(animationSpec = tween(180)))
                        },
                        contentKey = { it.key() },
                        label = "receiverState",
                    ) { current ->
                        StateBody(
                            current = current,
                            viewModel = viewModel,
                            offersAdvancedSetup = integration.offersAdvancedSetup,
                            onSetup = {
                                integration.onAdvancedSetupRequested(context)
                                wizardOpen = true
                            },
                            onApkInstallFailed = {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Couldn't start install — please retry",
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Dispatch one state to its screen. Exhaustive over the sealed [ReceiverState]. */
@Composable
private fun StateBody(
    current: ReceiverState,
    viewModel: ReceiverViewModel,
    offersAdvancedSetup: Boolean,
    onSetup: () -> Unit,
    onApkInstallFailed: () -> Unit = {},
) {
    val context = LocalContext.current
    when (current) {
        is ReceiverState.Idle ->
            IdleScreen(
                onScan = viewModel::startScanning,
                onSetup = onSetup,
                offersAdvancedSetup = offersAdvancedSetup,
                modifier = Modifier.fillMaxSize(),
            )

        is ReceiverState.Scanning ->
            ScanScreen(onScanned = viewModel::onQrScanned, modifier = Modifier.fillMaxSize())

        is ReceiverState.Pairing ->
            PendingBody(headline = "Pairing", caption = "Securing the link and reading the manifest…")

        is ReceiverState.Reviewing ->
            ReviewingBody(current = current, viewModel = viewModel)

        is ReceiverState.Transferring ->
            TransferringScreen(
                items = current.items,
                modifier = Modifier.fillMaxSize(),
            )

        is ReceiverState.Done ->
            DoneScreen(
                moved = current.moved,
                skipped = current.skipped,
                onDone = viewModel::reset,
                modifier = Modifier.fillMaxSize(),
                installActions = current.installActions,
                repairEntries = current.repairEntries,
                relayPrompts = current.relayPrompts,
                apkInstallPrompts = current.apkInstallPrompts,
                restoredPermissions = current.restoredPermissions,
                optInPermissions = current.optInPermissions,
                roleCandidates = current.roleCandidates,
                restoredRoles = current.restoredRoles,
                roleAttempts = current.roleAttempts,
                failedItems = current.failedItems,
                onInstall = { action -> launchInstall(context, action) },
                onInstallApk = { prompt ->
                    if (!commitApkInstall(context, prompt)) onApkInstallFailed()
                },
                onGrantOptIn = { packageName, permissions ->
                    viewModel.grantOptIn(packageName, permissions)
                },
                // The ONLY path that changes a default app. The ViewModel re-checks the (role,
                // package) pair against the live offered set before it reaches the bridge.
                onRestoreRole = { role, packageName -> viewModel.restoreRole(role, packageName) },
                onOpenBluetoothSettings = { launchBluetoothSettings(context) },
                onOpenRelayApp = { prompt -> launchRelayApp(context, prompt) },
                backupActionLabel = if (seedvaultIntent(context) != null) {
                    "Open Seedvault"
                } else {
                    "Open backup settings"
                },
                onOpenBackup = { launchBackup(context) },
            )

        is ReceiverState.Failed ->
            FailedScreen(
                reason = current.reason,
                onRetry = viewModel::reset,
                modifier = Modifier.fillMaxSize(),
            )
    }
}

/**
 * The review screen, plus a live read of the "Modify system settings" (Tier-0 Settings.System)
 * access. canWrite() is re-checked on every ON_RESUME, so the one-tap grant nudge appears and then
 * disappears as the user returns from the system toggle. The apply path re-checks canWrite() too,
 * so a stale read never blocks the keys — this only governs whether the nudge shows.
 */
@Composable
private fun ReviewingBody(
    current: ReceiverState.Reviewing,
    viewModel: ReceiverViewModel,
) {
    val context = LocalContext.current
    var permissionRequestInFlight by remember { mutableStateOf(false) }
    val applyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRequestInFlight = false
        // Providers re-check permissions and fail per-item, so denial never blocks unrelated data.
        viewModel.onConfirm()
    }
    var canWriteSystem by remember {
        mutableStateOf(android.provider.Settings.System.canWrite(context))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canWriteSystem = android.provider.Settings.System.canWrite(context)
    }
    ChecklistScreen(
        senderName = current.senderName,
        groups = current.groups,
        onToggle = viewModel::onToggle,
        onConfirm = {
            if (!permissionRequestInFlight) {
                val missing = ReceiverChecklist.requiredApplyPermissions(current.groups)
                    .filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                if (missing.isEmpty()) {
                    viewModel.onConfirm()
                } else {
                    permissionRequestInFlight = true
                    applyPermissionLauncher.launch(missing.toTypedArray())
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        absentKinds = current.absentKinds,
        systemSettingsGrantNeeded =
            ReceiverChecklist.systemSettingsGrantNeeded(current.groups, canWriteSystem),
        onGrantSystemSettings = { launchManageWriteSettings(context) },
    )
}

/**
 * Deep-link straight to THIS app's "Modify system settings" toggle (Tier-0 Settings.System access).
 * The package-scoped ACTION_MANAGE_WRITE_SETTINGS lands on the single toggle — one tap — instead of
 * the multi-level Settings dive. Falls back to the un-scoped listing screen if the per-app intent
 * fails to resolve, and swallows a missing activity rather than crashing the review screen.
 */
private fun launchManageWriteSettings(context: Context) {
    val perApp = Intent(
        android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launched = runCatching { context.startActivity(perApp); true }.getOrDefault(false)
    if (!launched) {
        val listing = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(listing) }
    }
}

/**
 * Fire a store deep link for one app — exactly one user tap, never a silent install (PRP §2).
 * The URI is re-validated to an allowed scheme first ([InstallLaunch]); a missing store app is
 * swallowed rather than crashing the done screen.
 */
private fun launchInstall(context: Context, action: InstallAction) {
    val uri = InstallLaunch.safeUri(action) ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Commit one carried-APK install (ADR-006 D3/D6): fire the system install-confirm UI for the prompt's
 * already-sealed `PackageInstaller` session — our own app committing our own carried bytes, NO shell uid.
 * The session was created during apply; here we only commit it. Returns false when the session is
 * missing/expired so the caller can surface a brief retry prompt (fix 6).
 */
private fun commitApkInstall(context: Context, prompt: ApkInstallPrompt): Boolean =
    PackageInstallerApkInstaller(context).commit(prompt.sessionId)

/**
 * The Seedvault handoff (division-of-labor framing, PRP §3): portage moved the parity layer;
 * app DATA restores through the system backup. Launch Seedvault directly when present (GOS
 * ships it), else land the user in Settings to find System → Backup.
 */
private fun seedvaultIntent(context: Context): Intent? = runCatching {
    context.packageManager.getLaunchIntentForPackage(SEEDVAULT_PACKAGE)
}.getOrNull()

private fun launchBackup(context: Context) {
    val intent = seedvaultIntent(context)
        ?: Intent(android.provider.Settings.ACTION_SETTINGS)
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Phase 1 re-pair assist (PRP-07): open the SYSTEM Bluetooth settings so the user re-pairs each
 * listed device themselves — the OS owns bonding. Phase 1 does NOT call createBond and carries no
 * link keys; this is a single, validated intent into the platform, no per-device extras. Assisted
 * per-row createBond is deferred to a Phase 2 follow-up.
 */
private fun launchBluetoothSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Phase 1 relay re-link (PRP-06): open the target app so the user can import the relayed backup file
 * with THEIR passphrase. portage relayed the OPAQUE file only — it never imports it and never holds
 * the passphrase. The package was already validated against the typed RelayApp enum + package regex
 * ([com.ventouxlabs.portage.providers.relay.RelayHeader.sanitizedOrNull]); we re-resolve it to a launch
 * intent here (null when the app isn't installed, in which case the tap is a no-op rather than a
 * crash). A per-app import deep link is deferred to a Phase 2 follow-up.
 */
private fun launchRelayApp(context: Context, prompt: RelayRestorePrompt) {
    val intent = runCatching {
        context.packageManager.getLaunchIntentForPackage(prompt.targetPackage)
    }.getOrNull() ?: return
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private const val SEEDVAULT_PACKAGE = "com.stevesoltys.seedvault"

/**
 * Persistent safety net for the default-SMS handoff (DEVILS_ADVOCATE.md Q4 §3): shown on Home
 * whenever portage is still the default texting app from an interrupted restore. A notification
 * would be the textbook backstop, but POST_NOTIFICATIONS is denied-by-default on GrapheneOS, so an
 * in-app affordance is the reliable path — it survives process death via the on-disk ledger and
 * needs no extra permission.
 */
@Composable
private fun SmsRoleRestoreBanner(onRestore: () -> Unit) {
    val s = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = s.gutter, vertical = s.md)) {
            Text(
                text = "portage is still your texting app",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(s.xs))
            Text(
                text = "It only needed that to restore your messages. Hand it back to your usual app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(s.sm))
            SwissPrimaryButton(
                text = "Restore my texting app",
                onClick = onRestore,
                fullWidth = true,
            )
        }
    }
}

/** Quiet interstitial for the brief [ReceiverState.Pairing] handshake window. */
@Composable
private fun PendingBody(headline: String, caption: String) {
    val s = LocalSpacing.current
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = s.gutter), contentAlignment = Alignment.CenterStart) {
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
            Spacer(Modifier.height(s.lg))
            // Live activity through the handshake window so the step never looks frozen (portage #58).
            SwissIndeterminateRule()
        }
    }
}

/**
 * Stable transition key per state *kind* — keeps data-only updates (e.g. progress ticks,
 * checklist toggles) from triggering a full crossfade, so motion fires on real screen changes.
 */
private fun ReceiverState.key(): String = when (this) {
    is ReceiverState.Idle -> "idle"
    is ReceiverState.Scanning -> "scanning"
    is ReceiverState.Pairing -> "pairing"
    is ReceiverState.Reviewing -> "reviewing"
    is ReceiverState.Transferring -> "transferring"
    is ReceiverState.Done -> "done"
    is ReceiverState.Failed -> "failed"
}

/**
 * The network window — pairing (handshake + manifest read) and the live item stream — where the
 * screen is kept awake as a belt for #85. Idle/Scanning/Reviewing/Done/Failed let it time out.
 *
 * Note the deliberate asymmetry with the foreground-service keep-alive, which scopes to the item
 * stream only (it starts in onConfirm): Pairing is intentionally belt-only. It is short and
 * user-attended (the user just scanned and is watching the link come up), so the cheap screen-on
 * flag is enough; the wakelock is reserved for the long, potentially-background item stream.
 */
private fun ReceiverState.keepsScreenAwake(): Boolean =
    this is ReceiverState.Pairing || this is ReceiverState.Transferring
