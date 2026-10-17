package com.wifiauxbridge.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetUtils {

    /** Local IPv4 address on the active interface (prefers wlan/ap), or null if none. */
    fun getLocalIpAddress(): String? {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrNull() ?: return null

        var fallback: String? = null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            for (addr in Collections.list(iface.inetAddresses)) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    val name = iface.name.lowercase()
                    if (name.contains("wlan") || name.contains("ap") || name.contains("swlan")) {
                        return host
                    }
                    if (fallback == null) fallback = host
                }
            }
        }
        return fallback
    }
}
