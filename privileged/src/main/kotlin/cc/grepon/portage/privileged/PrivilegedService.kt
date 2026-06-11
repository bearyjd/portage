/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.privileged

/**
 * Shizuku UserService — runs in the separate shell-uid process Shizuku spawns. Because
 * `WRITE_SECURE_SETTINGS` is held by the shell, a `pm grant` executed here lands on us
 * (ADR-001 §1, Phase A one-shot). Shizuku instantiates this class reflectively in that
 * process, so its name and constructors must survive R8 (see `consumer-rules.pro`); the
 * `Context` constructor is preferred on Shizuku server ≥13, the no-arg one is the fallback.
 *
 * It does NOT expose a generic shell-uid exec, which would be the very "escape hatch on the
 * privilege boundary" the [PrivilegedOps] contract forbids. [runCommand] HARD-ALLOWLISTS the one
 * argv shape the bridge needs — `pm grant <pkg> WRITE_SECURE_SETTINGS` — and rejects everything
 * else, so even a buggy or compromised caller cannot run an arbitrary command as the shell uid.
 * It runs via [ProcessBuilder] list-form: there is no shell and no string interpolation. A future
 * Tier-1 shell op must widen this allowlist (or add its own typed method) under its own review.
 */
class PrivilegedService : IPrivilegedService.Stub {

    @Suppress("unused") // reflective fallback constructor (Shizuku server < 13)
    constructor() : super()

    @Suppress("unused", "UNUSED_PARAMETER") // reflective ctor (Shizuku server ≥ 13); context unused
    constructor(context: android.content.Context) : super()

    override fun destroy() {
        // Shizuku invokes this (reserved txn) to tear the service down.
        System.exit(0)
    }

    override fun runCommand(command: Array<String>?): Int {
        val argv = command?.toList().orEmpty()
        // Allowlist: this service exists ONLY to self-grant WRITE_SECURE_SETTINGS. The target
        // package is left to the OS grant (the shell can only grant a development permission to a
        // package that declares it), but the verb and permission are pinned.
        val isGrant = argv.size == GRANT_ARGV_SIZE &&
            argv[0] == "pm" && argv[1] == "grant" &&
            argv[3] == "android.permission.WRITE_SECURE_SETTINGS"
        if (!isGrant) return EXIT_BAD_INVOCATION
        return runCatching {
            val process = ProcessBuilder(argv).redirectErrorStream(true).start()
            // Drain the merged output stream so the child can never block on a full pipe.
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
        }.getOrDefault(EXIT_BAD_INVOCATION)
    }

    private companion object {
        const val EXIT_BAD_INVOCATION = -1
        const val GRANT_ARGV_SIZE = 4
    }
}
