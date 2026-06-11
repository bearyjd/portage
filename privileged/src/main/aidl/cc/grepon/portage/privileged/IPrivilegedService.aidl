// portage — GrapheneOS device-parity transfer
// Copyright (C) 2026 Grepon Labs LLC. Licensed under AGPL-3.0.
package cc.grepon.portage.privileged;

/**
 * The binder a Shizuku UserService exposes from the shell-uid process. The ONLY privileged
 * operation is running a fixed argv (no shell string is ever interpolated), used for the
 * one-shot `pm grant WRITE_SECURE_SETTINGS` (ADR-001 §1, grant architecture).
 *
 * destroy() carries Shizuku's reserved teardown transaction id; do not renumber it.
 */
interface IPrivilegedService {
    void destroy() = 16777114;
    int runCommand(in String[] command) = 1;
}
