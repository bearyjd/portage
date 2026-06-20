/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.apk

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Fixture APK bytes. The codec carries OPAQUE file bytes by length only — never parses a real APK —
 * so synthetic byte patterns are sufficient and let a test assert byte-exact reconstruction.
 */
private fun bytesOf(seed: Int, size: Int): ByteArray = ByteArray(size) { (it + seed).toByte() }

/** Build an [ApkSourceFile] whose declared length matches the bytes it will stream. */
private fun sourceFile(
    name: String,
    role: ApkFileRole,
    bytes: ByteArray,
    abi: String? = null,
    density: String? = null,
    lang: String? = null,
): ApkSourceFile {
    val entry = ApkFileEntry(name, role, abi, density, lang, bytes.size.toLong())
    return ApkSourceFile(entry) { ByteArrayInputStream(bytes) }
}

class ApkCodecTest {

    // ---- streamed round-trip via the production read path (readHeaderFrom/readEntryFrom/streamBlob) ----

    /**
     * Re-read a written container using ONLY the streaming primitives (NOT [ApkCodec.readFrom]) so the
     * test exercises the same path production uses, asserting byte-exact reconstruction of every file
     * plus the header (incl. capturedPermissions) and per-split tags. ADR-006 AC-2.
     */
    private fun streamRoundTrip(
        header: ApkContainerHeader,
        sources: List<ApkSourceFile>,
    ): Pair<ApkContainerHeader, List<DecodedApkFile>> {
        val framed = ByteArrayOutputStream().use { out ->
            ApkCodec.writeContainer(out, header, sources)
            out.toByteArray()
        }
        val input: InputStream = ByteArrayInputStream(framed)
        val decodedHeader = ApkCodec.readHeaderFrom(input)
        assertThat(decodedHeader).isNotNull()
        val files = (0 until decodedHeader!!.fileCount).map {
            val entry = ApkCodec.readEntryFrom(input)
            assertThat(entry).isNotNull()
            val blob = ByteArrayOutputStream()
            val written = ApkCodec.streamBlob(input, blob, entry!!.length)
            assertThat(written).isEqualTo(entry.length)
            DecodedApkFile(entry, blob.toByteArray())
        }
        return decodedHeader to files
    }

    @Test
    fun `base-only container streams back byte-exact through the production read path`() {
        val base = bytesOf(seed = 1, size = 5000)
        val header = ApkContainerHeader("com.example.app", versionCode = 42L, fileCount = 1)
        val (decodedHeader, files) = streamRoundTrip(
            header,
            listOf(sourceFile("base", ApkFileRole.BASE, base)),
        )

        assertThat(decodedHeader).isEqualTo(header)
        assertThat(files).hasSize(1)
        assertThat(files.single().entry.name).isEqualTo("base")
        assertThat(files.single().entry.role).isEqualTo(ApkFileRole.BASE)
        assertThat(files.single().bytes).isEqualTo(base)
    }

    @Test
    fun `base plus many splits stream back byte-exact with per-split tags and captured permissions`() {
        val base = bytesOf(seed = 1, size = 9000) // > one 8 KiB chunk to exercise chunked copy
        val abi = bytesOf(seed = 2, size = 4096)
        val density = bytesOf(seed = 3, size = 1)
        val lang = bytesOf(seed = 4, size = 0) // a zero-length split is still framed correctly
        val sources = listOf(
            sourceFile("base", ApkFileRole.BASE, base),
            sourceFile("config.arm64_v8a", ApkFileRole.CONFIG, abi, abi = "arm64_v8a"),
            sourceFile("config.xxhdpi", ApkFileRole.CONFIG, density, density = "xxhdpi"),
            sourceFile("config.en", ApkFileRole.LANGUAGE, lang, lang = "en"),
        )
        val header = ApkContainerHeader(
            packageName = "com.example.app",
            versionCode = 7L,
            fileCount = sources.size,
            capturedPermissions = listOf("android.permission.INTERNET", "android.permission.CAMERA"),
        )

        val (decodedHeader, files) = streamRoundTrip(header, sources)

        assertThat(decodedHeader).isEqualTo(header)
        assertThat(decodedHeader.capturedPermissions)
            .containsExactly("android.permission.INTERNET", "android.permission.CAMERA").inOrder()
        assertThat(files.map { it.entry }).isEqualTo(sources.map { it.entry })
        // Byte-exact reconstruction of every file (including the > 8 KiB base and the empty lang split).
        assertThat(files[0].bytes).isEqualTo(base)
        assertThat(files[1].bytes).isEqualTo(abi)
        assertThat(files[2].bytes).isEqualTo(density)
        assertThat(files[3].bytes).isEqualTo(lang)
        // Per-split advisory tags survive the wire.
        assertThat(files[1].entry.abi).isEqualTo("arm64_v8a")
        assertThat(files[2].entry.density).isEqualTo("xxhdpi")
        assertThat(files[3].entry.lang).isEqualTo("en")
    }

