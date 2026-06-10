/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers

import cc.grepon.portage.model.ItemKind
import cc.grepon.portage.model.ItemResult
import java.io.InputStream
import java.io.OutputStream

/**
 * A Tier-0 domain handler — contacts, calendar, call log, SMS, app inventory. Each works
 * with normal runtime permissions, no privilege bridge (portage-prp-prompt.md §3, Tier 0).
 *
 * Export and apply are split across the two apps: the sender exports, the receiver applies.
 * An implementation may support one side or both.
 */
interface Tier0Provider {

    val kind: ItemKind

    /** Sender side: stream this domain's serialized form (e.g. vCard/ICS) into [sink]. */
    suspend fun exportTo(sink: OutputStream)

    /**
     * Receiver side: apply a staged item read from [source]. MUST be best-effort and
     * per-record resilient — a single bad record never aborts the item, and a failed
     * item never aborts the batch (PROTOCOL.md §5).
     */
    suspend fun apply(source: InputStream): ItemResult
}

/**
 * SMS is special: applying requires becoming the temporary default SMS app, then handing
 * the role BACK to the recorded prior holder. The teardown is the highest UX-risk path —
 * see docs/prp/DEVILS_ADVOCATE.md Q4. Implementations MUST record the prior holder before
 * acquiring the role and expose an idempotent relinquish.
 */
interface SmsProvider : Tier0Provider {
    suspend fun recordPriorDefault(): String?
    suspend fun relinquishTo(priorHolderPackage: String?)
}
