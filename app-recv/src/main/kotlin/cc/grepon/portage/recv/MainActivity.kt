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

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cc.grepon.portage.adbbridge.AdbBridge
import cc.grepon.portage.adbbridge.AdbBridges
import cc.grepon.portage.providers.ApplyProviderRegistry
import cc.grepon.portage.providers.apk.ApkApplyProvider
import cc.grepon.portage.providers.inventory.AppRecord
import cc.grepon.portage.providers.inventory.InstallAction
import cc.grepon.portage.providers.bluetooth.BtPairingsApplyProvider
import cc.grepon.portage.providers.calendar.AndroidCalendarStore
import cc.grepon.portage.providers.calendar.CalendarApplyProvider
import cc.grepon.portage.providers.calllog.AndroidCallLogStore
import cc.grepon.portage.providers.calllog.CallLogApplyProvider
import cc.grepon.portage.providers.contacts.AndroidContactsStore
import cc.grepon.portage.providers.contacts.ContactsApplyProvider
import cc.grepon.portage.providers.inventory.AndroidInventorySource
import cc.grepon.portage.providers.inventory.AppInventoryApplyProvider
import cc.grepon.portage.providers.relay.AppBackupRelayApplyProvider
import cc.grepon.portage.recv.relay.AndroidRelayHandoff
import cc.grepon.portage.providers.settings.AndroidSecureGlobalSettingsStore
import cc.grepon.portage.providers.settings.AndroidSystemSettingsStore
import cc.grepon.portage.providers.settings.SettingsApplyProvider
import cc.grepon.portage.providers.settings.TierOneGrant
import cc.grepon.portage.providers.sms.AndroidSmsRoleGateway
import cc.grepon.portage.providers.sms.AndroidSmsStore
import cc.grepon.portage.providers.sms.SmsApplyProvider
import cc.grepon.portage.providers.sound.AndroidSoundStore
import cc.grepon.portage.providers.sound.SoundSelectionApplyProvider
import cc.grepon.portage.providers.wallpaper.AndroidWallpaperStore
import cc.grepon.portage.providers.wallpaper.WallpaperApplyProvider
import cc.grepon.portage.recv.install.AdbApkInstaller
import cc.grepon.portage.recv.install.PackageInstallerApkInstaller
import cc.grepon.portage.recv.install.androidApkTargetConfig
import cc.grepon.portage.recv.install.androidInstalledPackageVersions
import cc.grepon.portage.recv.install.hasSilentInstall
import cc.grepon.portage.recv.privilege.PrivilegeWizardHolder
import cc.grepon.portage.recv.sms.AndroidSmsRoleCoordinator
import cc.grepon.portage.recv.sms.SmsRoleCoordinator
import cc.grepon.portage.recv.sms.SmsRoleCoordinatorHolder
import cc.grepon.portage.recv.ui.ReceiverApp
import java.io.File

/**
 * Importer entry point (portage-prp-prompt.md §7): scan QR → handshake → receive manifest →
 * single grouped checklist (SAFE pre-checked) → "Bring it over" → progress → done summary.
 *
 * SMS restore needs the default-SMS role transiently: this Activity hosts the role-request
 * launcher and bridges its ActivityResult into [AndroidSmsRoleCoordinator]; the ViewModel
 * wraps the transfer in acquire → write → relinquish when SMS is selected.
 */
class MainActivity : ComponentActivity() {

    // Process-scoped: a config change mid role-dialog recreates this Activity but not the
    // ViewModel awaiting acquireRole(); a shared coordinator keeps the dialog result and the
    // await in sync (DEVILS_ADVOCATE.md Q4 stranding — see SmsRoleCoordinatorHolder).
    private val smsRoleCoordinator: AndroidSmsRoleCoordinator
        get() = SmsRoleCoordinatorHolder.get(applicationContext)

