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

import cc.grepon.portage.model.ItemKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private fun source(name: String, role: ApkFileRole, size: Int, abi: String? = null): ApkSourceFile {
    val bytes = ByteArray(size) { (it + name.length).toByte() }
    return ApkSourceFile(ApkFileEntry(name, role, abi, length = bytes.size.toLong())) {
        ByteArrayInputStream(bytes)
    }
}

/**
 * A source that DECLARES [declaredLength] without allocating that many bytes — `available()` reads only
 * the entry length, so this lets a test exercise the multi-GiB item/total caps without huge fixtures.
 */
private fun declaredSource(name: String, role: ApkFileRole, declaredLength: Long): ApkSourceFile =
    ApkSourceFile(ApkFileEntry(name, role, length = declaredLength)) { ByteArrayInputStream(ByteArray(0)) }

class ApkExportProviderTest {

    private fun providerOf(files: List<ApkSourceFile>, permissions: List<String> = emptyList()) =
        ApkExportProvider(
            packageName = "com.example.app",
            versionCode = 12L,
            appLabel = "Example App",
            files = files,
            capturedPermissions = permissions,
        )

    @Test
    fun `the provider declares the APK kind and the Apps group with the app label`() {
        val provider = providerOf(listOf(source("base", ApkFileRole.BASE, 10)))
        assertThat(provider.kind).isEqualTo(ItemKind.APK)
        assertThat(provider.group).isEqualTo("Apps")
        assertThat(provider.displayName).isEqualTo("Example App")
    }

    @Test
    fun `available is true with a full set (base plus non-empty splits)`() = runTest {
        val provider = providerOf(
            listOf(
                source("base", ApkFileRole.BASE, 100),
                source("config.arm64_v8a", ApkFileRole.CONFIG, 50, abi = "arm64_v8a"),
            ),
        )
        assertThat(provider.available()).isTrue()
    }

