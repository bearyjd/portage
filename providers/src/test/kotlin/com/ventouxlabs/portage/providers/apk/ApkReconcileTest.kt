/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers.apk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AC-15: the PURE split target-compatibility reconcile (ADR-006 D3). */
class ApkReconcileTest {

    private val pixelTarget = ApkTargetConfig(
        supportedAbis = listOf("arm64_v8a", "armeabi_v7a"),
        densityBucket = "xxhdpi",
        locales = listOf("en", "de"),
    )

    private fun base() = ApkFileEntry("base", ApkFileRole.BASE, length = 100)
    private fun abi(name: String) = ApkFileEntry("split_config.$name", ApkFileRole.CONFIG, abi = name, length = 50)
    private fun density(name: String) = ApkFileEntry("split_config.$name", ApkFileRole.CONFIG, density = name, length = 20)
    private fun lang(name: String) = ApkFileEntry("split_config.$name", ApkFileRole.LANGUAGE, lang = name, length = 5)
    private fun feature(name: String) = ApkFileEntry(name, ApkFileRole.FEATURE, length = 30)

    @Test
    fun `base-only single APK is compatible and keeps just the base`() {
        val result = ApkReconcile.reconcile(listOf(base()), pixelTarget)
        assertThat(result).isInstanceOf(ApkReconcile.Result.Compatible::class.java)
        val files = (result as ApkReconcile.Result.Compatible).files
        assertThat(files.map { it.name }).containsExactly("base")
    }

