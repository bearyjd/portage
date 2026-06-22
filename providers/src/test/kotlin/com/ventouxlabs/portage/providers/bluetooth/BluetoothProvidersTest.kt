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

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.ventouxlabs.portage.model.Tier
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Hand-written fake of the [BluetoothStore] seam (mirrors FakeSoundStore / FakeInventorySource).
 * It models the sender's bonded-device roster as `BluetoothAdapter.getBondedDevices()` would
 * return it, plus the two reasons the read yields nothing: the adapter is off, or the
 * BLUETOOTH_CONNECT runtime permission is not granted. The seam keeps the providers JVM-testable
 * without any android.bluetooth dependency.
 */
private class FakeBluetoothStore(
    private val devices: List<BondedDevice> = emptyList(),
    /** Adapter present AND on (false ⇒ no hardware / BT disabled → nothing to read). */
    private val enabled: Boolean = true,
    /** Whether the BLUETOOTH_CONNECT runtime grant is held (false ⇒ read denied). */
    private val permitted: Boolean = true,
    /** When true, bondedDevices() throws — models a SecurityException from a revoked grant. */
    private val throwOnRead: Boolean = false,
) : BluetoothStore {
    override fun isReadable(): Boolean = enabled && permitted

    override fun bondedDevices(): List<BondedDevice> {
        if (throwOnRead) throw SecurityException("BLUETOOTH_CONNECT not granted")
        return devices
    }
}

class BluetoothProvidersTest {

    private val headphones = BondedDevice("AA:BB:CC:DD:EE:01", "WH-1000XM5", devType = 1, majorClass = 1024)
    private val watch = BondedDevice("AA:BB:CC:DD:EE:02", "Pixel Watch", devType = 2, majorClass = 1792)
    private val car = BondedDevice("AA:BB:CC:DD:EE:03", "Honda HFT", devType = 3, majorClass = 1056)

    // ---- model + wire shape ----

    @Test
    fun `BLUETOOTH_DEVICES kind is registered as a tier-0 wire kind`() {
        assertThat(ItemKind.BLUETOOTH_DEVICES.wire).isEqualTo("bluetooth.devices")
        assertThat(ItemKind.BLUETOOTH_DEVICES.tier).isEqualTo(Tier.TIER0)
    }

    @Test
    fun `roster round-trips through the codec`() {
        val roster = BtPairingRoster(listOf(headphones, watch, car))
        val encoded = BtRosterCodec.encode(roster)
        val decoded = BtRosterCodec.decode(ByteArrayInputStream(encoded.toByteArray(Charsets.UTF_8)))
        assertThat(decoded).isEqualTo(roster)
    }

    @Test
    fun `codec returns null on an unreadable payload`() {
        assertThat(BtRosterCodec.decode(ByteArrayInputStream("not json".toByteArray()))).isNull()
    }

    // ---- export ----

    private suspend fun exportPayload(store: FakeBluetoothStore): ByteArray {
        val out = ByteArrayOutputStream()
        BtPairingsExportProvider(store).exportTo(out)
        return out.toByteArray()
    }

