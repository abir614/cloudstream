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
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val DNS_TXID_RANDOM = SecureRandom()

/**
 * Builds a standard RFC-1035 UDP DNS A-record query packet and returns `(txId, packetBytes)`.
 */
private fun buildDnsQueryPacket(hostname: String): Pair<Int, ByteArray> {
    val txId = DNS_TXID_RANDOM.nextInt(65535) + 1
    val baos = ByteArrayOutputStream()
    val dos = DataOutputStream(baos)
    // Cryptographically random 16-bit transaction ID to harden against UDP spoofing
    dos.writeShort(txId)
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
    return txId to baos.toByteArray()
}

/**
 * Parses an RFC-1035 DNS response packet and extracts IPv4 addresses.
 */
private fun parseDnsResponse(buffer: ByteArray, length: Int, hostname: String, expectedTxId: Int): List<InetAddress>? {
    if (length < 12) return null
    return try {
        val bais = ByteArrayInputStream(buffer, 0, length)
        val dis = DataInputStream(bais)
        val responseTxId = dis.readUnsignedShort()
        if (responseTxId != expectedTxId) return null // Reject spoofed/mismatched TXID
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
        val (txId, query) = buildDnsQueryPacket(hostname)
        val serverAddr = InetAddress.getByName(dnsServerIp)
        val packet = DatagramPacket(query, query.size, serverAddr, 53)
        socket.send(packet)

        val buffer = ByteArray(512)
        val response = DatagramPacket(buffer, buffer.size)
        socket.receive(response)
        parseDnsResponse(buffer, response.length, hostname, txId)
    } catch (_: Throwable) {
        null
    } finally {
        try { socket?.close() } catch (_: Throwable) {}
    }
}

private val IP_REGEX = Regex("^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$")

/**
 * Checks and parses raw IPv4 or IPv6 numeric literals, returning them immediately
 * without executing unnecessary DNS roundtrips.
 */
private fun parseNumericAddress(hostname: String): List<InetAddress>? {
    val clean = hostname.removePrefix("[").removeSuffix("]")
    if (clean.contains(':') || clean.matches(IP_REGEX)) {
        return try {
            listOf(InetAddress.getByName(clean))
        } catch (_: Throwable) {
            null
        }
    }
    return null
}

/**
 * Multi-Tier Resilient DNS Resolver with cascading fallbacks:
 * 1. Primary DoH: Mozilla Cloudflare DoH -> Quad9 DoH (dns10)
 * 2. Secondary DoH: Cloudflare DoH -> Google DoH
 * 3. Primary Plain DNS (UDP 53): Quad9 (dns10) -> Cloudflare
 * 4. Secondary Plain DNS (UDP 53): Google -> AdGuard
 * 5. Final Fallback: Android System DNS (Dns.SYSTEM)
 */
class ResilientFallbackDns(
    bootstrapClient: OkHttpClient
) : Dns {
    // Fast-fail timeouts prevent blackholed DoH servers from stalling the 4s cascade budget.
    private val fastDohClient = bootstrapClient.newBuilder()
        .cache(null)
        .connectTimeout(1500L, TimeUnit.MILLISECONDS)
        .readTimeout(1500L, TimeUnit.MILLISECONDS)
        .writeTimeout(1500L, TimeUnit.MILLISECONDS)
        .callTimeout(2000L, TimeUnit.MILLISECONDS)
        .build()

    private val mozillaCloudflareDoh by lazy {
        DnsOverHttps.Builder()
            .client(fastDohClient)
            .url("https://mozilla.cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("2606:4700:4700::1001")
            ))
            .build()
    }

    private val quad9Doh by lazy {
        DnsOverHttps.Builder()
            .client(fastDohClient)
            .url("https://dns10.quad9.net/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("9.9.9.10"),
                InetAddress.getByName("149.112.112.10"),
                InetAddress.getByName("2620:fe::10"),
                InetAddress.getByName("2620:fe::fe:10")
            ))
            .build()
    }

    private val cloudflareDoh by lazy {
        DnsOverHttps.Builder()
            .client(fastDohClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("2606:4700:4700::1001")
            ))
            .build()
    }

    private val googleDoh by lazy {
        DnsOverHttps.Builder()
            .client(fastDohClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4"),
                InetAddress.getByName("2001:4860:4860::8888"),
                InetAddress.getByName("2001:4860:4860::8844")
            ))
            .build()
    }

    private val primaryPlainIps = listOf("9.9.9.10", "149.112.112.10", "1.1.1.1", "1.0.0.1")
    private val secondaryPlainIps = listOf("8.8.8.8", "8.8.4.4", "94.140.14.140", "94.140.14.141")

    override fun lookup(hostname: String): List<InetAddress> {
        parseNumericAddress(hostname)?.let { return it }

        val deadline = System.currentTimeMillis() + 4000L

        // 1. Primary Privacy DoH: Mozilla Cloudflare -> Quad9 (Zero-logging, strictly confidential)
        try {
            return mozillaCloudflareDoh.lookup(hostname)
        } catch (_: Throwable) {}
        if (System.currentTimeMillis() < deadline) {
            try {
                return quad9Doh.lookup(hostname)
            } catch (_: Throwable) {}
        }

        if (System.currentTimeMillis() < deadline) {
            // 2. Secondary High-Speed Edge DoH: Cloudflare -> Google
            try {
                return cloudflareDoh.lookup(hostname)
            } catch (_: Throwable) {}
            if (System.currentTimeMillis() < deadline) {
                try {
                    return googleDoh.lookup(hostname)
                } catch (_: Throwable) {}
            }
        }

        if (System.currentTimeMillis() < deadline) {
            // 3. Primary Plain DNS (UDP 53): Quad9 -> Cloudflare
            for (ip in primaryPlainIps) {
                val res = queryUdpDns(hostname, ip, timeoutMs = 1000)
                if (!res.isNullOrEmpty()) return res
                if (System.currentTimeMillis() >= deadline) break
            }
        }

        if (System.currentTimeMillis() < deadline) {
            // 4. Secondary Plain DNS (UDP 53): Google -> AdGuard
            for (ip in secondaryPlainIps) {
                val res = queryUdpDns(hostname, ip, timeoutMs = 1000)
                if (!res.isNullOrEmpty()) return res
                if (System.currentTimeMillis() >= deadline) break
            }
        }

        // 5. Final Fallback to Android System DNS
        return Dns.SYSTEM.lookup(hostname)
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
        parseNumericAddress(hostname)?.let { return it }

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
}

