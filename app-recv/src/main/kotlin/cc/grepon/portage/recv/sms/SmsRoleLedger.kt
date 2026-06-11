/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.sms

import java.io.File

/**
 * The process-death safety net for the transient default-SMS-role handoff (DEVILS_ADVOCATE.md
 * Q4 §3). When portage takes the role it [arm]s this ledger with the prior holder's package; the
 * marker is a tiny file in app-private storage, so it OUTLIVES the transfer coroutine — even an
 * OOM kill or reboot mid-restore. On the next launch the coordinator reconciles: if portage is
 * still the default SMS app (the strand), [prior] names where to hand it back; once the role has
 * actually returned, [disarm] clears the marker.
 *
 * It stores ONLY a package name (never SMS content), and every filesystem touch is wrapped so a
 * storage error can never throw into the role teardown. Pure `java.io` — unit-tested on the JVM.
 */
class SmsRoleLedger(private val file: File) {

    /** Record that portage now holds the role, to be handed back toward [priorPackage]. */
    fun arm(priorPackage: String?) {
        runCatching { file.writeText(priorPackage ?: "") }
    }

    /** Clear the marker once the role has genuinely been relinquished. Idempotent. */
    fun disarm() {
        runCatching { file.delete() }
    }

    /** True while a handoff is outstanding (the marker file exists). */
    fun isArmed(): Boolean = file.exists()

    /**
     * The recorded restore target, or null when not armed OR armed without a known prior holder
     * (in which case the restore falls back to the system default-apps screen).
     */
    fun prior(): String? =
        runCatching { file.takeIf { it.exists() }?.readText()?.ifBlank { null } }.getOrNull()
}