    @Test
    fun `matching subset selected — only the target ABI and density splits are kept`() {
        val entries = listOf(
            base(),
            abi("arm64_v8a"),
            abi("x86_64"),
            density("xxhdpi"),
            density("hdpi"),
        )
        val result = ApkReconcile.reconcile(entries, pixelTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.name }).containsExactly(
            "base", "split_config.arm64_v8a", "split_config.xxhdpi",
        )
        // base is always first
        assertThat(result.files.first().role).isEqualTo(ApkFileRole.BASE)
    }

    @Test
    fun `missing-required-ABI routes to the incompatible fallback branch`() {
        // The source only carries an x86 split; this arm64 device cannot install it and never had arm64.
        val entries = listOf(base(), abi("x86_64"), abi("x86"))
        val result = ApkReconcile.reconcile(entries, pixelTarget)
        assertThat(result).isInstanceOf(ApkReconcile.Result.Incompatible::class.java)
    }

    @Test
    fun `language splits are all kept regardless of target locale`() {
        // The user may switch locale on the new phone — carry every language split.
        val entries = listOf(base(), lang("en"), lang("de"), lang("fr"), lang("ja"))
        val result = ApkReconcile.reconcile(entries, pixelTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.lang }).containsExactly(null, "en", "de", "fr", "ja")
    }

    @Test
    fun `a bucket-mismatched density split is KEPT as fallback, never dropped to zero`() {
        // Hardware-found (2026-06-21): dropping the only density split makes PackageInstaller REJECT the
        // commit with "Missing split" for App Bundles that mark density a required split type. So a
        // non-matching density split is kept (Android scales a non-exact density), never dropped — never
        // INCOMPATIBLE either (only ABI is). Pre-fix this asserted the ldpi split was dropped.
        val entries = listOf(base(), abi("arm64_v8a"), density("ldpi"))
        val result = ApkReconcile.reconcile(entries, pixelTarget) as ApkReconcile.Result.Compatible
        // ldpi != xxhdpi target, but it is the ONLY density split → kept as fallback (not dropped).
        assertThat(result.files.map { it.name }).containsExactly(
            "base", "split_config.arm64_v8a", "split_config.ldpi",
        )
    }

    @Test
    fun `Termux hardware regression — xxhdpi-only source kept for a lower-density xhdpi target`() {
        // Exact shape of the 2026-06-21 husky(xhdpi) <- rango(xxhdpi) failure: the source carries only its
        // own xxhdpi density split; the lower-density target has no xxhdpi match. Pre-fix reconcile dropped
        // it → "Missing split for com.termux" reject. Post-fix the xxhdpi split is kept so a density split
        // is present and Android scales it.
        val xhdpiTarget = pixelTarget.copy(densityBucket = "xhdpi")
        val entries = listOf(base(), abi("arm64_v8a"), lang("en"), lang("es"), density("xxhdpi"))
        val result = ApkReconcile.reconcile(entries, xhdpiTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.name }).containsExactly(
            "base", "split_config.arm64_v8a", "split_config.xxhdpi", "split_config.en", "split_config.es",
        )
    }

    @Test
    fun `an exact bucket match still drops the other density splits (fallback only when none match)`() {
        // The fallback "keep all" path triggers ONLY when nothing matches the bucket. When the target
        // bucket IS present, the non-matching densities are still dropped (no unnecessary bloat).
        val entries = listOf(base(), density("xxhdpi"), density("hdpi"), density("xxxhdpi"))
        val result = ApkReconcile.reconcile(entries, pixelTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.density }).containsExactly(null, "xxhdpi")
    }

    @Test
    fun `feature splits are kept`() {
        val entries = listOf(base(), feature("dynamic_feature"))
        val result = ApkReconcile.reconcile(entries, pixelTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.name }).containsExactly("base", "dynamic_feature")
    }

    @Test
    fun `an app with no abi splits at all stays compatible (trivial single-APK case)`() {
        val entries = listOf(base(), density("xxhdpi"), lang("en"))
        val result = ApkReconcile.reconcile(entries, pixelTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.name }).containsExactly(
            "base", "split_config.xxhdpi", "split_config.en",
        )
    }

    @Test
    fun `tvdpi density split is kept for a tvdpi target (fix 3 — bucket alignment with sender KNOWN_DENSITIES)`() {
        // The receiver's densityBucket() emits "tvdpi" for ~213dpi devices (fix 3). This test verifies
        // that ApkReconcile correctly keeps a tvdpi split when the target bucket is "tvdpi", confirming
        // end-to-end alignment with the sender's KNOWN_DENSITIES set (which includes "tvdpi").
        val tvdpiTarget = pixelTarget.copy(densityBucket = "tvdpi")
        val entries = listOf(base(), density("tvdpi"), density("xxhdpi"))
        val result = ApkReconcile.reconcile(entries, tvdpiTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.density }).contains("tvdpi")
        assertThat(result.files.map { it.density }).doesNotContain("xxhdpi")
    }

    @Test
    fun `a nodpi split is always kept regardless of target density bucket (fix 4)`() {
        // nodpi is density-independent — it must be kept even when the target bucket is xxhdpi.
        // This test pins fix 4: remove the nodpi/anydpi always-keep filter and it fails.
        val entries = listOf(base(), density("nodpi"), density("xxhdpi"))
        val result = ApkReconcile.reconcile(entries, pixelTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.density }).contains("nodpi")
        assertThat(result.files.map { it.density }).contains("xxhdpi")
    }

    @Test
    fun `an anydpi split is always kept regardless of target density bucket (fix 4)`() {
        // anydpi is density-independent (vector drawables etc.) — always kept.
        val entries = listOf(base(), density("anydpi"))
        val result = ApkReconcile.reconcile(entries, pixelTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.density }).contains("anydpi")
    }

    @Test
    fun `a present nodpi suppresses the keep-all fallback — a mismatched concrete density is still dropped`() {
        // The keep-all fallback fires ONLY when nothing density-valid is present. A density-independent
        // nodpi already satisfies a required-density-split base, so a non-bucket-matching concrete density
        // (ldpi vs the xxhdpi target) is still dropped — no bloat when the required-split check is already met.
        val entries = listOf(base(), density("nodpi"), density("ldpi"))
        val result = ApkReconcile.reconcile(entries, pixelTarget) as ApkReconcile.Result.Compatible
        assertThat(result.files.map { it.density }).containsExactly(null, "nodpi")
    }
}
