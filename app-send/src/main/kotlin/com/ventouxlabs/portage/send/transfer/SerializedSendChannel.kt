/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.transfer

import com.ventouxlabs.portage.model.ProtocolMessage
import com.ventouxlabs.portage.transport.SecureChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cancellation and a simultaneous peer cancellation must never encrypt/write concurrently. */
internal class SerializedSendChannel(private val delegate: SecureChannel) : SecureChannel {
    private val sendMutex = Mutex()
    override suspend fun send(message: ProtocolMessage) = sendMutex.withLock { delegate.send(message) }
    override suspend fun receive(): ProtocolMessage? = delegate.receive()
    override fun close() = delegate.close()
}
