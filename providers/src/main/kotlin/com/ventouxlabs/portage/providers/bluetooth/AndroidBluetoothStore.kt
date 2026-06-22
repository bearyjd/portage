/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Thin `BluetoothAdapter` adapter behind [BluetoothStore] (Tier 0, PRP-07 public-API approach).
 *
 * The roster comes from the PUBLIC, NON-PRIVILEGED `BluetoothAdapter.getBondedDevices()` API,
 * guarded by the normal `BLUETOOTH_CONNECT` runtime permission — NO ADB bridge, NO privilege
 * escalation (the privileged `bt_config.conf` read PRP-07 originally assumed is DENIED to shell uid
 * on GOS anyway; see docs/prp/features/SPIKE-RESULTS-2026-06-12.md). This keeps :adb-bridge OUT of
 * portage-send (ADR-003, CI-enforced) — the sender reads its own paired roster with a normal grant.
 *
 * [isReadable] gates the read on three things being true: an adapter exists, it is ON, and the
 * `BLUETOOTH_CONNECT` grant is held. When any is false the provider degrades to "nothing to send"
 * (an absent manifest item) rather than throwing — the Tier-0 graceful-degrade contract.
 *
 * The roster is a list of name + MAC + type/class ONLY. Link keys / bond secrets are never read
 * (the public API does not expose them), and the roster is never logged (mild fingerprinting risk).
 */
class AndroidBluetoothStore(private val context: Context) : BluetoothStore {

    private fun adapter(): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasConnectPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun isReadable(): Boolean = runCatching {
        val adapter = adapter() ?: return@runCatching false
        adapter.isEnabled && hasConnectPermission()
    }.getOrDefault(false)

    override fun bondedDevices(): List<BondedDevice> {
        val adapter = adapter() ?: return emptyList()
        // getBondedDevices() requires BLUETOOTH_CONNECT; isReadable() gates it, but a race that
        // revokes the grant still surfaces as a SecurityException the export path catches.
        val bonded = adapter.bondedDevices ?: return emptyList()
        return bonded.map { device ->
            BondedDevice(
                address = device.address.orEmpty(),
                name = device.name.orEmpty(),
                devType = runCatching { device.type }.getOrDefault(0),
                majorClass = runCatching { device.bluetoothClass?.majorDeviceClass ?: 0 }.getOrDefault(0),
            )
        }
    }
}
