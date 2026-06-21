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
 * The "derive/validate, never trust" gate for a hostile sender's APK container (ADR-006 D1/D4/AC-6b),
 * mirroring [cc.grepon.portage.providers.relay.RelayHeader.sanitizedOrNull]. These validators are the
 * security-reviewer's focus: every wire field that could become a staged filename, an unbounded loop,
 * or a path escape is refused HERE — cleanly returning null — before it is ever acted on. The
 * receiver-side use of these guards lands in a later phase; the guards themselves live and are
 * unit-tested now.
 */
object ApkContainerValidation {

    /**
     * [ApkContainerHeader.fileCount] bound (ADR-006 D4): generous for a real split set, bounds a
     * malformed header. A container outside `1..MAX_APK_FILES` is rejected.
     */
    const val MAX_APK_FILES = 64

    /**
     * Per-item byte ceiling (ADR-006 D4): the largest a single APK container item may declare. The
     * receiver-side streaming apply path enforces this against each [ApkFileEntry.length] before staging.
     * Declared here so both sender validation and receiver apply share the same constant.
     */
    const val MAX_APK_ITEM_BYTES = 1L * 1024 * 1024 * 1024   // ADR-006 D4

    /**
     * Aggregate byte ceiling across all items in one container (ADR-006 D4). The receiver enforces this
     * as the running sum of declared lengths before accepting the container.
     */
    const val MAX_APK_TOTAL_BYTES = 8L * 1024 * 1024 * 1024   // ADR-006 D4

    /**
     * Split-name length ceiling. An in-grammar name is otherwise bounded only by [ApkCodec]'s 4 KiB
     * header guard, which is far past the filesystem `NAME_MAX` (255 on common Linux/Android FSes) —
     * an over-long name would survive validation only to fail downstream with `ENAMETOOLONG`. 255 caps
     * it at that limit so an oversized name is refused HERE, cleanly.
     */
    const val MAX_SPLIT_NAME_LENGTH = 255

    /** The wire name a base APK must carry, exactly. Anything else is treated as a split name. */
    const val BASE_NAME = "base"

    /**
     * The Android package grammar: dot-separated `[A-Za-z0-9_]` segments, two or more — the same
     * regex the inventory deep link and [cc.grepon.portage.providers.relay.RelayHeader] use, so a
     * validated package is intent/URL-safe by construction and can never be a path.
     */
    private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")

    /**
     * The strict split-name grammar (ADR-006 AC-6b). A split name must begin with an alphanumeric
     * character, followed by zero or more characters drawn ONLY from `[A-Za-z0-9._-]`. Requiring an
     * alphanumeric first character closes the leading-dot and leading-dash attack surfaces (e.g.
     * `.hidden`, `-rf`) that the character-set allowlist alone permits. The allowlist itself structurally
     * excludes '/', '\', any other path separator, control characters, whitespace, and shell
     * metacharacters, so a validated split name can never traverse, escape, or inject. Note `".."` and
     * `"."` are additionally rejected explicitly below as defence in depth.
     */
    private val SPLIT_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")

    /**
     * Validate [ApkFileEntry.name] BEFORE it is ever used as a staged filename (ADR-006 AC-6b).
     * Returns the trusted name on success, or null to REJECT.
     *  - `"base"` is accepted verbatim.
     *  - any other name must be at most [MAX_SPLIT_NAME_LENGTH] chars, match the strict [SPLIT_NAME]
     *    allowlist, AND not be `"."`/`".."`.
     * Empty, over-long, path-bearing, control-char, or shell-metacharacter names are all refused.
     */
    fun validatedSplitNameOrNull(name: String): String? {
        if (name == BASE_NAME) return name
        if (name.isEmpty()) return null
        if (name.length > MAX_SPLIT_NAME_LENGTH) return null
        if (name == "." || name == "..") return null
        if (!SPLIT_NAME.matches(name)) return null
        return name
    }

    /**
     * Reject a single [ApkFileEntry] whose name fails the split-name gate, whose declared length is
     * negative, or whose declared length exceeds [MAX_APK_ITEM_BYTES] (ADR-006 D4 — bounds sender-side
     * staging and kills the integer-overflow class at the leaf so the aggregate sum can never wrap).
     * Returns the entry unchanged on success (its name is already trusted), or null to REJECT. The
     * streamed-vs-declared byte cross-check is the caller's job; this validates the line.
     */
    fun validatedEntryOrNull(entry: ApkFileEntry): ApkFileEntry? {
        if (entry.length < 0L) return null
        if (entry.length > MAX_APK_ITEM_BYTES) return null
        if (validatedSplitNameOrNull(entry.name) == null) return null
        // A BASE-role file MUST carry the literal base name, and only a BASE-role file may.
        val isBaseName = entry.name == BASE_NAME
        val isBaseRole = entry.role == ApkFileRole.BASE
        if (isBaseName != isBaseRole) return null
        return entry
    }

    /**
     * Reject a hostile [ApkContainerHeader] (ADR-006 D1/D4). Returns the header unchanged on success
     * (its package is already trusted), or null to REJECT. Guards:
     *  - [ApkContainerHeader.versionCode] must be >= 0.
     *  - [ApkContainerHeader.fileCount] must be in `1..MAX_APK_FILES`.
     *  - [ApkContainerHeader.packageName] must match the package grammar (never a path).
     */
    fun validatedHeaderOrNull(header: ApkContainerHeader): ApkContainerHeader? {
        if (header.versionCode < 0L) return null
        if (header.fileCount < 1 || header.fileCount > MAX_APK_FILES) return null
        if (!PACKAGE_NAME.matches(header.packageName)) return null
        return header
    }

    /**
     * Validate a fully-collected entry list against the container invariants that span every line
     * (ADR-006 D1). Returns the list unchanged on success, or null to REJECT. Guards:
     *  - every entry passes [validatedEntryOrNull] (name grammar + non-negative length + base/role
     *    agreement);
     *  - the list size equals [ApkContainerHeader.fileCount];
     *  - exactly one BASE entry is present (zero or multiple are rejected).
     */
    fun validatedEntriesOrNull(header: ApkContainerHeader, entries: List<ApkFileEntry>): List<ApkFileEntry>? {
        if (entries.size != header.fileCount) return null
        if (entries.any { validatedEntryOrNull(it) == null }) return null
        if (entries.count { it.role == ApkFileRole.BASE } != 1) return null
        return entries
    }
}
