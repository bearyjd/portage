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
import cc.grepon.portage.settings.Namespace
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

private class FakeSecureGlobalSettingsStore(
    private val values: MutableMap<Pair<Namespace, String>, String> = mutableMapOf(),
    var writable: Boolean = false,
) : SecureGlobalSettingsStore {
    val writes = mutableMapOf<Pair<Namespace, String>, String>()

    override fun read(namespace: Namespace, name: String): String? = values[namespace to name]
    override fun canWrite(): Boolean = writable
    override fun write(namespace: Namespace, name: String, value: String): Boolean {
        if (!writable) return false
        writes[namespace to name] = value
        return true
    }
}

/**
 * Models the one-shot grant: when [outcome] is GRANTED it flips [storeToGrant]'s `canWrite`,
 * mirroring `pm grant WRITE_SECURE_SETTINGS` making Settings.Secure/Global writable. The default
 * matches an unwired grant path (no bridge available), so Tier-1 keys self-skip.
 */
private class FakeTierOneGrant(
    private val outcome: TierOneGrant.Outcome = TierOneGrant.Outcome.UNAVAILABLE,
    private val storeToGrant: FakeSecureGlobalSettingsStore? = null,
) : TierOneGrant {
    var grantCalls = 0
        private set

    override suspend fun ensureWriteSecureSettingsGranted(): TierOneGrant.Outcome {
        grantCalls++
        if (outcome == TierOneGrant.Outcome.GRANTED) storeToGrant?.writable = true
        return outcome
    }
}

class SettingsProvidersTest {

    private fun payload(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val snapshot = SettingsSnapshot(entries.map { SettingEntry(it.first, it.second) })
        return ByteArrayInputStream(SettingsCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
    }

    private fun applyProvider(
        system: FakeSystemSettingsStore,
        secureGlobal: FakeSecureGlobalSettingsStore = FakeSecureGlobalSettingsStore(),
        grant: FakeTierOneGrant = FakeTierOneGrant(),
    ) = SettingsApplyProvider(system, secureGlobal, grant)

    // --- Export side ---

    @Test
    fun `export reads SAFE keys across the system and secure or global namespaces`() = runTest {
        val system = FakeSystemSettingsStore(
            mutableMapOf(
                "font_scale" to "1.15",        // SAFE SYSTEM T0 — exported
                "screen_brightness" to "183",  // DEVICE_SPECIFIC — never read out
                "volume_alarm" to "5",         // RISKY — not in the default sync set
            ),
        )
        val secureGlobal = FakeSecureGlobalSettingsStore(
            mutableMapOf(
                (Namespace.SECURE to "ui_night_mode") to "2",          // SAFE SECURE T1 — exported
                (Namespace.GLOBAL to "window_animation_scale") to "0.5", // SAFE GLOBAL T1 — exported
            ),
        )
        val out = ByteArrayOutputStream()
        val provider = SettingsExportProvider(system, secureGlobal)

        assertThat(provider.available()).isTrue()
        provider.exportTo(out)

        val names = SettingsCodec.decode(ByteArrayInputStream(out.toByteArray()))!!.entries.map { it.name }
        assertThat(names).containsAtLeast("font_scale", "ui_night_mode", "window_animation_scale")
        assertThat(names).doesNotContain("screen_brightness")
        assertThat(names).doesNotContain("volume_alarm")
    }

    @Test
    fun `export is unavailable when no allowlisted key has a value`() = runTest {
        val provider = SettingsExportProvider(FakeSystemSettingsStore(), FakeSecureGlobalSettingsStore())
        assertThat(provider.available()).isFalse()
    }

    // --- Apply side: the allowlist is the boundary (DEVILS_ADVOCATE Q2) ---

    @Test
    fun `apply writes a valid SAFE system key`() = runTest {
        val store = FakeSystemSettingsStore()
        val outcome = applyProvider(store).apply(payload("font_scale" to "1.15"))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.writes).containsExactly("font_scale", "1.15")
    }

    @Test
    fun `apply skips keys absent from the allowlist even if they exist on the device`() = runTest {
        val store = FakeSystemSettingsStore()
        val outcome = applyProvider(store)
            .apply(payload("some_vendor_key" to "1", "font_scale" to "1.0"))

        assertThat(store.writes.keys).containsExactly("font_scale")
        assertThat(outcome.detail).contains("skipped 1")
    }

