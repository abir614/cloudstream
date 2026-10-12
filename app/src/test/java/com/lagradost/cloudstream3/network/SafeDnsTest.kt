package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.network.SafeDns.Companion.isBlocked
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class SafeDnsTest {

    @Test
    fun `test isBlocked blocks private loopback link-local and cloud metadata IPs`() {
        // Loopback
        assertTrue(InetAddress.getByName("127.0.0.1").isBlocked())
        assertTrue(InetAddress.getByName("127.1.2.3").isBlocked())
        assertTrue(InetAddress.getByName("::1").isBlocked())

        // RFC 1918 Private IP Ranges
        assertTrue(InetAddress.getByName("10.0.0.1").isBlocked())
        assertTrue(InetAddress.getByName("10.255.255.255").isBlocked())
        assertTrue(InetAddress.getByName("172.16.0.1").isBlocked())
        assertTrue(InetAddress.getByName("172.31.255.255").isBlocked())
        assertTrue(InetAddress.getByName("192.168.0.1").isBlocked())
        assertTrue(InetAddress.getByName("192.168.1.254").isBlocked())

        // Cloud Metadata & Link-Local
        assertTrue(InetAddress.getByName("169.254.169.254").isBlocked())
        assertTrue(InetAddress.getByName("169.254.1.1").isBlocked())

        // Special IPv4 ranges (0.0.0.0/8, CGNAT 100.64.0.0/10, Multicast/Reserved 240.0.0.0/4)
        assertTrue(InetAddress.getByName("0.0.0.0").isBlocked())
        assertTrue(InetAddress.getByName("100.64.0.1").isBlocked())
        assertTrue(InetAddress.getByName("100.127.255.255").isBlocked())
        assertTrue(InetAddress.getByName("240.0.0.1").isBlocked())
        assertTrue(InetAddress.getByName("224.0.0.1").isBlocked()) // Multicast

        // IPv6 Unique Local Address (fc00::/7)
        assertTrue(InetAddress.getByName("fc00::1").isBlocked())
        assertTrue(InetAddress.getByName("fd12:3456:789a::1").isBlocked())
    }

    @Test
    fun `test isBlocked permits valid public Internet IPs`() {
        assertFalse(InetAddress.getByName("8.8.8.8").isBlocked())
        assertFalse(InetAddress.getByName("1.1.1.1").isBlocked())
        assertFalse(InetAddress.getByName("9.9.9.9").isBlocked())
        assertFalse(InetAddress.getByName("142.250.190.46").isBlocked())
        assertFalse(InetAddress.getByName("2606:4700:4700::1111").isBlocked())
    }

    @Test
    fun `test SafeDns throws UnknownHostException when all resolved addresses are blocked`() {
        val mockUpstream = Dns { hostname ->
            when (hostname) {
                "metadata.google.internal" -> listOf(InetAddress.getByName("169.254.169.254"))
                "internal.lan" -> listOf(InetAddress.getByName("192.168.1.100"))
                else -> emptyList()
            }
        }

        val safeDns = SafeDns(mockUpstream, allowLan = { false })

        var exceptionThrown = false
        try {
            safeDns.lookup("metadata.google.internal")
        } catch (e: UnknownHostException) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun `test SafeDns filters blocked IPs and retains valid public IPs`() {
        val mockUpstream = Dns {
            listOf(
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("8.8.8.8")
            )
        }

        val safeDns = SafeDns(mockUpstream, allowLan = { false })
        val resolved = safeDns.lookup("dual-homed.example.com")
        assertEquals(1, resolved.size)
        assertEquals(InetAddress.getByName("8.8.8.8"), resolved[0])
    }

    @Test
    fun `test SafeDns permits LAN IPs when allowLan is true`() {
        val mockUpstream = Dns {
            listOf(InetAddress.getByName("192.168.1.50"))
        }

        val safeDns = SafeDns(mockUpstream, allowLan = { true })
        val resolved = safeDns.lookup("local-nas.lan")
        assertEquals(1, resolved.size)
        assertEquals(InetAddress.getByName("192.168.1.50"), resolved[0])
    }
}