    @Test
    fun `readFrom test helper materializes a byte-exact round-trip`() {
        val base = bytesOf(seed = 9, size = 200)
        val split = bytesOf(seed = 8, size = 200)
        val sources = listOf(
            sourceFile("base", ApkFileRole.BASE, base),
            sourceFile("config.x86_64", ApkFileRole.CONFIG, split, abi = "x86_64"),
        )
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = sources.size)
        val out = ByteArrayOutputStream()
        ApkCodec.writeContainer(out, header, sources)

        val container = ApkCodec.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(container).isNotNull()
        assertThat(container!!.header).isEqualTo(header)
        assertThat(container.files).hasSize(2)
        assertThat(container.files[0].bytes).isEqualTo(base)
        assertThat(container.files[1].bytes).isEqualTo(split)
    }

    // ---- codec failure modes ----

    @Test
    fun `readHeaderFrom returns null on a payload with no header line`() {
        // No newline anywhere → EOF-before-newline.
        assertThat(ApkCodec.readHeaderFrom(ByteArrayInputStream("not-a-line-no-newline".toByteArray()))).isNull()
    }

    @Test
    fun `readHeaderFrom returns null when the header line exceeds the runaway guard`() {
        // A 5 KiB header line with NO newline before the 4 KiB cap is refused as malformed.
        val oversize = ByteArray(5 * 1024) { 'a'.code.toByte() } // no '\n'
        assertThat(ApkCodec.readHeaderFrom(ByteArrayInputStream(oversize))).isNull()
    }

    @Test
    fun `readEntryFrom returns null on an unparseable entry line`() {
        val payload = "{\"not\":\"an entry\"}\n".toByteArray()
        assertThat(ApkCodec.readEntryFrom(ByteArrayInputStream(payload))).isNull()
    }

    @Test
    fun `readFrom returns null when a file is truncated below its declared length`() {
        // Header says one 100-byte file but only 10 bytes follow → streamed count disagrees.
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = 1)
        val entry = ApkFileEntry("base", ApkFileRole.BASE, length = 100L)
        val out = ByteArrayOutputStream()
        out.write(jsonLine(ApkContainerHeader.serializer(), header))
        out.write(jsonLine(ApkFileEntry.serializer(), entry))
        out.write(ByteArray(10)) // truncated body
        assertThat(ApkCodec.readFrom(ByteArrayInputStream(out.toByteArray()))).isNull()
    }

    // ---- validation: fileCount bound, negative length, BASE cardinality ----

    @Test
    fun `header with fileCount of zero is rejected`() {
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = 0)
        assertThat(ApkContainerValidation.validatedHeaderOrNull(header)).isNull()
    }

    @Test
    fun `header with fileCount above MAX_APK_FILES is rejected`() {
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = ApkContainerValidation.MAX_APK_FILES + 1)
        assertThat(ApkContainerValidation.validatedHeaderOrNull(header)).isNull()
    }

    @Test
    fun `header at the MAX_APK_FILES boundary and a plausible package is accepted`() {
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = ApkContainerValidation.MAX_APK_FILES)
        assertThat(ApkContainerValidation.validatedHeaderOrNull(header)).isEqualTo(header)
    }

    @Test
    fun `header whose packageName is not a plausible package is rejected`() {
        val header = ApkContainerHeader("not a package", 1L, fileCount = 1)
        assertThat(ApkContainerValidation.validatedHeaderOrNull(header)).isNull()
    }

    @Test
    fun `header with a negative versionCode is rejected`() {
        val header = ApkContainerHeader("com.example.app", versionCode = -1L, fileCount = 1)
        assertThat(ApkContainerValidation.validatedHeaderOrNull(header)).isNull()
    }

    @Test
    fun `an entry with a negative length is rejected`() {
        val entry = ApkFileEntry("base", ApkFileRole.BASE, length = -1L)
        assertThat(ApkContainerValidation.validatedEntryOrNull(entry)).isNull()
    }

    @Test
    fun `a base-named entry that is not BASE-role is rejected`() {
        val entry = ApkFileEntry("base", ApkFileRole.CONFIG, length = 1L)
        assertThat(ApkContainerValidation.validatedEntryOrNull(entry)).isNull()
    }

    @Test
    fun `a BASE-role entry that is not base-named is rejected`() {
        val entry = ApkFileEntry("config.arm64_v8a", ApkFileRole.BASE, length = 1L)
        assertThat(ApkContainerValidation.validatedEntryOrNull(entry)).isNull()
    }

    @Test
    fun `entries with zero BASE are rejected`() {
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = 1)
        val entries = listOf(ApkFileEntry("config.arm64_v8a", ApkFileRole.CONFIG, abi = "arm64_v8a", length = 1L))
        assertThat(ApkContainerValidation.validatedEntriesOrNull(header, entries)).isNull()
    }

    @Test
    fun `entries with multiple BASE are rejected`() {
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = 2)
        val entries = listOf(
            ApkFileEntry("base", ApkFileRole.BASE, length = 1L),
            // Two base files cannot both validate (the second fails the base-name/role gate anyway),
            // but even a hypothetical second BASE-role-and-named line is refused by the cardinality check.
            ApkFileEntry("base", ApkFileRole.BASE, length = 1L),
        )
        assertThat(ApkContainerValidation.validatedEntriesOrNull(header, entries)).isNull()
    }

    @Test
    fun `entries whose size disagrees with fileCount are rejected`() {
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = 2)
        val entries = listOf(ApkFileEntry("base", ApkFileRole.BASE, length = 1L))
        assertThat(ApkContainerValidation.validatedEntriesOrNull(header, entries)).isNull()
    }

    @Test
    fun `a well-formed base-plus-split entry list validates`() {
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = 2)
        val entries = listOf(
            ApkFileEntry("base", ApkFileRole.BASE, length = 100L),
            ApkFileEntry("config.arm64_v8a", ApkFileRole.CONFIG, abi = "arm64_v8a", length = 50L),
        )
        assertThat(ApkContainerValidation.validatedEntriesOrNull(header, entries)).isEqualTo(entries)
    }

    // ---- split-name injection guard (ADR-006 AC-6b) ----

    @Test
    fun `the split-name guard rejects path traversal, separators, shell metacharacters, dotdot, leading dot, leading dash, and empty`() {
        val hostile = listOf(
            "../../etc/x",   // path traversal
            "a;rm -rf /",    // shell metacharacters + whitespace
            "a/b",           // forward slash separator
            "a\\b",          // backslash separator
            "..",            // parent dir (defence-in-depth; also blocked by leading-dot rule)
            ".",             // current dir (defence-in-depth; also blocked by leading-dot rule)
            "",              // empty
            "a b",           // whitespace
            "name\u0000",    // embedded NUL control char
            "name ",         // trailing space (not in allowlist)
            "name\n",        // newline
            "\$(whoami)",    // command substitution
            "a|b",           // pipe
            ".hidden",       // leading dot (tightened grammar, Fix 1)
            "-rf",           // leading dash (tightened grammar, Fix 1)
        )
        for (name in hostile) {
            assertThat(ApkContainerValidation.validatedSplitNameOrNull(name)).isNull()
        }
    }

    @Test
    fun `the split-name guard accepts base and legitimate split names`() {
        val legit = listOf("base", "config.arm64_v8a", "config.xxhdpi", "config.en", "split_config.armeabi-v7a", "feature_dynamic")
        for (name in legit) {
            assertThat(ApkContainerValidation.validatedSplitNameOrNull(name)).isEqualTo(name)
        }
    }

    // ---- adversarial codec tests (security M1 / L1) ----

    @Test
    fun `entry list whose declared lengths overflow to negative is documented at the validator boundary`() {
        // Two entries each declaring Long.MAX_VALUE / 2 + 1; their sum overflows to negative.
        // Individual entry lengths are non-negative, so validatedEntryOrNull accepts each one;
        // the aggregate overflow is the receiver-side streaming apply path's responsibility
        // (MAX_APK_TOTAL_BYTES), not the per-entry validator's. This test pins that boundary.
        val halfMax = Long.MAX_VALUE / 2 + 1
        val header = ApkContainerHeader("com.example.app", 1L, fileCount = 2)
        val entries = listOf(
            ApkFileEntry("base", ApkFileRole.BASE, length = halfMax),
            ApkFileEntry("config.x86_64", ApkFileRole.CONFIG, abi = "x86_64", length = halfMax),
        )
        assertThat(entries.sumOf { it.length }).isLessThan(0L) // confirm overflow
        // Per-entry validator: each length >= 0 individually, so entries pass.
        assertThat(ApkContainerValidation.validatedEntriesOrNull(header, entries)).isNotNull()
    }

    @Test
    fun `streamBlob returns actual written count when source is truncated below declared length`() {
        // Production apply path must detect written != expectedBytes and reject the container.
        val truncated = java.io.ByteArrayInputStream(ByteArray(10))
        val sink = ByteArrayOutputStream()
        val written = ApkCodec.streamBlob(truncated, sink, expectedBytes = 100L)
        assertThat(written).isEqualTo(10L)
        assertThat(written).isLessThan(100L)
    }

    @Test
    fun `readHeaderFrom accepts a header padded with unknown fields within the 4 KiB guard`() {
        // ignoreUnknownKeys = true in JsonLines.format: unknown fields are silently dropped.
        val padding = "\"x\":\"" + "a".repeat(100) + "\","
        val json = "{\"packageName\":\"com.example.app\",\"versionCode\":1,\"fileCount\":1,$padding\"capturedPermissions\":[]}\n"
        assertThat(json.length).isLessThan(4 * 1024)
        val header = ApkCodec.readHeaderFrom(java.io.ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        assertThat(header).isNotNull()
        assertThat(header!!.packageName).isEqualTo("com.example.app")
        assertThat(header.fileCount).isEqualTo(1)
    }

    @Test
    fun `readHeaderFrom with duplicate keys takes last value - boundary is validatedHeaderOrNull not decoder`() {
        // kotlinx.serialization last-value-wins on duplicate keys; the decoder does NOT reject.
        // The security boundary is validatedHeaderOrNull (package grammar check), not the codec.
        // This test pins current behaviour so a future change to strict duplicate rejection is visible.
        val json = "{\"packageName\":\"com.example.app\",\"packageName\":\"evil\",\"versionCode\":1,\"fileCount\":1,\"capturedPermissions\":[]}\n"
        val header = ApkCodec.readHeaderFrom(java.io.ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        assertThat(header).isNotNull()
        assertThat(header!!.packageName).isEqualTo("evil") // last-value wins
    }

    // ---- small helpers ----

    private fun <T> jsonLine(serializer: kotlinx.serialization.KSerializer<T>, value: T): ByteArray =
        (cc.grepon.portage.providers.wire.JsonLines.format.encodeToString(serializer, value) + "\n")
            .toByteArray(Charsets.UTF_8)
}
