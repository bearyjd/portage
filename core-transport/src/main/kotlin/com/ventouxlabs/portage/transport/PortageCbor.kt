/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.transport

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * Single CBOR configuration for the transport. Centralized (security review 2026-06-10) so
 * the depth/size limits promised in PROTOCOL.md §5 / THREAT_MODEL.md §10 are applied in one
 * place when added, rather than drifting across two independent `Cbor {}` instances.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object PortageCbor {
    val instance: Cbor = Cbor { ignoreUnknownKeys = true }
}
