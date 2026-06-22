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

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.providers.ExportProvider
import java.io.OutputStream

/**
 * Sender side: stage one installed app's base + split APK files as a framed [ApkCodec] container
 * (ADR-006 D1/D2). Android-specific reading (`PackageManager.applicationInfo.sourceDir` +
 * `splitSourceDirs`) is done in app-send and INJECTED via the [files] seam — exactly as the relay
 * provider injects `openPickedFile`/`pickedFileLength` and settings inject `TierOneGrant` — so this
 * class stays Android-type-free and `:providers` keeps its `:core-model` + `:settings-catalog`-only
 * dependency set (ADR-006 D2). [capturedPermissions] defaults empty; Phase 5 fills it (ADR-006 D5).
 *
 * NOTE: the codec + provider are built and tested, but the app-send `PackageManager`/Compose wiring
 * that produces instances of this provider is NOT part of this slice — `ItemKind.APK` has no producer
 * until that follow-up. The green tests here prove the provider itself, not the end-to-end flow.
 *
 * [available] is false (a DEFINED skip, ADR-006 AC-19) unless a BASE file is present AND every declared
 * file's length is > 0 — a partial set or an unreadable split must never ship a broken half-container.
 */
class ApkExportProvider(
    private val packageName: String,
    private val versionCode: Long,
    private val appLabel: String,
    private val files: List<ApkSourceFile>,
    private val capturedPermissions: List<String> = emptyList(),
) : ExportProvider {

    override val kind = ItemKind.APK
    override val displayName = appLabel
    override val group = "Apps"

    override suspend fun available(): Boolean {
        if (files.size !in 1..ApkContainerValidation.MAX_APK_FILES) return false
        if (files.count { it.entry.role == ApkFileRole.BASE } != 1) return false
        if (!files.all { it.entry.length > 0L }) return false
        // Enforce the per-item ceiling first (ADR-006 D4): a single file over MAX_APK_ITEM_BYTES is a
        // defined skip. Bounding each file at <= 1 GiB also means the <=64-file sum below cannot wrap a
        // Long, so the aggregate check is overflow-safe by construction.
        if (files.any { it.entry.length > ApkContainerValidation.MAX_APK_ITEM_BYTES }) return false
        return files.sumOf { it.entry.length } <= ApkContainerValidation.MAX_APK_TOTAL_BYTES
    }

    override suspend fun exportTo(sink: OutputStream) {
        if (!available()) return
        val header = ApkContainerHeader(
            packageName = packageName,
            versionCode = versionCode,
            fileCount = files.size,
            capturedPermissions = capturedPermissions,
        )
        ApkCodec.writeContainer(sink, header, files)
    }
}

/** The derived split tags for one APK file: its [role] plus any config dimension it carries. */
data class ApkSplitTags(
    val role: ApkFileRole,
    val abi: String? = null,
    val density: String? = null,
    val lang: String? = null,
)

/**
 * Parse Android split-APK file naming into typed [ApkSplitTags] — pure string logic, no Android types,
 * so it is fully unit-testable and lives beside the codec (ADR-006 D2/D3). The receiver re-derives
 * these on apply and never trusts the sender's tags blindly. Examples:
 *  - `"base"` → `BASE`
 *  - `"split_config.arm64_v8a"` / `"config.arm64_v8a"` → `CONFIG`, abi = `arm64_v8a`
 *  - `"split_config.xxhdpi"` → `CONFIG`, density = `xxhdpi`
 *  - `"split_config.en"` / `"config.en_US"` → `LANGUAGE`, lang = `en` / `en_US`
 *  - `"config.b+sr+Latn"` (BCP-47 `b+` prefix form) → `LANGUAGE`, lang = `b+sr+Latn`
 *  - anything else (a dynamic-feature module) → `FEATURE`
 *
 * The dimension after `config.` is classified by shape: a known density suffix → density, a `b+`-prefixed
 * BCP-47 form → language, a 2-letter-or-region code matching `[a-z]{2}([_-][A-Za-z0-9]{2,})?` → language,
 * otherwise → abi. This is advisory; misclassification only affects the sender-side tag, never the install
 * plan.
 *
 * NOTE: NOT yet wired from app-send — the `PackageManager`/Compose seam that calls this to build
 * [ApkFileEntry] tags is the next slice (Phase 2, ADR-006 D6). This function is built and tested here
 * so the codec slice is complete and independently reviewable.
 */
fun deriveTags(splitFileName: String): ApkSplitTags {
    val name = splitFileName.removeSuffix(".apk")
    if (name == ApkContainerValidation.BASE_NAME) return ApkSplitTags(ApkFileRole.BASE)

    val configBody = stripConfigPrefix(name) ?: return ApkSplitTags(ApkFileRole.FEATURE)
    return when {
        configBody in KNOWN_DENSITIES -> ApkSplitTags(ApkFileRole.CONFIG, density = configBody)
        configBody.startsWith("b+") -> ApkSplitTags(ApkFileRole.LANGUAGE, lang = configBody)
        LANG_CODE.matches(configBody) -> ApkSplitTags(ApkFileRole.LANGUAGE, lang = configBody)
        else -> ApkSplitTags(ApkFileRole.CONFIG, abi = configBody)
    }
}

/** Strip a leading `split_config.` or `config.` prefix, returning the dimension, or null if absent. */
private fun stripConfigPrefix(name: String): String? = when {
    name.startsWith("split_config.") -> name.removePrefix("split_config.")
    name.startsWith("config.") -> name.removePrefix("config.")
    else -> null
}

/**
 * A language-code pattern covering simple 2-letter codes (`en`, `de`) and region/script-qualified forms
 * (`en_US`, `zh_CN`, `sr-Latn`). Matches `[a-z]{2}` optionally followed by `[_-][A-Za-z0-9]{2,}` to
 * include BCP-47 region tags; enough to disambiguate from ABI (which contains digits/underscores in
 * other positions) and density (enumerated explicitly in [KNOWN_DENSITIES]). The `b+` BCP-47 prefix
 * form is handled separately before this regex is evaluated.
 */
private val LANG_CODE = Regex("""[a-z]{2}([_-][A-Za-z0-9]{2,})?""")

/** The Android screen-density buckets that appear as config-split suffixes. */
private val KNOWN_DENSITIES = setOf(
    "ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi",
)