    @Test
    fun `apply skips RISKY and DEVICE_SPECIFIC keys — SAFE only, no opt-in path here`() = runTest {
        val store = FakeSystemSettingsStore()
        applyProvider(store).apply(
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
        applyProvider(store).apply(
            payload(
                "font_scale" to "9.0",            // outside FloatRange(0.85, 1.30)
                "screen_brightness_mode" to "7",  // outside IntEnum(0, 1)
                "time_12_24" to "13",             // outside StringEnum(12, 24)
            ),
        )

        assertThat(store.writes).isEmpty()
    }

    @Test
    fun `apply without the modify-system-settings grant is SKIPPED, not a crash`() = runTest {
        val store = FakeSystemSettingsStore(writable = false)
        val outcome = applyProvider(store).apply(payload("font_scale" to "1.0"))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(store.writes).isEmpty()
    }

    @Test
    fun `an unreadable payload is a WRITE_ERROR`() = runTest {
        val outcome = applyProvider(FakeSystemSettingsStore())
            .apply(ByteArrayInputStream("garbage".toByteArray()))

        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
    }

    // --- Apply side: Tier-1 SECURE/GLOBAL data path (grant architecture, ADR-001) ---

    @Test
    fun `apply writes a Tier-1 secure key when the grant is already held`() = runTest {
        val system = FakeSystemSettingsStore()
        val secureGlobal = FakeSecureGlobalSettingsStore(writable = true) // grant persisted
        val grant = FakeTierOneGrant()

        val outcome = applyProvider(system, secureGlobal, grant).apply(payload("ui_night_mode" to "2"))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(secureGlobal.writes).containsExactly(Namespace.SECURE to "ui_night_mode", "2")
        assertThat(grant.grantCalls).isEqualTo(0) // grant path not consulted when already writable
    }

    @Test
    fun `apply attempts the one-shot grant once, then writes Tier-1 when granted`() = runTest {
        val system = FakeSystemSettingsStore()
        val secureGlobal = FakeSecureGlobalSettingsStore(writable = false)
        val grant = FakeTierOneGrant(TierOneGrant.Outcome.GRANTED, secureGlobal)

        val outcome = applyProvider(system, secureGlobal, grant).apply(payload("ui_night_mode" to "2"))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(secureGlobal.writes).containsExactly(Namespace.SECURE to "ui_night_mode", "2")
        assertThat(grant.grantCalls).isEqualTo(1)
    }

    @Test
    fun `apply skips Tier-1 keys when the privilege bridge is unavailable`() = runTest {
        val system = FakeSystemSettingsStore()
        val secureGlobal = FakeSecureGlobalSettingsStore(writable = false)
        val grant = FakeTierOneGrant(TierOneGrant.Outcome.UNAVAILABLE)

        val outcome = applyProvider(system, secureGlobal, grant).apply(payload("ui_night_mode" to "2"))

        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(secureGlobal.writes).isEmpty()
        assertThat(grant.grantCalls).isEqualTo(1)
    }

    @Test
    fun `apply routes secure and global keys to their own namespaces`() = runTest {
        val system = FakeSystemSettingsStore()
        val secureGlobal = FakeSecureGlobalSettingsStore(writable = true)

        applyProvider(system, secureGlobal).apply(
            payload("ui_night_mode" to "2", "window_animation_scale" to "0.5"),
        )

        assertThat(secureGlobal.writes).containsExactly(
            Namespace.SECURE to "ui_night_mode", "2",
            Namespace.GLOBAL to "window_animation_scale", "0.5",
        )
    }

    @Test
    fun `apply rejects a Tier-1 value the catalog validator refuses, even with the grant`() = runTest {
        val system = FakeSystemSettingsStore()
        val secureGlobal = FakeSecureGlobalSettingsStore(writable = true)

        applyProvider(system, secureGlobal)
            .apply(payload("window_animation_scale" to "5.0")) // outside FloatRange(0, 1)

        assertThat(secureGlobal.writes).isEmpty()
    }

    @Test
    fun `apply writes Tier-0 and Tier-1 keys together`() = runTest {
        val system = FakeSystemSettingsStore()
        val secureGlobal = FakeSecureGlobalSettingsStore(writable = true)

        val outcome = applyProvider(system, secureGlobal)
            .apply(payload("font_scale" to "1.0", "ui_night_mode" to "2"))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(system.writes).containsExactly("font_scale", "1.0")
        assertThat(secureGlobal.writes).containsExactly(Namespace.SECURE to "ui_night_mode", "2")
    }

    @Test
    fun `apply applies Tier-0 yet flags the missing Tier-1 grant on a mixed payload`() = runTest {
        // font_scale (T0, writable) applies; ui_night_mode (T1) is grant-blocked. The overall
        // status is OK (something applied), but the detail must still surface the grant gap so
        // the done-summary does not silently swallow it.
        val system = FakeSystemSettingsStore()                       // writable
        val secureGlobal = FakeSecureGlobalSettingsStore(writable = false)
        val grant = FakeTierOneGrant(TierOneGrant.Outcome.UNAVAILABLE)

        val outcome = applyProvider(system, secureGlobal, grant)
            .apply(payload("font_scale" to "1.0", "ui_night_mode" to "2"))

        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(system.writes).containsExactly("font_scale", "1.0")
        assertThat(secureGlobal.writes).isEmpty()
        assertThat(outcome.detail).contains("secure-settings grant")
    }

    @Test
    fun `apply refuses a RISKY Tier-1 key even when the secure-settings grant is held`() = runTest {
        // default_input_method is SECURE + T1_GRANT but classified RISKY. A VALID value that
        // passes its pattern validator must still be refused — the SAFE-only gate, not the
        // validator, is what stops it (THREAT_MODEL §10; the more dangerous RISKY members live
        // on the Tier-1 seam).
        val secureGlobal = FakeSecureGlobalSettingsStore(writable = true)

        applyProvider(FakeSystemSettingsStore(), secureGlobal)
            .apply(payload("default_input_method" to "com.example/.ExampleIme"))

        assertThat(secureGlobal.writes).isEmpty()
    }
}
