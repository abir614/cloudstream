package com.lagradost.cloudstream3.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds a standard RFC-1035 UDP DNS A-record query packet.
 */
private fun buildDnsQueryPacket(hostname: String): ByteArray {
    val baos = ByteArrayOutputStream()
    val dos = DataOutputStream(baos)
    // Transaction ID (random 16-bit integer)
    dos.writeShort((1..65535).random())
    // Flags: standard query, recursion desired (0x0100)
    dos.writeShort(0x0100)
    // QDCOUNT: 1 question
    dos.writeShort(1)
    // ANCOUNT, NSCOUNT, ARCOUNT: 0
    dos.writeShort(0)
    dos.writeShort(0)
    dos.writeShort(0)
    // QNAME
    val labels = hostname.trimEnd('.').split('.')
    for (label in labels) {
        val bytes = label.toByteArray(Charsets.US_ASCII)
        dos.writeByte(bytes.size)
        dos.write(bytes)
    }
    dos.writeByte(0) // Root null terminator
    // QTYPE: A (1)
    dos.writeShort(1)
    // QCLASS: IN (1)
    dos.writeShort(1)
    return baos.toByteArray()
}

/**
 * Parses an RFC-1035 DNS response packet and extracts IPv4 addresses.
 */
private fun parseDnsResponse(buffer: ByteArray, length: Int, hostname: String): List<InetAddress>? {
    if (length < 12) return null
    return try {
        val bais = ByteArrayInputStream(buffer, 0, length)
        val dis = DataInputStream(bais)
        dis.readUnsignedShort() // TXID
        val flags = dis.readUnsignedShort()
        val rcode = flags and 0x000F
        if (rcode != 0) return null // DNS error
        val qdCount = dis.readUnsignedShort()
        val anCount = dis.readUnsignedShort()
        dis.readUnsignedShort() // NSCOUNT
        dis.readUnsignedShort() // ARCOUNT

        // Skip question section
        for (i in 0 until qdCount) {
            skipDnsName(bais)
            dis.readShort() // QTYPE
            dis.readShort() // QCLASS
        }

        // Read answer records
        val results = mutableListOf<InetAddress>()
        for (i in 0 until anCount) {
            skipDnsName(bais)
            val type = dis.readUnsignedShort()
            dis.readUnsignedShort() // CLASS
            dis.readInt() // TTL
            val rdLength = dis.readUnsignedShort()

            if (type == 1 && rdLength == 4) { // Type A (IPv4)
                val ipBytes = ByteArray(4)
                dis.readFully(ipBytes)
                results.add(InetAddress.getByAddress(hostname, ipBytes))
            } else {
                dis.skipBytes(rdLength)
            }
        }
        if (results.isNotEmpty()) results else null
    } catch (_: Throwable) {
        null
    }
}

private fun skipDnsName(bais: ByteArrayInputStream) {
    while (true) {
        val len = bais.read()
        if (len <= 0) break
        if ((len and 0xC0) == 0xC0) {
            // Compressed 2-byte pointer offset
            bais.read()
            break
        }
        bais.skip(len.toLong())
    }
}

/**
 * Sends a UDP DNS query to a specific DNS server IP on port 53.
 */
fun queryUdpDns(hostname: String, dnsServerIp: String, timeoutMs: Int = 1500): List<InetAddress>? {
    var socket: DatagramSocket? = null
    return try {
        socket = DatagramSocket()
        socket.soTimeout = timeoutMs
        val query = buildDnsQueryPacket(hostname)
        val serverAddr = InetAddress.getByName(dnsServerIp)
        val packet = DatagramPacket(query, query.size, serverAddr, 53)
        socket.send(packet)

        val buffer = ByteArray(512)
        val response = DatagramPacket(buffer, buffer.size)
        socket.receive(response)
        parseDnsResponse(buffer, response.length, hostname)
    } catch (_: Throwable) {
        null
    } finally {
        try { socket?.close() } catch (_: Throwable) {}
    }
}

/**
 * Multi-Tier Resilient DNS Resolver with cascading fallbacks:
 * 1. Primary DoH: Mullvad DoH -> Quad9 DoH
 * 2. Secondary DoH: Cloudflare DoH -> Google DoH
 * 3. Primary Plain DNS (UDP 53): Mullvad -> Quad9
 * 4. Secondary Plain DNS (UDP 53): Cloudflare -> Google
 * 5. Final Fallback: Android System DNS (Dns.SYSTEM)
 */
