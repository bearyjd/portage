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
import java.io.OutputStream

/**
 * The Tier-0 `PackageInstaller` write core (ADR-006 D3/D6), factored OUT of the Android
 * `PackageInstaller` API so it is JVM-testable. Given a multi-split [ApkInstallAction]'s files and a
 * [SessionWriter] (the Android session in production, a fake in tests), it copies each split's bytes
 * into its own session entry, fsyncing per entry — exactly the `openWrite → copy → fsync → close`
 * sequence a `PackageInstaller.Session` requires for a split-APK install.
 *
 * The bytes are read from the [ApkInstallFile.open] opener SYNCHRONOUSLY here, before the apply
 * provider wipes the staged files in its `finally` (stage → act → wipe) — so this MUST run inside the
 * `onApkInstall` callback, never deferred.
 */
object PackageInstallerApk {

    private const val CHUNK = 8 * 1024

    /**
     * One open session entry the writer can stream a named split into and fsync. The Android adapter
     * backs [openWrite] with `PackageInstaller.Session.openWrite(name, 0, length)` and [fsync] with
     * `session.fsync(stream)`; a fake records bytes in tests.
     */
    interface SessionWriter {
        /** Open a write stream for the split [name] sized to [length] bytes. */
        fun openWrite(name: String, length: Long): OutputStream

        /** Flush the just-written [stream] to the session (Android requires fsync before close). */
        fun fsync(stream: OutputStream)
    }

    /**
     * Write every [files] split into [writer], copying exactly [ApkInstallFile.length] bytes per split
     * and fsyncing each. Returns the total bytes written across all splits. The session entry name is
     * the split's wire-validated [ApkInstallFile.name] (the literal `"base"` or a validated split name —
     * already path-safe by construction via `ApkContainerValidation`); a `.apk` suffix is appended so
     * the session has distinct, well-formed entry names.
     */
    fun writeSplits(writer: SessionWriter, files: List<ApkInstallFile>): Long {
        var total = 0L
        for (file in files) {
            val entryName = "${file.name}.apk"
            val out = writer.openWrite(entryName, file.length)
            file.open().use { input ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
            writer.fsync(out)
            out.close()
        }
        return total
    }
}
