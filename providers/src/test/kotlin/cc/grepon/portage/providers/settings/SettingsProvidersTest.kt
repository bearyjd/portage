/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.settings

import cc.grepon.portage.model.ItemStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private class FakeSystemSettingsStore(
    private val values: MutableMap<String, String> = mutableMapOf(),
    var writable: Boolean = true,
) : SystemSettingsStore {
    val writes = mutableMapOf<String, String>()

    override fun read(name: String): String? = values[name]
    override fun canWrite(): Boolean = writable
    override fun write(name: String, value: String): Boolean {
        if (!writable) return false
        writes[name] = value
        return true
    }
}

class SettingsProvidersTest {

    private fun payload(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val snapshot = SettingsSnapshot(entries.map { SettingEntry(it.first, it.second) })
        return ByteArrayInputStream(SettingsCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
    }

    // --- Export side ---

    @Test
    fun `export reads only SAFE Tier-0 SYSTEM keys from the allowlist`() = runTest {
        val store = FakeSystemSettingsStore(
            mutableMapOf(
                "font_scale" to "1.15",              // SAFE SYSTEM T0
                "screen_brightness" to "183",        // DEVICE_SPECIFIC — must never be read out
                "volume_alarm" to "5",               // RISKY — not in the default sync set
                "ui_night_mode" to "2",              // SAFE but SECURE namespace (Tier 1)
            ),
        )
        val out = ByteArrayOutputStream()
        val provider = SettingsExportProvider(store)

        assertThat(provider.available()).isTrue()
        provider.exportTo(out)

        val snapshot = SettingsCodec.decode(ByteArrayInputStream(out.toByteArray()))
        val names = snapshot!!.entries.map { it.name }
        assertThat(names).contains("font_scale")
        assertThat(names).doesNotContain("screen_brightness")
        assertThat(names).doesNotContain("volume_alarm")
        assertThat(names).doesNotContain("ui_night_mode")
    }

    @Test
    fun `export is unavailable when no allowlisted key has a value`() = runTest {
        assertThat(SettingsExportProvider(FakeSystemSettingsStore()).available()).isFalse()
    }

    // --- Apply side: the allowlist is the boundary (DEVILS_ADVOCATE Q2) ---

    @Test
    fun `apply writes a valid SAFE system key`() = runTest {
        val store = FakeSystemSettingsStore()
        val outcome = SettingsApplyProvider(store).apply(payload("font_scale" to "1.15"))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.writes).containsExactly("font_scale", "1.15")
    }

    @Test
    fun `apply skips keys absent from the allowlist even if they exist on the device`() = runTest {
        val store = FakeSystemSettingsStore()
        val outcome = SettingsApplyProvider(store)
            .apply(payload("some_vendor_key" to "1", "font_scale" to "1.0"))

        assertThat(store.writes.keys).containsExactly("font_scale")
        assertThat(outcome.detail).contains("skipped 1")
    }

    @Test
    fun `apply skips RISKY and DEVICE_SPECIFIC keys — SAFE only, no opt-in path here`() = runTest {
        val store = FakeSystemSettingsStore()
        SettingsApplyProvider(store).apply(
            payload(
                "volume_alarm" to "5",         // RISKY (valid value!) — still refused
                "screen_brightness" to "120",  // DEVICE_SPECIFIC trap
                "ringtone" to "content://x",   // DEVICE_SPECIFIC trap
            ),
        )

        assertThat(store.writes).isEmpty()
    }

    @Test
    fun `apply rejects values the catalog validator refuses`() = runTest {
        val store = FakeSystemSettingsStore()
        SettingsApplyProvider(store).apply(
            payload(
                "font_scale" to "9.0",            // outside FloatRange(0.85, 1.30)
                "screen_brightness_mode" to "7",  // outside IntEnum(0, 1)
                "time_12_24" to "13",             // outside StringEnum(12, 24)
            ),
        )

        assertThat(store.writes).isEmpty()
    }

    @Test
    fun `apply skips a SECURE-namespace key smuggled under a SYSTEM payload`() = runTest {
        val store = FakeSystemSettingsStore()
        // ui_night_mode is SAFE but lives in SECURE (Tier 1) — the SYSTEM-namespace lookup
        // must not find it.
        SettingsApplyProvider(store).apply(payload("ui_night_mode" to "2"))

        assertThat(store.writes).isEmpty()
    }

    @Test
    fun `apply without the write-settings grant is SKIPPED, not a crash`() = runTest {
        val store = FakeSystemSettingsStore(writable = false)
        val outcome = SettingsApplyProvider(store).apply(payload("font_scale" to "1.0"))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.writes).isEmpty()
    }

    @Test
    fun `an unreadable payload is a WRITE_ERROR`() = runTest {
        val outcome = SettingsApplyProvider(FakeSystemSettingsStore())
            .apply(ByteArrayInputStream("garbage".toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
    }
}