    @Test
    fun `export reads the whole bonded roster`() = runTest {
        val decoded = BtRosterCodec.decode(
            ByteArrayInputStream(exportPayload(FakeBluetoothStore(listOf(headphones, watch, car)))),
        )
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.devices.map { it.address })
            .containsExactly(headphones.address, watch.address, car.address).inOrder()
        assertThat(decoded.devices.first().name).isEqualTo("WH-1000XM5")
        assertThat(decoded.devices.first().devType).isEqualTo(1)
        assertThat(decoded.devices.first().majorClass).isEqualTo(1024)
    }

    @Test
    fun `export available is true when at least one device is bonded`() = runTest {
        assertThat(BtPairingsExportProvider(FakeBluetoothStore(listOf(headphones))).available()).isTrue()
    }

    @Test
    fun `export available is false when nothing is bonded`() = runTest {
        assertThat(BtPairingsExportProvider(FakeBluetoothStore(devices = emptyList())).available()).isFalse()
    }

    @Test
    fun `export is empty when Bluetooth is off`() = runTest {
        // Adapter disabled ⇒ isReadable() false ⇒ no item enters the manifest, empty payload.
        val store = FakeBluetoothStore(devices = listOf(headphones), enabled = false)
        assertThat(BtPairingsExportProvider(store).available()).isFalse()
        assertThat(exportPayload(store)).isEmpty()
    }

    @Test
    fun `export is empty when the BLUETOOTH_CONNECT permission is not granted`() = runTest {
        val store = FakeBluetoothStore(devices = listOf(headphones), permitted = false)
        assertThat(BtPairingsExportProvider(store).available()).isFalse()
        assertThat(exportPayload(store)).isEmpty()
    }

    @Test
    fun `export is empty when the read throws`() = runTest {
        val store = FakeBluetoothStore(devices = listOf(headphones), throwOnRead = true)
        assertThat(BtPairingsExportProvider(store).available()).isFalse()
        assertThat(exportPayload(store)).isEmpty()
    }

    // ---- apply (Phase 1: list -> re-pair checklist; no createBond, no platform writes) ----

    private fun frameOf(vararg devices: BondedDevice): ByteArrayInputStream =
        ByteArrayInputStream(BtRosterCodec.encode(BtPairingRoster(devices.toList())).toByteArray(Charsets.UTF_8))

    @Test
    fun `apply surfaces every well-formed device as a re-pair entry`() = runTest {
        var entries: List<RePairEntry> = emptyList()
        val outcome = BtPairingsApplyProvider { entries = it }.apply(frameOf(headphones, watch, car))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(entries.map { it.address })
            .containsExactly(headphones.address, watch.address, car.address).inOrder()
        assertThat(entries.first().name).isEqualTo("WH-1000XM5")
        assertThat(outcome.detail).contains("3 to re-pair")
    }

    @Test
    fun `apply drops a malformed MAC and counts it`() = runTest {
        val bad = BondedDevice("not-a-mac", "Sketchy", devType = 1, majorClass = 0)
        var entries: List<RePairEntry> = emptyList()
        val outcome = BtPairingsApplyProvider { entries = it }.apply(frameOf(headphones, bad))

        assertThat(entries.map { it.address }).containsExactly(headphones.address)
        assertThat(outcome.detail).contains("1 dropped")
    }

    @Test
    fun `apply rejects every MAC shape that is not six hex octets`() {
        val hostile = listOf(
            "",                              // empty
            "AA:BB:CC:DD:EE",                // five octets
            "AA:BB:CC:DD:EE:FF:00",          // seven octets
            "AABBCCDDEEFF",                  // no separators
            "ZZ:BB:CC:DD:EE:FF",             // non-hex
            "AA-BB-CC-DD-EE-FF",             // wrong separator
            "AA:BB:CC:DD:EE:F",              // short final octet
            "content://x AA:BB:CC:DD:EE:FF", // smuggling junk in front
        )
        hostile.forEach { addr ->
            assertThat(RePairEntry.from(BondedDevice(addr, "X", devType = 1, majorClass = 0))).isNull()
        }
    }

    @Test
    fun `apply accepts upper and lower case hex MACs`() {
        assertThat(RePairEntry.from(BondedDevice("aa:bb:cc:dd:ee:ff", "lower", 1, 0))).isNotNull()
        assertThat(RePairEntry.from(BondedDevice("AA:BB:CC:DD:EE:FF", "upper", 1, 0))).isNotNull()
        assertThat(RePairEntry.from(BondedDevice("A0:1b:2C:3d:4E:5f", "mixed", 1, 0))).isNotNull()
    }

    @Test
    fun `apply drops a blank-name device so no empty row reaches the UI`() = runTest {
        val nameless = BondedDevice("AA:BB:CC:DD:EE:09", "   ", devType = 1, majorClass = 0)
        var entries: List<RePairEntry> = emptyList()
        val outcome = BtPairingsApplyProvider { entries = it }.apply(frameOf(headphones, nameless))

        assertThat(entries.map { it.address }).containsExactly(headphones.address)
        assertThat(outcome.detail).contains("1 dropped")
    }

    @Test
    fun `apply strips control characters from a name but keeps ordinary spaces`() {
        // Newlines/tabs/control chars are removed (they could break a UI row or smuggle layout);
        // an ordinary space inside the label survives. Input "Head set\tPro" -> "Head setPro":
        // the TAB is stripped, the space between "Head" and "set" stays.
        val noisy = BondedDevice("AA:BB:CC:DD:EE:0A", "Head set\tPro", devType = 1, majorClass = 0)
        val entry = RePairEntry.from(noisy)
        assertThat(entry).isNotNull()
        assertThat(entry!!.name).doesNotContain("\t")
        assertThat(entry.name).isEqualTo("Head setPro") // TAB gone, the ordinary space kept
    }

    @Test
    fun `apply truncates an over-long name to the display cap`() {
        val long = "x".repeat(500)
        val truncated = RePairEntry.from(BondedDevice("AA:BB:CC:DD:EE:0B", long, 1, 0))
        assertThat(truncated!!.name.length).isAtMost(RePairEntry.MAX_NAME_LENGTH)
    }

    @Test
    fun `apply dedupes by address so the checklist keys never collide`() = runTest {
        var entries: List<RePairEntry> = emptyList()
        // Same address twice (a hostile or buggy roster); only one entry must survive.
        BtPairingsApplyProvider { entries = it }
            .apply(frameOf(headphones, headphones.copy(name = "Same MAC, other label")))

        assertThat(entries.map { it.address }).containsExactly(headphones.address)
    }

    @Test
    fun `apply bounds the roster to a sane maximum entry count`() = runTest {
        val many = (1..5_000).map {
            BondedDevice("AA:BB:CC:DD:%02X:%02X".format(it shr 8 and 0xFF, it and 0xFF), "Dev $it", 1, 0)
        }
        var entries: List<RePairEntry> = emptyList()
        val outcome = BtPairingsApplyProvider { entries = it }
            .apply(ByteArrayInputStream(BtRosterCodec.encode(BtPairingRoster(many)).toByteArray(Charsets.UTF_8)))

        assertThat(entries.size).isAtMost(BtPairingsApplyProvider.MAX_ENTRIES)
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
    }

    @Test
    fun `apply on an empty roster yields no entries and an OK summary`() = runTest {
        var entries: List<RePairEntry> = emptyList()
        val outcome = BtPairingsApplyProvider { entries = it }.apply(frameOf())

        assertThat(entries).isEmpty()
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(outcome.detail).contains("0 to re-pair")
    }

    @Test
    fun `apply reports WRITE_ERROR and emits no entries on an unreadable payload`() = runTest {
        var called = false
        val outcome = BtPairingsApplyProvider { called = true }
            .apply(ByteArrayInputStream("garbage".toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(called).isFalse()
    }

    @Test
    fun `apply never bonds anything — Phase 1 only stores and presents the list`() = runTest {
        // The provider has no platform dependency at all: it cannot call createBond. This pins the
        // Phase-1 contract structurally — the only side effect is the onEntries callback.
        var entries: List<RePairEntry> = emptyList()
        val outcome = BtPairingsApplyProvider { entries = it }.apply(frameOf(headphones, watch))
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(entries).hasSize(2)
    }
}