    // Registered during construction (before STARTED), as the ActivityResult API requires.
    private val smsRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            smsRoleCoordinator.onRoleResult(result.resultCode == Activity.RESULT_OK)
        }

    private val viewModel: ReceiverViewModel by viewModels {
        ReceiverViewModelFactory(applicationContext, smsRoleCoordinator)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sweep staging orphaned by a mid-transfer process death — staged payloads are
        // plaintext PII and must never outlive a single session.
        File(cacheDir, STAGING_DIR).deleteRecursively()
        // Sweep sealed-but-uncommitted PackageInstaller sessions left by a previous run that was
        // abandoned before the user tapped to install (fix 5c). mySessions is app-scoped; only
        // this app's own sessions are touched. Best-effort.
        PackageInstallerApkInstaller(applicationContext).abandonUncommittedSessions()
        smsRoleCoordinator.requestLauncher = { intent -> smsRoleLauncher.launch(intent) }
        setContent {
            ReceiverApp(
                viewModel = viewModel,
                wizard = PrivilegeWizardHolder.get(applicationContext),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system change-default prompt: re-check whether portage is still the
        // default SMS app so the in-app "restore" affordance clears once the role is handed back.
        viewModel.refreshSmsRoleStrand()
        // Returning from Settings mid-wizard: Developer options / Wireless debugging may have
        // just been toggled — let the privilege wizard advance (ADR-003).
        PrivilegeWizardHolder.get(applicationContext).recheck()
    }
}

private const val STAGING_DIR = "portage-staging"

/** APK split staging subdir under [STAGING_DIR]; swept with the rest on launch / process-death recovery. */
private const val APK_STAGING_DIR = "apk"

/** Builds the ViewModel with the compiled Tier-0 apply registry (one provider per kind). */
private class ReceiverViewModelFactory(
    private val context: Context,
    private val smsRoleCoordinator: SmsRoleCoordinator,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val registryFactory = ApplyRegistryFactory {
            onInstallActions, onRepairEntries, onRelayPrompt, onApkInstallPrompt ->
            val resolver = context.contentResolver
            // One process-scoped bridge (AdbBridges.local caches a single instance): the silent APK
            // installer and the Tier-1 settings grant both go through it.
            val adbBridge = AdbBridges.local(context)
            // The Tier-0 PackageInstaller adapter: turns each reconciled APK item into a sealed
            // multi-split session and surfaces a one-tap install-confirm row on the Done screen.
            val apkInstaller = PackageInstallerApkInstaller(context)
            // App-private staging for APK splits: the apply provider streams each split here, the
            // PackageInstaller adapter copies the bytes into its session, then the provider wipes them
            // (stage → act → wipe). Plaintext payload, so it lives under the swept cacheDir staging root.
            val apkStagingDir = File(File(context.cacheDir, STAGING_DIR), APK_STAGING_DIR)
            ApplyProviderRegistry(
                listOf(
                    ContactsApplyProvider(AndroidContactsStore(resolver)),
                    CalendarApplyProvider(AndroidCalendarStore(resolver)),
                    CallLogApplyProvider(AndroidCallLogStore(resolver)),
                    // SMS writes only while portage transiently holds ROLE_SMS — the ViewModel
                    // acquires it (SmsRoleCoordinator) around the transfer when SMS is selected,
                    // and the gateway's isSelfDefault gate self-skips outside that window.
                    SmsApplyProvider(AndroidSmsStore(resolver), AndroidSmsRoleGateway(context)),
                    AppInventoryApplyProvider(AndroidInventorySource(context.packageManager), onInstallActions),
                    // APK keystone (ADR-006): stage each carried app's split set, reconcile against this
                    // device, then install. The silent (privileged) seam is the P6 stdin-streaming
                    // installer (AdbApkInstaller → pm install-write -S .. - over the bridge); when
                    // SILENT_INSTALL is probed present the install is silent, otherwise hasSilentInstall
                    // is false and the apply provider takes the Tier-0 PackageInstaller fallback emitted
                    // below. The capability set is read at transfer start from the wizard (Ready → caps,
                    // else emptySet). C1/D2 discipline: this :providers provider holds NO :adb-bridge
                    // edge — every Android/privilege concern is injected here from :app-recv.
                    ApkApplyProvider(
                        stagingDir = apkStagingDir,
                        targetConfig = androidApkTargetConfig(context),
                        installedVersions = androidInstalledPackageVersions(context),
                        silentInstaller = AdbApkInstaller(adbBridge),
                        hasSilentInstall = {
                            hasSilentInstall(PrivilegeWizardHolder.get(context).step.value)
                        },
                        onApkInstall = { action ->
                            // Synchronous: seal the PackageInstaller session over the staged bytes BEFORE
                            // the provider wipes them, then surface the one-tap confirm row.
                            apkInstaller.install(action)?.let(onApkInstallPrompt)
                        },
                        // An "incompatible on this device" app reuses the inventory store list as a
                        // get-it-from-the-store deep link (ADR-006 D3 step 2).
                        onStoreFallback = { packageName, label ->
                            InstallAction.from(AppRecord(packageName, 0L, null, label))
                                ?.let { onInstallActions(listOf(it)) }
                        },
                    ),
                    // Tier-0 SYSTEM keys write today. Tier-1 SECURE/GLOBAL keys go live once the
                    // one-shot WRITE_SECURE_SETTINGS grant lands — normally installed by the
                    // privilege wizard's probe (ADR-003); this lazy TierOneGrant adapter is the
                    // in-apply fallback when the bridge happens to still be connected. With no
                    // grant and no live bridge, Tier-1 keys self-skip.
                    SettingsApplyProvider(
                        AndroidSystemSettingsStore(context),
                        AndroidSecureGlobalSettingsStore(context),
                        tierOneGrant = adbTierOneGrant(adbBridge),
                    ),
                    // Tier 0: sets home/lock wallpaper via the normal SET_WALLPAPER permission.
                    // The provider's bounds-only decode gate rejects decompression bombs before
                    // any bitmap is allocated (PRP-02 §7).
                    WallpaperApplyProvider(AndroidWallpaperStore(context)),
                    // Tier 0: sets default ringtone/notification/alarm via the "modify system
                    // settings" special access (Settings.System.canWrite). The provider re-resolves
                    // each carried built-in title to a LOCAL URI and never writes a sender-supplied
                    // URI — an unmatched built-in leaves that role at the device default (PRP-04 §3).
                    SoundSelectionApplyProvider(AndroidSoundStore(context)),
                    // Tier 0: the bonded-Bluetooth roster (PRP-07 public-API approach). Phase 1
                    // SURFACES the list as a "re-pair each here" checklist and applies nothing —
                    // it never calls createBond (deferred to Phase 2) and carries no link keys
                    // (non-transferable). No platform dependency, so it cannot bond by construction.
                    BtPairingsApplyProvider(onRepairEntries),
                    // Tier 0: COURIER for a user-exported, app-encrypted backup (Signal/Molly/Aegis;
                    // PRP-06). portage relays the OPAQUE file the user picked — it NEVER decrypts,
                    // parses, or imports it, and never holds the passphrase. The apply path validates
                    // the typed header (derive-never-trust the advisory package/note), streams the
                    // opaque bytes to a user-visible location via [AndroidRelayHandoff], and surfaces
                    // a guided "open this in <app>" reminder. No app data is written by portage.
                    AppBackupRelayApplyProvider(
                        onPrompt = onRelayPrompt,
                        handoff = AndroidRelayHandoff(context)::write,
                    ),
                ),
            )
        }
        @Suppress("UNCHECKED_CAST")
        return ReceiverViewModel(
            stagingDir = File(context.cacheDir, STAGING_DIR),
            smsRoleCoordinator = smsRoleCoordinator,
            applyRegistryFactory = registryFactory,
            // Abandon sealed-but-uncommitted sessions on return-home (fix 5b).
            abandonSessions = { PackageInstallerApkInstaller(context).abandonUncommittedSessions() },
        ) as T
    }
}

/** Adapt the AdbBridge self-grant (ADR-003) to the providers' narrow [TierOneGrant] seam. */
private fun adbTierOneGrant(bridge: AdbBridge) = TierOneGrant {
    when (bridge.selfGrant(WRITE_SECURE_SETTINGS_PERMISSION)) {
        AdbBridge.GrantResult.GRANTED -> TierOneGrant.Outcome.GRANTED
        AdbBridge.GrantResult.REJECTED -> TierOneGrant.Outcome.REJECTED
        AdbBridge.GrantResult.BRIDGE_UNAVAILABLE -> TierOneGrant.Outcome.UNAVAILABLE
    }
}

private const val WRITE_SECURE_SETTINGS_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
