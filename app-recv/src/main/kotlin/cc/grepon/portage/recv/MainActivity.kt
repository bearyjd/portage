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
import cc.grepon.portage.privileged.ShizukuPrivilegedOps
import cc.grepon.portage.providers.ApplyProviderRegistry
import cc.grepon.portage.providers.calendar.AndroidCalendarStore
import cc.grepon.portage.providers.calendar.CalendarApplyProvider
import cc.grepon.portage.providers.calllog.AndroidCallLogStore
import cc.grepon.portage.providers.calllog.CallLogApplyProvider
import cc.grepon.portage.providers.contacts.AndroidContactsStore
import cc.grepon.portage.providers.contacts.ContactsApplyProvider
import cc.grepon.portage.providers.inventory.AndroidInventorySource
import cc.grepon.portage.providers.inventory.AppInventoryApplyProvider
import cc.grepon.portage.providers.inventory.InstallAction
import cc.grepon.portage.providers.settings.AndroidSecureGlobalSettingsStore
import cc.grepon.portage.providers.settings.AndroidSystemSettingsStore
import cc.grepon.portage.providers.settings.SettingsApplyProvider
import cc.grepon.portage.providers.sms.AndroidSmsRoleGateway
import cc.grepon.portage.providers.sms.AndroidSmsStore
import cc.grepon.portage.providers.sms.SmsApplyProvider
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
        smsRoleCoordinator.requestLauncher = { intent -> smsRoleLauncher.launch(intent) }
        setContent {
            ReceiverApp(viewModel = viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system change-default prompt: re-check whether portage is still the
        // default SMS app so the in-app "restore" affordance clears once the role is handed back.
        viewModel.refreshSmsRoleStrand()
    }
}

private const val STAGING_DIR = "portage-staging"

/** Builds the ViewModel with the compiled Tier-0 apply registry (one provider per kind). */
private class ReceiverViewModelFactory(
    private val context: Context,
    private val smsRoleCoordinator: SmsRoleCoordinator,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val registryFactory = { onInstallActions: (List<InstallAction>) -> Unit ->
            val resolver = context.contentResolver
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
                    // Tier-0 SYSTEM keys write today. Tier-1 SECURE/GLOBAL keys go live once the
                    // user authorizes Shizuku and the one-shot WRITE_SECURE_SETTINGS grant lands;
                    // until then ShizukuPrivilegedOps reports the bridge unavailable and they
                    // self-skip. (The in-app "unlock secure settings" affordance is a follow-up.)
                    SettingsApplyProvider(
                        AndroidSystemSettingsStore(context),
                        AndroidSecureGlobalSettingsStore(context),
                        ShizukuPrivilegedOps(context),
                    ),
                ),
            )
        }
        @Suppress("UNCHECKED_CAST")
        return ReceiverViewModel(
            stagingDir = File(context.cacheDir, STAGING_DIR),
            smsRoleCoordinator = smsRoleCoordinator,
            applyRegistryFactory = registryFactory,
        ) as T
    }
}
