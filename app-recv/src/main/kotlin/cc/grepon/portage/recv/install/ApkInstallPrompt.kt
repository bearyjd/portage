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

/**
 * One Tier-0 install row surfaced on the Done screen (ADR-006 D3/D6). Produced when the
 * [cc.grepon.portage.providers.apk.ApkApplyProvider] falls back to the `PackageInstaller` path: the
 * bytes have already been written and sealed into a `PackageInstaller` session (inside the apply call,
 * before the staged files were wiped), and [sessionId] is the handle to commit it.
 *
 * The Done-screen tap fires the system install-confirm UI for [sessionId] — our own app committing a
 * session over our own bytes, NO shell uid (the silent stdin-streaming path is the deferred P6 concern,
 * ADR-006 D3/LOW-1). One tap per app is honest; whether GOS A16 can batch multiple confirm intents into
 * fewer taps is the open UX question noted in ADR-006 follow-ups.
 *
 * [packageName] is the wire-validated package (already gated by `ApkContainerValidation`); [label] is a
 * display label (today the package name — the friendly label lands when app-send carries it).
 */
data class ApkInstallPrompt(
    val packageName: String,
    val label: String,
    val sessionId: Int,
)
