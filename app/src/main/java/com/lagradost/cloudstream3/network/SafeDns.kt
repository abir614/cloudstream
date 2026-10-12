package com.lagradost.cloudstream3.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * SSRF-Safe DNS Resolver that wraps any upstream OkHttp Dns implementation.
 *
 * Enforces security at the DNS resolution layer to protect against SSRF, internal network
 * probing, cloud metadata theft (e.g. 169.254.169.254), and DNS rebinding attacks.
 * Since OkHttp resolves every redirect hop through Dns, SafeDns covers initial requests
 * and all redirect chains automatically.
 */
class SafeDns(
    private val upstream: Dns,
    private val allowLan: () -> Boolean = { false },
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val all = upstream.lookup(hostname)
        val ok = if (allowLan()) all else all.filterNot { it.isBlocked() }
        if (ok.isEmpty()) {
            throw UnknownHostException("Blocked non-routable / private network address for host: $hostname")
        }
        return ok
    }

    companion object {
        fun InetAddress.isBlocked(): Boolean {
            // Standard JDK checks for loopback, link-local, site-local, multicast, any-local
            if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress ||
                isSiteLocalAddress || isMulticastAddress
            ) {
                return true
            }

            val b = address
            return when (this) {
                is Inet4Address -> {
                    val b0 = b[0].toInt() and 0xFF
                    val b1 = b[1].toInt() and 0xFF
                    // 0.0.0.0/8 (current network)
                    // 100.64.0.0/10 (Carrier-Grade NAT)
                    // 240.0.0.0/4 (reserved/experimental)
                    b0 == 0 || (b0 == 100 && b1 in 64..127) || b0 >= 240
                }
                is Inet6Address -> {
                    // fc00::/7 (Unique Local Address - ULA)
                    (b[0].toInt() and 0xFE) == 0xFC
                }
                else -> false
            }
        }
    }
}