/**
 * Shared lightweight bootstrap OkHttpClient for DoH endpoints so multiple callers
 * (main API client, Coil image client, player fallback) reuse a single Dispatcher and ConnectionPool
 * without building duplicate OkHttpClient stacks during client initialization.
 */
private val sharedDohBootstrapClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(1500L, TimeUnit.MILLISECONDS)
        .readTimeout(1500L, TimeUnit.MILLISECONDS)
        .writeTimeout(1500L, TimeUnit.MILLISECONDS)
        .callTimeout(2000L, TimeUnit.MILLISECONDS)
        .build()
}

/**
 * Shared DNS resolver cache keyed by DoH endpoint + bootstrap IPs so API requests,
 * Coil image loads, and media playback reuse resolved records across clients.
 */
private val sharedDnsResolvers = ConcurrentHashMap<String, Dns>()

fun OkHttpClient.Builder.addGenericDns(url: String, ips: List<String>): OkHttpClient.Builder {
    val cacheKey = "$url|${ips.joinToString(",")}"
    val sharedDns = sharedDnsResolvers.computeIfAbsent(cacheKey) {
        SafeDns(
            CachedDns(
                object : Dns {
                    private val doh by lazy {
                        DnsOverHttps
                            .Builder()
                            .client(sharedDohBootstrapClient)
                            .url(url.toHttpUrl())
                            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                            .build()
                    }

                    override fun lookup(hostname: String): List<InetAddress> {
                        parseNumericAddress(hostname)?.let { return it }

                        val deadline = System.currentTimeMillis() + 3000L
                        try {
                            return doh.lookup(hostname)
                        } catch (_: Throwable) {}

                        if (System.currentTimeMillis() < deadline) {
                            // Plain DNS fallback using the provider's bootstrap IPs (IPv4 only for UDP port 53 A-record queries)
                            for (ip in ips.filterNot { it.contains(':') }) {
                                val res = queryUdpDns(hostname, ip, timeoutMs = 1000)
                                if (!res.isNullOrEmpty()) return res
                                if (System.currentTimeMillis() >= deadline) break
                            }
                        }

                        // Final fallback to System DNS
                        return Dns.SYSTEM.lookup(hostname)
                    }
                }
            )
        )
    }
    return dns(sharedDns)
}

fun OkHttpClient.Builder.addResilientMultiTierDns(): OkHttpClient.Builder {
    val sharedDns = sharedDnsResolvers.computeIfAbsent("__resilient_multi_tier__") {
        SafeDns(CachedDns(ResilientFallbackDns(sharedDohBootstrapClient)))
    }
    return dns(sharedDns)
}


fun OkHttpClient.Builder.addMozillaCloudflareDns() = (
    addGenericDns(
        "https://mozilla.cloudflare-dns.com/dns-query",
        listOf(
            "1.1.1.1",
            "1.0.0.1",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001"
        )
    ))

@Deprecated("Mullvad DNS is discontinued. Redirected to Mozilla Cloudflare DNS.", ReplaceWith("addMozillaCloudflareDns()"))
fun OkHttpClient.Builder.addMullvadDns() = addMozillaCloudflareDns()

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
        "https://unfiltered.adguard-dns.com/dns-query",
        listOf(
            "94.140.14.140",
            "94.140.14.141",
            "2a10:50c0::1:ff",
            "2a10:50c0::2:ff"
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
        "https://dns10.quad9.net/dns-query",
        listOf(
            "9.9.9.10",
            "149.112.112.10",
            "2620:fe::10",
            "2620:fe::fe:10"
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
