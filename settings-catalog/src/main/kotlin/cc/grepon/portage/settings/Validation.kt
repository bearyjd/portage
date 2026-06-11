/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.settings

/**
 * Evaluate a [Validator] against a wire value. FAIL-CLOSED in every direction:
 * [Validator.None] accepts nothing (it is reserved for excluded keys), unparseable
 * numbers are rejected, patterns must match the WHOLE value, and an invalid regex
 * rejects rather than throws. The receiver applies a value only when this returns true.
 */
fun Validator.accepts(value: String): Boolean = when (this) {
    is Validator.None -> false
    is Validator.IntRange -> value.toIntOrNull()?.let { it in min..max } ?: false
    is Validator.FloatRange -> value.toFloatOrNull()?.let { !it.isNaN() && it in min..max } ?: false
    is Validator.IntEnum -> value.toIntOrNull()?.let { it in allowed } ?: false
    is Validator.StringEnum -> value in allowed
    is Validator.StringPattern ->
        runCatching { Regex(pattern).matches(value) }.getOrDefault(false)
}
