/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.install

import cc.grepon.portage.providers.apk.ApkInstallFile
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * The JVM-testable Tier-0 `PackageInstaller` write core (ADR-006 D3/D6): copy each reconciled split's
 * bytes into its own session entry, fsync per entry. The Android session is faked via [SessionWriter].
 */
class PackageInstallerApkTest {

    /** A fake session: records each opened entry's name and the bytes written to it, plus fsync calls. */
    private class FakeSession : PackageInstallerApk.SessionWriter {
        val entries = linkedMapOf<String, ByteArrayOutputStream>()
        val fsynced = mutableListOf<OutputStream>()

        override fun openWrite(name: String, length: Long): OutputStream =
            ByteArrayOutputStream().also { entries[name] = it }

        override fun fsync(stream: OutputStream) { fsynced += stream }
    }

    private fun file(name: String, bytes: ByteArray): ApkInstallFile =
        ApkInstallFile(name = name, length = bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    @Test
    fun `each split is written to its own session entry named with a apk suffix`() {
        val session = FakeSession()
        val written = PackageInstallerApk.writeSplits(
            session,
            listOf(
                file("base", byteArrayOf(1, 2, 3, 4)),
                file("split_config.arm64_v8a", byteArrayOf(5, 6)),
            ),
        )
        assertThat(session.entries.keys).containsExactly("base.apk", "split_config.arm64_v8a.apk").inOrder()
        assertThat(session.entries["base.apk"]?.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat(session.entries["split_config.arm64_v8a.apk"]?.toByteArray()).isEqualTo(byteArrayOf(5, 6))
        assertThat(written).isEqualTo(6L)
    }

    @Test
    fun `every entry is fsynced before the session is committed`() {
        val session = FakeSession()
        PackageInstallerApk.writeSplits(
            session,
            listOf(file("base", byteArrayOf(1)), file("split_config.xxhdpi", byteArrayOf(2))),
        )
        // One fsync per split — the platform requires fsync before close.
        assertThat(session.fsynced).hasSize(2)
    }

    @Test
    fun `a base-only single APK writes exactly one entry`() {
        val session = FakeSession()
        PackageInstallerApk.writeSplits(session, listOf(file("base", ByteArray(10))))
        assertThat(session.entries.keys).containsExactly("base.apk")
    }
}
