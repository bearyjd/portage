/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.adbbridge

/**
 * Builds shell command lines from discrete arguments so no caller ever string-interpolates
 * values into a command (the classic injection footgun — values like settings strings come
 * off the wire). The ADB `shell:` service takes ONE string parsed by `sh` on the device, so
 * unlike the old Shizuku UserService there is no argv-exec path; quoting is the boundary.
 *
 * Policy: args made only of clearly-safe characters pass through verbatim; anything else is
 * single-quoted with the standard `'\''` escape. Control characters (including newline) are
 * rejected outright — no legitimate portage operation needs them, and rejecting beats escaping
 * for a privilege boundary.
 */
object ShellArgs {

    private val SAFE_ARG = Regex("^[A-Za-z0-9_.,:/@+=-]+$")
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f]")

    /** One validated arg, quoted if needed; null = rejected (control characters). */
    fun quote(arg: String): String? {
        if (arg.isEmpty()) return "''"
        if (CONTROL_CHARS.containsMatchIn(arg)) return null
        if (SAFE_ARG.matches(arg)) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    /** A full command line from discrete args; null if any arg is rejected. */
    fun command(vararg argv: String): String? {
        if (argv.isEmpty()) return null
        val quoted = argv.map { quote(it) ?: return null }
        return quoted.joinToString(" ")
    }
}
