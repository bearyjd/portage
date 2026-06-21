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

/**
 * The PURE split target-compatibility reconcile (ADR-006 D3/AC-15). Byte-exact reconstruction does not
 * guarantee installability: the source device's config splits (abi/density/language) may not match the
 * target's, and the source never held the target's splits. Given the container's [ApkFileEntry] tags and
 * the target [ApkTargetConfig], this computes the installable subset BEFORE any install is attempted — no
 * Android types, fully unit-testable, lives beside the codec (ADR-006 D2/D3).
 *
 * Derive-never-trust (ADR-006 D3): this function reconciles on whatever role/abi/density/lang tags its
 * [ApkFileEntry] inputs carry, but the apply path NEVER passes the sender's wire tags here — the
 * [ApkApplyProvider] re-derives each entry's tags from its validated [ApkFileEntry.name] via [deriveTags]
 * first, so a mislabeled wire tag cannot steer the install plan. The byte payload and length are untouched.
 *
 * Policy (ADR-006 D3):
 *  1. Always keep BASE.
 *  2. ABI config splits: keep only those whose abi matches a target [ApkTargetConfig.supportedAbis].
 *     If the source carries ANY abi split but NONE matches the target → INCOMPATIBLE (the required ABI
 *     dimension is genuinely absent — portage cannot synthesize a split it never had; pretending
 *     otherwise produces a broken app). A base with no abi splits at all is the trivial single-APK case
 *     and stays compatible.
 *  3. Density config splits: keep the target's [ApkTargetConfig.densityBucket] split + the density-
 *     independent nodpi/anydpi splits. If NONE match the target bucket, keep the source's density
 *     split(s) ANYWAY (never drop to zero): an App Bundle may mark density a REQUIRED split type, so a
 *     commit carrying no density split is REJECTED by PackageInstaller ("Missing split") — Android
 *     instead accepts a non-exact density split and scales it. Density is never INCOMPATIBLE (only ABI
 *     is); the worst case is one slightly-mismatched density split that installs and scales.
 *  4. Language splits: keep ALL of them (ADR-006 D3 step 1: "+ all language splits the user kept"). The
 *     user may switch locale on the new phone; carrying every language split keeps that honest.
 *  5. FEATURE splits: keep all (dynamic-feature modules are install-time-optional; carrying them is safe).
 */
object ApkReconcile {

    /** The outcome of reconciling one container against the target device. */
    sealed interface Result {
        /** Installable: [files] is base + the kept subset, in a stable order (base first). */
        data class Compatible(val files: List<ApkFileEntry>) : Result

        /**
         * A REQUIRED config dimension's split is genuinely absent from the source set for THIS device
         * (today: no source ABI split matches a target ABI). The honest terminal — surface an
         * "incompatible on this device — install from store" outcome and route to the inventory-style
         * fallback; never attempt a known-broken install (ADR-006 D3 step 2).
         */
        data class Incompatible(val reason: String) : Result
    }

    /**
     * Reconcile [entries] (already validated by [ApkContainerValidation.validatedEntriesOrNull]) against
     * [target]. Returns [Result.Compatible] with the installable subset, or [Result.Incompatible] when a
     * required ABI split is absent. The BASE entry is always first in the returned list.
     */
    fun reconcile(entries: List<ApkFileEntry>, target: ApkTargetConfig): Result {
        val base = entries.firstOrNull { it.role == ApkFileRole.BASE }
            ?: return Result.Incompatible("no base apk in container")

        val abiSplits = entries.filter { it.role == ApkFileRole.CONFIG && it.abi != null }
        val matchedAbis = abiSplits.filter { it.abi in target.supportedAbis }
        if (abiSplits.isNotEmpty() && matchedAbis.isEmpty()) {
            return Result.Incompatible(
                "needs an ABI this device doesn't have (${abiSplits.mapNotNull { it.abi }.distinct().joinToString()})",
            )
        }

        // Density config splits. Keep nodpi/anydpi (density-INDEPENDENT — they serve all densities; a
        // device never self-reports them) plus the split matching the target bucket. CRUCIAL (verified
        // on hardware 2026-06-21): if NONE match the target bucket and there are no nodpi/anydpi splits,
        // keep the source's density split(s) ANYWAY rather than dropping to zero. A source device carries
        // only its OWN bucket's split, and an App Bundle that marks density a REQUIRED split type (the
        // bundletool default — e.g. Termux) makes PackageInstaller REJECT a commit carrying no density
        // split ("Missing split for <pkg>"). Android accepts a non-exact density split and scales it, so
        // the mismatched split installs cleanly where dropping it fails at commit.
        val densityCandidates = entries.filter { it.role == ApkFileRole.CONFIG && it.density != null }
        val densityIndependent = densityCandidates.filter { it.density == "nodpi" || it.density == "anydpi" }
        val bucketMatch = densityCandidates.filter { it.density == target.densityBucket }
        val densitySplits = (densityIndependent + bucketMatch).ifEmpty { densityCandidates }

        val languageSplits = entries.filter { it.role == ApkFileRole.LANGUAGE }
        val featureSplits = entries.filter { it.role == ApkFileRole.FEATURE }

        val kept = buildList {
            add(base)
            addAll(matchedAbis)
            addAll(densitySplits)
            addAll(languageSplits)
            addAll(featureSplits)
        }
        return Result.Compatible(kept)
    }
}
