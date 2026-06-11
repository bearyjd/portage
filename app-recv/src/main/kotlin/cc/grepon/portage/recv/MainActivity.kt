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

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import cc.grepon.portage.providers.settings.AndroidSystemSettingsStore
import cc.grepon.portage.providers.settings.SettingsApplyProvider
import cc.grepon.portage.providers.sms.AndroidSmsRoleGateway
import cc.grepon.portage.providers.sms.AndroidSmsStore
import cc.grepon.portage.providers.sms.SmsApplyProvider
import cc.grepon.portage.recv.ui.ReceiverApp
import java.io.File

/**
 * Importer entry point. Real flow (portage-prp-prompt.md §7): scan QR → handshake → receive
 * manifest → single grouped checklist (SAFE pre-checked) → "Bring it over" → progress → done
 * summary (moved / app data isn't carried). [ReceiverApp] owns the whole Compose tree;
 * this Activity hosts it and assembles the Tier-0 apply registry over the Android stores.
 *
 * Tier 1 is an optional "Unlock advanced settings transfer (Shizuku)" step; everything in
 * Tier 0 works without it (DEVILS_ADVOCATE.md Q1).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ReceiverViewModel by viewModels {
        ReceiverViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sweep staging orphaned by a mid-transfer process death — staged payloads are
        // plaintext PII and must never outlive a single session.
        File(cacheDir, STAGING_DIR).deleteRecursively()
        setContent {
            ReceiverApp(viewModel = viewModel)
        }
    }
}

private const val STAGING_DIR = "portage-staging"

/** Builds the ViewModel with the compiled Tier-0 apply registry (one provider per kind). */
private class ReceiverViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val registryFactory = { onInstallActions: (List<InstallAction>) -> Unit ->
            val resolver = context.contentResolver
            ApplyProviderRegistry(
                listOf(
                    ContactsApplyProvider(AndroidContactsStore(resolver)),
                    CalendarApplyProvider(AndroidCalendarStore(resolver)),
                    CallLogApplyProvider(AndroidCallLogStore(resolver)),
                    // INERT BY DESIGN until the SMS role mini-project lands: the manifest
                    // declares no SMS role components, so isSelfDefault() is always false
                    // and apply() hard-skips. Pinned end-to-end by ReceiverViewModelTest
                    // ("a registered SMS provider is inert..."). Do NOT declare the SMS
                    // permissions as a quick fix — the role handoff gets its own review.
                    SmsApplyProvider(AndroidSmsStore(resolver), AndroidSmsRoleGateway(context)),
                    AppInventoryApplyProvider(AndroidInventorySource(context.packageManager), onInstallActions),
                    SettingsApplyProvider(AndroidSystemSettingsStore(context)),
                ),
            )
        }
        @Suppress("UNCHECKED_CAST")
        return ReceiverViewModel(
            stagingDir = File(context.cacheDir, STAGING_DIR),
            applyRegistryFactory = registryFactory,
        ) as T
    }
}
