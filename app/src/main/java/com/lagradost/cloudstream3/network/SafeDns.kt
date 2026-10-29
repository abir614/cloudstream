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
                    // Check IPv4-mapped IPv6 (::ffff:w.x.y.z)
                    val isIpv4Mapped = (0..9).all { b[it] == 0.toByte() } &&
                            b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()

                    // Check IPv4-compatible IPv6 (::w.x.y.z, deprecated)
                    val isIpv4Compatible = (0..11).all { b[it] == 0.toByte() } &&
                            !(b[12] == 0.toByte() && b[13] == 0.toByte() && b[14] == 0.toByte() && b[15] == 1.toByte())

                    // Check NAT64 Well-Known Prefix (64:ff9b::w.x.y.z)
                    val isNat64 = b[0] == 0x00.toByte() && b[1] == 0x64.toByte() &&
                            b[2] == 0xFF.toByte() && b[3] == 0x9B.toByte() &&
                            (4..11).all { b[it] == 0.toByte() }

                    if (isIpv4Mapped || isIpv4Compatible || isNat64) {
                        try {
                            val embeddedIpv4 = InetAddress.getByAddress(b.copyOfRange(12, 16))
                            return embeddedIpv4.isBlocked()
                        } catch (_: Throwable) {
                            return true
                        }
                    }

                    // fc00::/7 (Unique Local Address - ULA)
                    (b[0].toInt() and 0xFE) == 0xFC
                }
                else -> false
            }
        }
    }
}
