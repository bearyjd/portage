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

import cc.grepon.portage.adbbridge.AdbBridge
import cc.grepon.portage.wizard.PrivilegeWizard

/**
 * Read the probed capability set from a wizard [PrivilegeWizard.Step] (ADR-006 D6). The set lives only
 * in the wizard's `StateFlow` (`Step.Ready(capabilities)`); it is not durably persisted. Any non-Ready
 * step — including process death between wizard and transfer, which loses the set — yields the EMPTY set,
 * which routes APK installs to the Tier-0 fallback. That is the SAFE failure direction (never a wrong
 * silent install). Pure over the step so it is JVM-testable.
 */
fun capabilitiesOf(step: PrivilegeWizard.Step): Set<AdbBridge.PrivilegedCapability> =
    when (step) {
        is PrivilegeWizard.Step.Ready -> step.capabilities
        else -> emptySet()
    }

/**
 * Whether the silent (privileged) install seam should be selected for THIS transfer (ADR-006 D6): true
 * iff `SILENT_INSTALL` is in the probed set. Today the silent adapter still returns Deferred → Tier-0,
 * but the wiring is correct and future-proof: when the P6 stdin-streaming adapter lands, flipping this
 * branch is all that selects it.
 */
fun hasSilentInstall(step: PrivilegeWizard.Step): Boolean =
    AdbBridge.PrivilegedCapability.SILENT_INSTALL in capabilitiesOf(step)
