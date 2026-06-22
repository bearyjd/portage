/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.providers.permission

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ADR-006 D5 — guardrails for the PURE sender-side capture filter (the primary signature/system gate). */
class PermissionCaptureTest {

    private fun req(name: String, granted: Boolean, dangerous: Boolean) =
        PermissionCapture.RequestedPermission(name, granted, dangerous)

    private val camera = "android.permission.CAMERA"
    private val internet = PermissionCapture.NETWORK_SENSOR_SPECIALS.first { it.endsWith("INTERNET") }
    private val otherSensors = PermissionCapture.NETWORK_SENSOR_SPECIALS.first { it.endsWith("OTHER_SENSORS") }
    private val writeSecure = "android.permission.WRITE_SECURE_SETTINGS" // signature/system

    @Test
    fun `a granted dangerous perm is captured`() {
        assertThat(PermissionCapture.capturable(listOf(req(camera, granted = true, dangerous = true))))
            .containsExactly(camera)
    }

    @Test
    fun `granted GOS specials are captured even though they are not dangerous-protection`() {
        val captured = PermissionCapture.capturable(
            listOf(
                req(internet, granted = true, dangerous = false),
                req(otherSensors, granted = true, dangerous = false),
            ),
        )
        assertThat(captured).containsExactly(internet, otherSensors)
    }

    @Test
    fun `a denied perm is never captured`() {
        assertThat(PermissionCapture.capturable(listOf(req(camera, granted = false, dangerous = true))))
            .isEmpty()
        assertThat(PermissionCapture.capturable(listOf(req(internet, granted = false, dangerous = false))))
            .isEmpty()
    }

    @Test
    fun `a granted normal or signature perm is dropped (the signature-system gate)`() {
        // Neither dangerous nor a GOS special → not captured, regardless of grant. This keeps
        // signature/system perms (e.g. WRITE_SECURE_SETTINGS) out of the captured set entirely.
        val captured = PermissionCapture.capturable(
            listOf(
                req(writeSecure, granted = true, dangerous = false),
                req("android.permission.SOME_NORMAL_PERM", granted = true, dangerous = false),
            ),
        )
        assertThat(captured).isEmpty()
    }

    @Test
    fun `the result is de-duped and sorted for a stable wire order`() {
        val captured = PermissionCapture.capturable(
            listOf(
                req(otherSensors, granted = true, dangerous = false),
                req(camera, granted = true, dangerous = true),
                req(camera, granted = true, dangerous = true),
                req(internet, granted = true, dangerous = false),
            ),
        )
        assertThat(captured).containsExactly(camera, internet, otherSensors).inOrder()
    }

    @Test
    fun `empty input yields empty output`() {
        assertThat(PermissionCapture.capturable(emptyList())).isEmpty()
    }
}
