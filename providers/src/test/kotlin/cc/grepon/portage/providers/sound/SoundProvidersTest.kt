/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.sound

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemStatus
import cc.grepon.portage.model.Tier
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Hand-written fake of the [SoundStore] seam (mirrors FakeSystemSettingsStore /
 * FakeWallpaperStore). It models a device's three default-sound roles as the SENDER sees them
 * (current URI + the built-in title that URI resolves to) and, independently, the set of
 * built-in titles the TARGET can resolve back to a LOCAL URI. The apply path must only ever
 * write a URI this fake resolves locally — never the carried wire URI verbatim.
 */
private class FakeSoundStore(
    /** role → the current default URI on this (sender) device. */
    private val current: MutableMap<SoundRole, String> = mutableMapOf(),
    /** role → the built-in title the current URI resolves to (null ⇒ a user-file URI). */
    private val titles: MutableMap<SoundRole, String?> = mutableMapOf(),
    /** (role, title) the TARGET can resolve to a local URI, and the URI it yields. */
    private val resolvable: MutableMap<Pair<SoundRole, String>, String> = mutableMapOf(),
    var writable: Boolean = true,
) : SoundStore {
    /** Every (role, uri) handed to setDefault, in call order. */
    val setCalls = mutableListOf<Pair<SoundRole, String>>()

    override fun read(role: SoundRole): String? = current[role]

    override fun titleOf(uri: String): String? =
        titles.entries.firstOrNull { current[it.key] == uri }?.value

    override fun resolveBuiltin(role: SoundRole, title: String): String? = resolvable[role to title]

    override fun canWrite(): Boolean = writable

    override fun setDefault(role: SoundRole, uri: String): Boolean {
        setCalls += role to uri
        return writable
    }
}

class SoundProvidersTest {

    private val builtinRingtone = "content://settings/system/ringtone"
    private val builtinNotification = "content://media/internal/audio/media/27"
    private val targetRingtoneUri = "content://media/internal/audio/media/8"
    private val targetNotificationUri = "content://media/internal/audio/media/12"

    // ---- model + wire shape ----

    @Test
    fun `SOUND_SELECTION kind is registered as a tier-0 wire kind`() {
        assertThat(ItemKind.SOUND_SELECTION.wire).isEqualTo("sound.selection")
        assertThat(ItemKind.SOUND_SELECTION.tier).isEqualTo(Tier.TIER0)
    }

    @Test
    fun `selection round-trips through the codec`() {
        val selection = SoundSelection(
            listOf(
                SoundChoice(SoundRole.RINGTONE, SoundSource.BUILTIN, builtinTitle = "Flutey Phone"),
                SoundChoice(SoundRole.NOTIFICATION, SoundSource.BUILTIN, builtinTitle = "Pixie Dust"),
            ),
        )
        val encoded = SoundCodec.encode(selection)
        val decoded = SoundCodec.decode(ByteArrayInputStream(encoded.toByteArray(Charsets.UTF_8)))
        assertThat(decoded).isEqualTo(selection)
    }

    @Test
    fun `codec returns null on an unreadable payload`() {
        assertThat(SoundCodec.decode(ByteArrayInputStream("not json".toByteArray()))).isNull()
    }

    // ---- export ----

    private suspend fun exportPayload(store: FakeSoundStore): ByteArray {
        val out = ByteArrayOutputStream()
        SoundSelectionExportProvider(store).exportTo(out)
        return out.toByteArray()
    }

