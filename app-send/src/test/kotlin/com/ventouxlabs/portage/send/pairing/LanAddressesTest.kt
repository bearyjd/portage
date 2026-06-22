/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.pairing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress

class LanAddressesTest {

    private fun iface(name: String, vararg hosts: String, up: Boolean = true, loopback: Boolean = false) =
        NetInterface(
            name = name,
            isUp = up,
            isLoopback = loopback,
            addresses = hosts.map { InetAddress.getByName(it) },
        )

    @Test
    fun `prefers the wlan site-local address over everything else`() {
        val hints = LanAddresses.hints(
            listOf(
                iface("rmnet0", "10.123.45.67"),          // cellular — excluded by name
                iface("wlan0", "192.168.1.23"),
                iface("eth0", "192.168.1.50"),
            ),
        )
        assertThat(hints.first()).isEqualTo("192.168.1.23")
        assertThat(hints).containsExactly("192.168.1.23", "192.168.1.50").inOrder()
    }

    @Test
    fun `excludes loopback, down interfaces, and VPN tunnels`() {
        val hints = LanAddresses.hints(
            listOf(
                iface("lo", "127.0.0.1", loopback = true),
                iface("wlan0", "192.168.1.9", up = false),
                iface("tun0", "10.8.0.2"),
                iface("wg0", "10.10.0.2"),
                iface("ppp0", "10.64.64.64"),
            ),
        )
        assertThat(hints).isEmpty()
    }

    @Test
    fun `excludes IPv6 and link-local IPv4`() {
        val hints = LanAddresses.hints(
            listOf(
                iface("wlan0", "fe80::1", "169.254.13.37", "192.168.7.7"),
            ),
        )
        assertThat(hints).containsExactly("192.168.7.7")
    }

    @Test
    fun `deduplicates while keeping order`() {
        val hints = LanAddresses.hints(
            listOf(
                iface("wlan0", "192.168.1.2"),
                iface("wlan1", "192.168.1.2", "192.168.1.3"),
            ),
        )
        assertThat(hints).containsExactly("192.168.1.2", "192.168.1.3").inOrder()
    }

    @Test
    fun `no usable interface yields an empty list, not a crash`() {
        assertThat(LanAddresses.hints(emptyList())).isEmpty()
    }
}
