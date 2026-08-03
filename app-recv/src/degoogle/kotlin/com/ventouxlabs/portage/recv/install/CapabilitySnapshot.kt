/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.recv.install

import com.ventouxlabs.portage.adbbridge.AdbBridge

/** Whether the most recent completed probe authorizes trying the silent-install seam. */
fun hasSilentInstall(capabilities: Set<AdbBridge.PrivilegedCapability>): Boolean =
    AdbBridge.PrivilegedCapability.SILENT_INSTALL in capabilities