    @Test
    fun `export reads all three role selections`() = runTest {
        val store = FakeSoundStore(
            current = mutableMapOf(
                SoundRole.RINGTONE to builtinRingtone,
                SoundRole.NOTIFICATION to builtinNotification,
                SoundRole.ALARM to "content://media/internal/audio/media/30",
            ),
            titles = mutableMapOf(
                SoundRole.RINGTONE to "Flutey Phone",
                SoundRole.NOTIFICATION to "Pixie Dust",
                SoundRole.ALARM to "Cesium",
            ),
        )
        val decoded = SoundCodec.decode(ByteArrayInputStream(exportPayload(store)))
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.choices.map { it.role })
            .containsExactly(SoundRole.RINGTONE, SoundRole.NOTIFICATION, SoundRole.ALARM)
        val byRole = decoded.choices.associateBy { it.role }
        assertThat(byRole.getValue(SoundRole.RINGTONE).source).isEqualTo(SoundSource.BUILTIN)
        assertThat(byRole.getValue(SoundRole.RINGTONE).builtinTitle).isEqualTo("Flutey Phone")
    }

    @Test
    fun `export omits roles that have no default set`() = runTest {
        val store = FakeSoundStore(
            current = mutableMapOf(SoundRole.RINGTONE to builtinRingtone),
            titles = mutableMapOf(SoundRole.RINGTONE to "Flutey Phone"),
        )
        val decoded = SoundCodec.decode(ByteArrayInputStream(exportPayload(store)))
        assertThat(decoded!!.choices.map { it.role }).containsExactly(SoundRole.RINGTONE)
    }

    @Test
    fun `Phase 1 exports a user-file backed role as UNSET so nothing dangles`() = runTest {
        // A role whose current URI resolves to NO built-in title (titleOf == null) is a user file;
        // in Phase 1 (no file transfer) it must not be carried as a resolvable selection.
        val store = FakeSoundStore(
            current = mutableMapOf(
                SoundRole.RINGTONE to builtinRingtone,
                SoundRole.NOTIFICATION to "content://media/external/audio/media/999",
            ),
            titles = mutableMapOf(
                SoundRole.RINGTONE to "Flutey Phone",
                SoundRole.NOTIFICATION to null, // user file → no built-in identity
            ),
        )
        val decoded = SoundCodec.decode(ByteArrayInputStream(exportPayload(store)))!!
        // The user-file role is dropped entirely (only resolvable built-ins ship in Phase 1).
        assertThat(decoded.choices.map { it.role }).containsExactly(SoundRole.RINGTONE)
    }

    @Test
    fun `export available is false when nothing is set`() = runTest {
        assertThat(SoundSelectionExportProvider(FakeSoundStore()).available()).isFalse()
    }

    @Test
    fun `export available is true when at least one built-in is set`() = runTest {
        val store = FakeSoundStore(
            current = mutableMapOf(SoundRole.RINGTONE to builtinRingtone),
            titles = mutableMapOf(SoundRole.RINGTONE to "Flutey Phone"),
        )
        assertThat(SoundSelectionExportProvider(store).available()).isTrue()
    }

    @Test
    fun `export is empty when the read throws`() = runTest {
        val store = object : SoundStore {
            override fun read(role: SoundRole): String? = throw SecurityException("denied")
            override fun titleOf(uri: String): String? = null
            override fun resolveBuiltin(role: SoundRole, title: String): String? = null
            override fun canWrite(): Boolean = true
            override fun setDefault(role: SoundRole, uri: String): Boolean = true
        }
        val out = ByteArrayOutputStream()
        SoundSelectionExportProvider(store).exportTo(out)
        assertThat(out.toByteArray()).isEmpty()
    }

    // ---- apply ----

    private fun frameOf(vararg choices: SoundChoice): ByteArrayInputStream =
        ByteArrayInputStream(SoundCodec.encode(SoundSelection(choices.toList())).toByteArray(Charsets.UTF_8))

    @Test
    fun `apply resolves each built-in locally and writes only the resolved URI`() = runTest {
        val store = FakeSoundStore(
            resolvable = mutableMapOf(
                (SoundRole.RINGTONE to "Flutey Phone") to targetRingtoneUri,
                (SoundRole.NOTIFICATION to "Pixie Dust") to targetNotificationUri,
            ),
        )
        val outcome = SoundSelectionApplyProvider(store).apply(
            frameOf(
                SoundChoice(SoundRole.RINGTONE, SoundSource.BUILTIN, builtinTitle = "Flutey Phone"),
                SoundChoice(SoundRole.NOTIFICATION, SoundSource.BUILTIN, builtinTitle = "Pixie Dust"),
            ),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        // Exactly the locally-resolved URIs were written — never a carried URI.
        assertThat(store.setCalls).containsExactly(
            SoundRole.RINGTONE to targetRingtoneUri,
            SoundRole.NOTIFICATION to targetNotificationUri,
        )
    }

    @Test
    fun `apply skips a built-in with no equivalent on the target without any write`() = runTest {
        // RINGTONE resolves; NOTIFICATION's title has no local match → that role is left at default.
        val store = FakeSoundStore(
            resolvable = mutableMapOf((SoundRole.RINGTONE to "Flutey Phone") to targetRingtoneUri),
        )
        val outcome = SoundSelectionApplyProvider(store).apply(
            frameOf(
                SoundChoice(SoundRole.RINGTONE, SoundSource.BUILTIN, builtinTitle = "Flutey Phone"),
                SoundChoice(SoundRole.NOTIFICATION, SoundSource.BUILTIN, builtinTitle = "Nonexistent"),
            ),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        // Only the resolvable role was written; the unresolvable one never reached setDefault.
        assertThat(store.setCalls).containsExactly(SoundRole.RINGTONE to targetRingtoneUri)
    }

    @Test
    fun `apply skips a USER_FILE role in Phase 1 because no backing file is delivered`() = runTest {
        val store = FakeSoundStore(
            resolvable = mutableMapOf((SoundRole.RINGTONE to "Flutey Phone") to targetRingtoneUri),
        )
        val outcome = SoundSelectionApplyProvider(store).apply(
            frameOf(
                SoundChoice(SoundRole.RINGTONE, SoundSource.BUILTIN, builtinTitle = "Flutey Phone"),
                SoundChoice(SoundRole.ALARM, SoundSource.USER_FILE, fileSha256 = "deadbeef"),
            ),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.setCalls).containsExactly(SoundRole.RINGTONE to targetRingtoneUri)
    }

    @Test
    fun `apply rejects a built-in choice with a blank title without writing`() = runTest {
        val store = FakeSoundStore(
            resolvable = mutableMapOf((SoundRole.RINGTONE to "Flutey Phone") to targetRingtoneUri),
        )
        val outcome = SoundSelectionApplyProvider(store).apply(
            frameOf(SoundChoice(SoundRole.RINGTONE, SoundSource.BUILTIN, builtinTitle = "  ")),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.setCalls).isEmpty()
    }

    @Test
    fun `apply skips all roles and hints when the modify-settings access is missing`() = runTest {
        val store = FakeSoundStore(
            resolvable = mutableMapOf((SoundRole.RINGTONE to "Flutey Phone") to targetRingtoneUri),
            writable = false,
        )
        val outcome = SoundSelectionApplyProvider(store).apply(
            frameOf(SoundChoice(SoundRole.RINGTONE, SoundSource.BUILTIN, builtinTitle = "Flutey Phone")),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(outcome.detail).contains("modify system settings")
        assertThat(store.setCalls).isEmpty()
    }

    @Test
    fun `apply reports WRITE_ERROR on an unreadable payload`() = runTest {
        val store = FakeSoundStore()
        val outcome = SoundSelectionApplyProvider(store).apply(ByteArrayInputStream("garbage".toByteArray()))
        assertThat(outcome.status).isEqualTo(ItemStatus.WRITE_ERROR)
        assertThat(store.setCalls).isEmpty()
    }

    // ---- URI hygiene (derive-never-trust the wire value) ----

    @Test
    fun `a resolved URI with an unexpected scheme is rejected without writing`() = runTest {
        // The target's resolveBuiltin returns a hostile/unexpected scheme — apply must NOT write it.
        val store = FakeSoundStore(
            resolvable = mutableMapOf((SoundRole.RINGTONE to "Flutey Phone") to "javascript:alert(1)"),
        )
        val outcome = SoundSelectionApplyProvider(store).apply(
            frameOf(SoundChoice(SoundRole.RINGTONE, SoundSource.BUILTIN, builtinTitle = "Flutey Phone")),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.setCalls).isEmpty()
    }

    @Test
    fun `a malformed resolved URI is rejected without writing`() = runTest {
        val store = FakeSoundStore(
            resolvable = mutableMapOf((SoundRole.RINGTONE to "Flutey Phone") to "   "),
        )
        val outcome = SoundSelectionApplyProvider(store).apply(
            frameOf(SoundChoice(SoundRole.RINGTONE, SoundSource.BUILTIN, builtinTitle = "Flutey Phone")),
        )
        assertThat(outcome.status).isEqualTo(ItemStatus.OK)
        assertThat(store.setCalls).isEmpty()
    }

    @Test
    fun `well-formed content and file URIs are accepted by the validator`() {
        assertThat(SoundUri.isAcceptable("content://media/internal/audio/media/8")).isTrue()
        assertThat(SoundUri.isAcceptable("file:///system/media/audio/ringtones/Flutey.ogg")).isTrue()
        assertThat(SoundUri.isAcceptable("javascript:alert(1)")).isFalse()
        assertThat(SoundUri.isAcceptable("http://evil.example/x")).isFalse()
        assertThat(SoundUri.isAcceptable("")).isFalse()
        assertThat(SoundUri.isAcceptable("   ")).isFalse()
    }
}
