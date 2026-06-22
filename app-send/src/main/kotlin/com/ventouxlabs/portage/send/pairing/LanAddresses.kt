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

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One network interface, reduced to the facts the hint logic needs (JVM-testable). */
data class NetInterface(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val addresses: List<InetAddress>,
)

/**
 * Picks the address hints embedded in the pairing QR. The receiver dials these in order,
 * so ordering is the policy: Wi-Fi first, wired next, everything else last — and never a
 * loopback, VPN tunnel, or cellular interface (DEVILS_ADVOCATE: "use the LAN IP, not
 * loopback or a VPN tun"). The hints are best-effort only; authentication is end-to-end
 * in the Noise handshake regardless of how the address was found (PROTOCOL.md §1).
 */
object LanAddresses {

    /** Interface name prefixes that are never the LAN: tunnels, cellular, point-to-point. */
    private val EXCLUDED_PREFIXES = listOf("tun", "tap", "ppp", "wg", "rmnet", "clat", "dummy")

    fun hints(interfaces: List<NetInterface>): List<String> =
        interfaces.asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filter { iface -> EXCLUDED_PREFIXES.none { iface.name.startsWith(it) } }
            .sortedBy { rank(it.name) }
            .flatMap { iface -> iface.addresses.filter { it.isUsableLanAddress() } }
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()

    private fun rank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") -> 1
        else -> 2
    }

    private fun InetAddress.isUsableLanAddress(): Boolean =
        this is Inet4Address && !isLoopbackAddress && !isLinkLocalAddress

    /** Snapshot the device's interfaces. Never throws — no network is just an empty list. */
    fun enumerate(): List<NetInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().map { nic ->
            NetInterface(
                name = nic.name.orEmpty(),
                isUp = runCatching { nic.isUp }.getOrDefault(false),
                isLoopback = runCatching { nic.isLoopback }.getOrDefault(true),
                addresses = nic.inetAddresses.toList(),
            )
        }
    }.getOrDefault(emptyList())
}