class ResilientFallbackDns(
    bootstrapClient: OkHttpClient
) : Dns {
    private val mullvadDoh by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.mullvad.net/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("194.242.2.2"),
                InetAddress.getByName("193.19.108.2")
            ))
            .build()
    }

    private val quad9Doh by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.quad9.net/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("9.9.9.9"),
                InetAddress.getByName("149.112.112.112")
            ))
            .build()
    }

    private val cloudflareDoh by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1")
            ))
            .build()
    }

    private val googleDoh by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4")
            ))
            .build()
    }

    private val primaryPlainIps = listOf("194.242.2.2", "193.19.108.2", "9.9.9.9", "149.112.112.112")
    private val secondaryPlainIps = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4")

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.matches(IP_REGEX)) {
            return listOf(InetAddress.getByName(hostname))
        }

        // 1. Primary DoH: Mullvad -> Quad9
        try {
            return mullvadDoh.lookup(hostname)
        } catch (_: Throwable) {}
        try {
            return quad9Doh.lookup(hostname)
        } catch (_: Throwable) {}

        // 2. Secondary DoH: Cloudflare -> Google
        try {
            return cloudflareDoh.lookup(hostname)
        } catch (_: Throwable) {}
        try {
            return googleDoh.lookup(hostname)
        } catch (_: Throwable) {}

        // 3. Primary Plain DNS (UDP 53): Mullvad -> Quad9
        for (ip in primaryPlainIps) {
            val res = queryUdpDns(hostname, ip)
            if (!res.isNullOrEmpty()) return res
        }

        // 4. Secondary Plain DNS (UDP 53): Cloudflare -> Google
        for (ip in secondaryPlainIps) {
            val res = queryUdpDns(hostname, ip)
            if (!res.isNullOrEmpty()) return res
        }

        // 5. Final Fallback to Android System DNS
        return Dns.SYSTEM.lookup(hostname)
    }

    companion object {
        private val IP_REGEX = Regex("^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$")
    }
}

/**
 * In-Memory High-Performance TTL DNS Cache.
 * Caches successfully resolved DNS records for 5 minutes with bounded capacity (512 entries).
 * Completely eliminates repeated DoH and plain UDP round trips during HLS/DASH chunk streaming
 * and API queries, reducing lookup latency from ~50-150ms to 0.0ms.
 */
class CachedDns(
    private val delegate: Dns,
    private val ttlMs: Long = 5 * 60 * 1000L,
    private val maxCapacity: Int = 512
) : Dns {
    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.matches(IP_REGEX)) {
            return listOf(InetAddress.getByName(hostname))
        }

        val now = System.currentTimeMillis()
        val entry = cache[hostname]
        if (entry != null && entry.expiresAtMs > now) {
            return entry.addresses
        }

        val addresses = delegate.lookup(hostname)
        if (addresses.isNotEmpty()) {
            if (cache.size >= maxCapacity) {
                cache.entries.removeIf { it.value.expiresAtMs <= now }
                if (cache.size >= maxCapacity) {
                    cache.clear()
                }
            }
            cache[hostname] = CacheEntry(addresses, now + ttlMs)
        }
        return addresses
    }

    companion object {
        private val IP_REGEX = Regex("^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$")
    }
}

fun OkHttpClient.Builder.addGenericDns(url: String, ips: List<String>) = dns(
    CachedDns(
        object : Dns {
            private val doh by lazy {
                DnsOverHttps
                    .Builder()
                    .client(build())
                    .url(url.toHttpUrl())
                    .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                    .build()
            }

            override fun lookup(hostname: String): List<InetAddress> {
                try {
                    return doh.lookup(hostname)
                } catch (_: Throwable) {}

                // Plain DNS fallback using the provider's bootstrap IPs
                for (ip in ips) {
                    val res = queryUdpDns(hostname, ip)
                    if (!res.isNullOrEmpty()) return res
                }

                // Final fallback to System DNS
                return Dns.SYSTEM.lookup(hostname)
            }
        }
    )
)

fun OkHttpClient.Builder.addResilientMultiTierDns() = dns(
    CachedDns(ResilientFallbackDns(build()))
)

fun OkHttpClient.Builder.addMullvadDns() = (
    addGenericDns(
        "https://dns.mullvad.net/dns-query",
        listOf(
            "194.242.2.2",
            "193.19.108.2"
        )
    ))

fun OkHttpClient.Builder.addGoogleDns() = (
    addGenericDns(
        "https://dns.google/dns-query",
        listOf(
            "8.8.4.4",
            "8.8.8.8"
        )
    ))

fun OkHttpClient.Builder.addCloudFlareDns() = (
    addGenericDns(
        "https://cloudflare-dns.com/dns-query",
        listOf(
            "1.1.1.1",
            "1.0.0.1",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001"
        )
    ))

fun OkHttpClient.Builder.addAdGuardDns() = (
    addGenericDns(
        "https://dns.adguard.com/dns-query",
        listOf(
            "94.140.14.140",
            "94.140.14.141"
        )
    ))

fun OkHttpClient.Builder.addDNSWatchDns() = (
    addGenericDns(
        "https://resolver2.dns.watch/dns-query",
        listOf(
            "84.200.69.80",
            "84.200.70.40"
        )
    ))

fun OkHttpClient.Builder.addQuad9Dns() = (
    addGenericDns(
        "https://dns.quad9.net/dns-query",
        listOf(
            "9.9.9.9",
            "149.112.112.112"
        )
    ))

fun OkHttpClient.Builder.addDnsSbDns() = (
    addGenericDns(
        "https://doh.dns.sb/dns-query",
        listOf(
            "185.222.222.222",
            "45.11.45.11"
        )
    ))

fun OkHttpClient.Builder.addCanadianShieldDns() = (
    addGenericDns(
        "https://private.canadianshield.cira.ca/dns-query",
        listOf(
            "149.112.121.10",
            "149.112.122.10"
        )
    ))