    @Test
    fun `available is false when the base file is missing (partial set is a defined skip)`() = runTest {
        val provider = providerOf(
            listOf(source("config.arm64_v8a", ApkFileRole.CONFIG, 50, abi = "arm64_v8a")),
        )
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `available is false when any declared file length is zero (unreadable split is a defined skip)`() = runTest {
        val provider = providerOf(
            listOf(
                source("base", ApkFileRole.BASE, 100),
                source("config.arm64_v8a", ApkFileRole.CONFIG, 0, abi = "arm64_v8a"),
            ),
        )
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `exportTo produces a container whose decoded entry set equals the injected set`() = runTest {
        val files = listOf(
            source("base", ApkFileRole.BASE, 300),
            source("config.arm64_v8a", ApkFileRole.CONFIG, 120, abi = "arm64_v8a"),
            source("config.en", ApkFileRole.LANGUAGE, 40),
        )
        val provider = providerOf(files, permissions = listOf("android.permission.INTERNET"))

        val out = ByteArrayOutputStream()
        provider.exportTo(out)
        val container = ApkCodec.readFrom(ByteArrayInputStream(out.toByteArray()))

        assertThat(container).isNotNull()
        assertThat(container!!.header.packageName).isEqualTo("com.example.app")
        assertThat(container.header.versionCode).isEqualTo(12L)
        assertThat(container.header.fileCount).isEqualTo(3)
        assertThat(container.header.capturedPermissions).containsExactly("android.permission.INTERNET")
        assertThat(container.files.map { it.entry }).isEqualTo(files.map { it.entry })
    }

    @Test
    fun `exportTo writes nothing when unavailable`() = runTest {
        // No base file → unavailable → an empty payload (a defined skip), never a half-container.
        val provider = providerOf(listOf(source("config.arm64_v8a", ApkFileRole.CONFIG, 50, abi = "arm64_v8a")))
        val out = ByteArrayOutputStream()
        provider.exportTo(out)
        assertThat(out.toByteArray()).isEmpty()
    }

    // ---- deriveTags (pure split-name parsing) ----

    @Test
    fun `deriveTags classifies base, abi, density, language, and feature splits`() {
        assertThat(deriveTags("base")).isEqualTo(ApkSplitTags(ApkFileRole.BASE))
        assertThat(deriveTags("split_config.arm64_v8a"))
            .isEqualTo(ApkSplitTags(ApkFileRole.CONFIG, abi = "arm64_v8a"))
        assertThat(deriveTags("config.arm64_v8a"))
            .isEqualTo(ApkSplitTags(ApkFileRole.CONFIG, abi = "arm64_v8a"))
        assertThat(deriveTags("split_config.xxhdpi"))
            .isEqualTo(ApkSplitTags(ApkFileRole.CONFIG, density = "xxhdpi"))
        assertThat(deriveTags("config.en"))
            .isEqualTo(ApkSplitTags(ApkFileRole.LANGUAGE, lang = "en"))
        assertThat(deriveTags("split_dynamicfeature"))
            .isEqualTo(ApkSplitTags(ApkFileRole.FEATURE))
    }

    @Test
    fun `deriveTags tolerates a trailing apk suffix`() {
        assertThat(deriveTags("base.apk")).isEqualTo(ApkSplitTags(ApkFileRole.BASE))
        assertThat(deriveTags("config.arm64_v8a.apk"))
            .isEqualTo(ApkSplitTags(ApkFileRole.CONFIG, abi = "arm64_v8a"))
    }

    @Test
    fun `deriveTags classifies region-qualified and BCP-47 language splits as LANGUAGE`() {
        // config.en_US — region-qualified; must not be misclassified as ABI (Fix 3).
        assertThat(deriveTags("config.en_US"))
            .isEqualTo(ApkSplitTags(ApkFileRole.LANGUAGE, lang = "en_US"))
        // config.b+sr+Latn — BCP-47 b+ prefix form; must be LANGUAGE (Fix 3).
        assertThat(deriveTags("config.b+sr+Latn"))
            .isEqualTo(ApkSplitTags(ApkFileRole.LANGUAGE, lang = "b+sr+Latn"))
    }

    @Test
    fun `available is false when the file set exceeds MAX_APK_FILES (Fix 5)`() = runTest {
        // 65 files (one BASE + 64 splits) exceeds MAX_APK_FILES=64 → available() must return false
        // so the sender never ships a container the receiver's validatedHeaderOrNull would reject.
        val files = mutableListOf(source("base", ApkFileRole.BASE, 100))
        repeat(ApkContainerValidation.MAX_APK_FILES) { i ->
            files.add(source("config.split$i", ApkFileRole.CONFIG, 10))
        }
        assertThat(files.size).isGreaterThan(ApkContainerValidation.MAX_APK_FILES)
        val provider = providerOf(files)
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `available is false when there are two BASE-role files (Fix 5)`() = runTest {
        // Exactly-one-BASE is a container invariant; two BASE files → available() must return false.
        val files = listOf(
            source("base", ApkFileRole.BASE, 100),
            source("base", ApkFileRole.BASE, 100),
        )
        val provider = providerOf(files)
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `available is false when a single file exceeds MAX_APK_ITEM_BYTES`() = runTest {
        // A base file declaring 1 byte over the per-item ceiling is a defined skip (no overflow path).
        val provider = providerOf(
            listOf(declaredSource("base", ApkFileRole.BASE, ApkContainerValidation.MAX_APK_ITEM_BYTES + 1)),
        )
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `available is true at exactly MAX_APK_ITEM_BYTES`() = runTest {
        val provider = providerOf(
            listOf(declaredSource("base", ApkFileRole.BASE, ApkContainerValidation.MAX_APK_ITEM_BYTES)),
        )
        assertThat(provider.available()).isTrue()
    }

    @Test
    fun `available is false when the declared total exceeds MAX_APK_TOTAL_BYTES`() = runTest {
        // Nine 1-GiB files: each within the item cap, but their sum (9 GiB) exceeds the 8 GiB total cap.
        val files = mutableListOf(declaredSource("base", ApkFileRole.BASE, ApkContainerValidation.MAX_APK_ITEM_BYTES))
        repeat(8) { i ->
            files.add(declaredSource("config.split$i", ApkFileRole.CONFIG, ApkContainerValidation.MAX_APK_ITEM_BYTES))
        }
        assertThat(files.sumOf { it.entry.length }).isGreaterThan(ApkContainerValidation.MAX_APK_TOTAL_BYTES)
        val provider = providerOf(files)
        assertThat(provider.available()).isFalse()
    }

    @Test
    fun `available is true when the declared total is at MAX_APK_TOTAL_BYTES`() = runTest {
        // Eight 1-GiB files sum to exactly the 8 GiB total cap — accepted (boundary inclusive).
        val files = mutableListOf(declaredSource("base", ApkFileRole.BASE, ApkContainerValidation.MAX_APK_ITEM_BYTES))
        repeat(7) { i ->
            files.add(declaredSource("config.split$i", ApkFileRole.CONFIG, ApkContainerValidation.MAX_APK_ITEM_BYTES))
        }
        assertThat(files.sumOf { it.entry.length }).isEqualTo(ApkContainerValidation.MAX_APK_TOTAL_BYTES)
        val provider = providerOf(files)
        assertThat(provider.available()).isTrue()
    }

    // ---- deriveTags documentation tests (Fix 7): pin current behaviour, do NOT change the logic ----

    @Test
    fun `deriveTags classifies a digit-bearing ABI like x86 as CONFIG with that abi`() {
        // config.x86 — the dimension carries a digit, so it is NOT a 2-letter language code and falls
        // through to ABI. Documents that digit-bearing ABIs land in the CONFIG/abi bucket.
        assertThat(deriveTags("config.x86"))
            .isEqualTo(ApkSplitTags(ApkFileRole.CONFIG, abi = "x86"))
    }

    @Test
    fun `deriveTags classifies config_mips as CONFIG abi - documents the regex abi-language tradeoff`() {
        // Pinned behaviour: "mips" is a 4-letter all-alpha ABI with NO separator, so it does NOT match
        // LANG_CODE = [a-z]{2}([_-][A-Za-z0-9]{2,})? (the tail needs a separator) and falls through to
        // ABI. KNOWN FUZZINESS at this boundary: a bare 2-letter all-lowercase ABI (none exist today)
        // WOULD match LANG_CODE and be tagged LANGUAGE instead. This advisory tag never gates the
        // install plan; the test pins current output so a future LANG_CODE/ABI change is visible and
        // the tradeoff is explicit to the next editor.
        assertThat(deriveTags("config.mips"))
            .isEqualTo(ApkSplitTags(ApkFileRole.CONFIG, abi = "mips"))
    }
}
