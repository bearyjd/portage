/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ventouxlabs.portage.providers.ApplyProviderRegistry
import com.ventouxlabs.portage.providers.apk.ApkApplyProvider
import com.ventouxlabs.portage.providers.inventory.AppRecord
import com.ventouxlabs.portage.providers.inventory.InstallAction
import com.ventouxlabs.portage.providers.bluetooth.BtPairingsApplyProvider
import com.ventouxlabs.portage.providers.calendar.AndroidCalendarStore
import com.ventouxlabs.portage.providers.calendar.CalendarApplyProvider
import com.ventouxlabs.portage.providers.calllog.AndroidCallLogStore
import com.ventouxlabs.portage.providers.calllog.CallLogApplyProvider
import com.ventouxlabs.portage.providers.contacts.AndroidContactsStore
import com.ventouxlabs.portage.providers.contacts.ContactsApplyProvider
import com.ventouxlabs.portage.providers.inventory.AndroidInventorySource
import com.ventouxlabs.portage.providers.inventory.AppInventoryApplyProvider
import com.ventouxlabs.portage.providers.mms.AndroidMmsStore
import com.ventouxlabs.portage.providers.mms.MmsApplyProvider
import com.ventouxlabs.portage.providers.relay.AppBackupRelayApplyProvider
import com.ventouxlabs.portage.recv.relay.AndroidRelayHandoff
import com.ventouxlabs.portage.providers.settings.AndroidSecureGlobalSettingsStore
import com.ventouxlabs.portage.providers.settings.AndroidSystemSettingsStore
import com.ventouxlabs.portage.providers.settings.SettingsApplyProvider
import com.ventouxlabs.portage.providers.sms.AndroidSmsRoleGateway
import com.ventouxlabs.portage.providers.sms.AndroidSmsStore
import com.ventouxlabs.portage.providers.sms.SmsApplyProvider
import com.ventouxlabs.portage.providers.sound.AndroidSoundStore
import com.ventouxlabs.portage.providers.sound.SoundFileApplyProvider
import com.ventouxlabs.portage.providers.sound.SoundFileRemap
import com.ventouxlabs.portage.providers.sound.SoundSelectionApplyProvider
import com.ventouxlabs.portage.providers.wallpaper.AndroidWallpaperStore
import com.ventouxlabs.portage.providers.wallpaper.WallpaperApplyProvider
import com.ventouxlabs.portage.providers.userfile.UserFileApplyProvider
import com.ventouxlabs.portage.recv.files.AndroidUserFileStore
import com.ventouxlabs.portage.recv.install.PackageInstallerApkInstaller
import com.ventouxlabs.portage.recv.install.androidApkTargetConfig
import com.ventouxlabs.portage.recv.install.androidInstalledPackageVersions
import com.ventouxlabs.portage.recv.imports.FileCallLogImportJournal
import com.ventouxlabs.portage.recv.imports.FileContactImportJournal
import com.ventouxlabs.portage.recv.privilege.providePrivilegeIntegration
import com.ventouxlabs.portage.recv.sms.AndroidSmsRoleCoordinator
import com.ventouxlabs.portage.recv.sms.SmsRoleCoordinator
import com.ventouxlabs.portage.recv.sms.SmsRoleCoordinatorHolder
import com.ventouxlabs.portage.recv.ui.ReceiverApp
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
                integration = providePrivilegeIntegration(applicationContext),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system change-default prompt: re-check whether portage is still the
        // default SMS app so the in-app "restore" affordance clears once the role is handed back.
        viewModel.refreshSmsRoleStrand()
        // Returning from Settings mid-wizard: Developer options / Wireless debugging may have just been
        // toggled — let the active flavor's privilege integration advance (degoogle: wizard recheck;
        // play: no-op).
        providePrivilegeIntegration(applicationContext).onResume(applicationContext)
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
        // The active distribution flavor supplies every apply-time privilege seam (ADR-003 flavor
        // split): degoogle wires the self-contained ADB bridge + wizard; play returns no-op Tier-0
        // defaults with neither :adb-bridge nor :wizard compiled in. :app-recv/src/main holds no
        // :adb-bridge/:wizard type — that boundary is what keeps the play binary bridge-free.
        val wiring = providePrivilegeIntegration(context).wiring(context)
        val registryFactory = ApplyRegistryFactory { sinks ->
            val resolver = context.contentResolver
            // The Tier-0 PackageInstaller adapter: turns each reconciled APK item into a sealed
            // multi-split session and surfaces a one-tap install-confirm row on the Done screen.
            val apkInstaller = PackageInstallerApkInstaller(context)
            // App-private staging for APK splits: the apply provider streams each split here, the
            // PackageInstaller adapter copies the bytes into its session, then the provider wipes them
            // (stage → act → wipe). Plaintext payload, so it lives under the swept cacheDir staging root.
            val apkStagingDir = File(File(context.cacheDir, STAGING_DIR), APK_STAGING_DIR)
                val soundStore = AndroidSoundStore(context)
                val soundFileRemap = SoundFileRemap()
                ApplyProviderRegistry(
                listOf(
                    ContactsApplyProvider(
                        AndroidContactsStore(resolver),
                        FileContactImportJournal(File(context.filesDir, "contact-imports.sha256")),
                    ),
                    CalendarApplyProvider(AndroidCalendarStore(resolver)),
                    CallLogApplyProvider(
                        AndroidCallLogStore(resolver),
                        FileCallLogImportJournal(File(context.filesDir, "call-log-imports.sha256")),
                    ),
                    // SMS/MMS writes only while portage transiently holds ROLE_SMS — the ViewModel
                    // acquires it (SmsRoleCoordinator) around the transfer when either is selected,
                    // and the gateway's isSelfDefault gate self-skips outside that window.
                    SmsApplyProvider(AndroidSmsStore(resolver), AndroidSmsRoleGateway(context)),
                    MmsApplyProvider(AndroidMmsStore(resolver), AndroidSmsRoleGateway(context)),
                    AppInventoryApplyProvider(AndroidInventorySource(context.packageManager), sinks.onInstallActions),
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
                        // Flavor-supplied silent (privileged) install seam. degoogle: AdbApkInstaller,
                        // which self-guards via AdbBridge.connect() (NoEndpoint when Wireless Debugging is
                        // off, never driving libadb into the uninterruptible mDNS-discovery hang — GOS A16)
                        // plus an outer attempt-timeout backstop. play: ApkSilentInstaller.Deferred → Tier-0.
                        silentInstaller = wiring.silentInstaller,
                        // degoogle reads SILENT_INSTALL from the wizard's probed set; play is always false.
                        hasSilentInstall = wiring.hasSilentInstall,
                        // Runtime-permission parity (ADR-006 D5), silent-install path ONLY. degoogle: the
                        // AdbBridge.grantRuntimePermission call site (allowlist default-safe `auto` set,
                        // run only after a silent install, connect→grant→disconnect in finally, AC-11);
                        // its declared set read from PackageManager post-install. play: NoOp / None — no
                        // grant ever runs. On the Tier-0 fallback neither runs in either flavor.
                        permissionGranter = wiring.permissionGranter,
                        targetDeclaredPermissions = wiring.targetDeclaredPermissions,
                        // Display-only: feed the Done screen's "restored Network, Sensors" summary.
                        onPermissionsRestored = sinks.onPermissionsRestored,
                        // Data-only: feed the Done screen's opt-in dangerous-perm review (Phase 5d).
                        // Nothing is granted from this callback — the user confirms each on Done.
                        onOptInPermissions = sinks.onOptInPermissions,
                        onApkInstall = { action ->
                            // Synchronous: seal the PackageInstaller session over the staged bytes BEFORE
                            // the provider wipes them, then surface the one-tap confirm row.
                            apkInstaller.install(action)?.let(sinks.onApkInstallPrompt)
                        },
                        // An "incompatible on this device" app reuses the inventory store list as a
                        // get-it-from-the-store deep link (ADR-006 D3 step 2).
                        onStoreFallback = { packageName, label ->
                            InstallAction.from(AppRecord(packageName, 0L, null, label))
                                ?.let { sinks.onInstallActions(listOf(it)) }
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
                        // degoogle: the AdbBridge WRITE_SECURE_SETTINGS self-grant fallback; play:
                        // TierOneGrant.Unavailable, so Tier-1 keys self-skip.
                        tierOneGrant = wiring.tierOneGrant,
                    ),
                    // Tier 0: sets home/lock wallpaper via the normal SET_WALLPAPER permission.
                    // The provider's bounds-only decode gate rejects decompression bombs before
                    // any bitmap is allocated (PRP-02 §7).
                    WallpaperApplyProvider(AndroidWallpaperStore(context)),
                    // Tier 0: registers custom default sound files in MediaStore before the
                    // selection snapshot remaps ringtone/notification/alarm by role.
                    SoundFileApplyProvider(soundStore, soundFileRemap),
                    // Tier 0: sets default ringtone/notification/alarm via the "modify system
                    // settings" special access (Settings.System.canWrite). Built-ins are re-resolved
                    // to LOCAL URIs; custom files resolve only through the transfer-scoped remap.
                    SoundSelectionApplyProvider(soundStore, soundFileRemap),
                    // Tier 0: the bonded-Bluetooth roster (PRP-07 public-API approach). Phase 1
                    // SURFACES the list as a "re-pair each here" checklist and applies nothing —
                    // it never calls createBond (deferred to Phase 2) and carries no link keys
                    // (non-transferable). No platform dependency, so it cannot bond by construction.
                    BtPairingsApplyProvider(sinks.onRepairEntries),
                    // Tier 0: COURIER for a user-exported, app-encrypted backup (Signal/Molly/Aegis;
                    // PRP-06). portage relays the OPAQUE file the user picked — it NEVER decrypts,
                    // parses, or imports it, and never holds the passphrase. The apply path validates
                    // the typed header (derive-never-trust the advisory package/note), streams the
                    // opaque bytes to a user-visible location via [AndroidRelayHandoff], and surfaces
                    // a guided "open this in <app>" reminder. No app data is written by portage.
                    AppBackupRelayApplyProvider(
                        onPrompt = sinks.onRelayPrompt,
                        handoff = AndroidRelayHandoff(context)::write,
                    ),
                    // Explicit SAF-selected user files land in the public Downloads/Portage
                    // collection through MediaStore; no broad storage permission or path is trusted.
                    UserFileApplyProvider(writeFile = AndroidUserFileStore(context)::write),
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
            // Phase 5d-2: the user-driven opt-in dangerous-perm grant on the Done screen. degoogle: an
            // AdbRuntimePermissionGranter over the SAME process-scoped bridge, idle once the transfer is
            // done (connect→grant→disconnect in finally, AC-11); distinct from the apply provider's
            // auto-grant granter (that one is DEFAULT_SAFE-belt-filtered and runs inside the silent
            // install). play: RuntimePermissionGranter.NoOp — the Done-screen opt-in grants nothing.
            optInPermissionGranter = wiring.optInPermissionGranter,
            // Keeps the process alive + CPU awake for the item stream via a short-lived foreground
            // service so a screen-off can't reset the streaming socket mid-frame (#85).
            transferKeepAlive = ForegroundServiceKeepAlive(context),
        ) as T
    }
}
