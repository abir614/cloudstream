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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

private val dnsRaceThreadCounter = AtomicInteger(0)

/**
 * Bounded shared daemon executor for dual-thread primary DoH lookups on MID/HIGH devices.
 * Capped at 8 short-lived daemon threads (30s keep-alive) to bound tail latency without leaking.
 */
private val dnsRaceExecutor: ThreadPoolExecutor by lazy {
    ThreadPoolExecutor(
        0,
        8,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable ->
            Thread(runnable, "CloudStream-DNS-Race-${dnsRaceThreadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
}

/**
 * Multi-Tier Resilient DNS Resolver with cascading fallbacks:
 * 1. Primary DoH: Quad9 Unfiltered (`dns10.quad9.net`) + Mozilla Cloudflare (`mozilla.cloudflare-dns.com`)
 *    - On MID/HIGH devices (`enableDualThreadPrimary = true`), both primary DoH endpoints race
 *      concurrently across two threads and the fastest valid response wins immediately.
 *    - On LOW / UltraPotato devices (`enableDualThreadPrimary = false`), queries execute sequentially
 *      to avoid duplicate TLS handshakes, extra sockets, and CPU/RAM pressure.
 * 2. Secondary DoH: Google (`dns.google`) -> AdGuard Unfiltered (`unfiltered.adguard-dns.com`)
 * 3. Primary Plain DNS (UDP 53): Quad9 (`dns10`) -> Cloudflare
 * 4. Secondary Plain DNS (UDP 53): Google -> AdGuard
 * 5. Final Fallback: Android System DNS (`Dns.SYSTEM`)
 */
class ResilientFallbackDns internal constructor(
    private val quad9DohLazy: Lazy<Dns>,
    private val mozillaCloudflareDohLazy: Lazy<Dns>,
    private val googleDohLazy: Lazy<Dns>,
    private val adguardDohLazy: Lazy<Dns>,
    private val enableDualThreadPrimary: Boolean = false
) : Dns {
    constructor(
        bootstrapClient: OkHttpClient,
        enableDualThreadPrimary: Boolean = false
    ) : this(
        quad9DohLazy = lazy {
            DnsOverHttps.Builder()
                .client(fastDohClient(bootstrapClient))
                .url("https://dns10.quad9.net/dns-query".toHttpUrl())
                .bootstrapDnsHosts(listOf(
                    InetAddress.getByName("9.9.9.10"),
                    InetAddress.getByName("149.112.112.10"),
                    InetAddress.getByName("2620:fe::10"),
                    InetAddress.getByName("2620:fe::fe:10")
                ))
                .build()
        },
        mozillaCloudflareDohLazy = lazy {
            DnsOverHttps.Builder()
                .client(fastDohClient(bootstrapClient))
                .url("https://mozilla.cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(listOf(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1"),
                    InetAddress.getByName("2606:4700:4700::1111"),
                    InetAddress.getByName("2606:4700:4700::1001")
                ))
                .build()
        },
        googleDohLazy = lazy {
            DnsOverHttps.Builder()
                .client(fastDohClient(bootstrapClient))
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(listOf(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("8.8.4.4"),
                    InetAddress.getByName("2001:4860:4860::8888"),
                    InetAddress.getByName("2001:4860:4860::8844")
                ))
                .build()
        },
        adguardDohLazy = lazy {
            DnsOverHttps.Builder()
                .client(fastDohClient(bootstrapClient))
                .url("https://unfiltered.adguard-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(listOf(
                    InetAddress.getByName("94.140.14.140"),
                    InetAddress.getByName("94.140.14.141"),
                    InetAddress.getByName("2a10:50c0::1:ff"),
                    InetAddress.getByName("2a10:50c0::2:ff")
                ))
                .build()
        },
        enableDualThreadPrimary = enableDualThreadPrimary
    )

    companion object {
        // Fast-fail timeouts prevent blackholed DoH servers from stalling the 4s cascade budget.
        private fun fastDohClient(bootstrapClient: OkHttpClient): OkHttpClient =
            bootstrapClient.newBuilder()
                .cache(null)
                .connectTimeout(1500L, TimeUnit.MILLISECONDS)
                .readTimeout(1500L, TimeUnit.MILLISECONDS)
                .writeTimeout(1500L, TimeUnit.MILLISECONDS)
                .callTimeout(2000L, TimeUnit.MILLISECONDS)
                .build()
    }

    private val primaryPlainIps = listOf("9.9.9.10", "149.112.112.10", "1.1.1.1", "1.0.0.1")
    private val secondaryPlainIps = listOf("8.8.8.8", "8.8.4.4", "94.140.14.140", "94.140.14.141")

    private fun lookupPrimarySequential(hostname: String, deadline: Long): List<InetAddress>? {
        try {
            val q9 = quad9DohLazy.value.lookup(hostname)
            if (q9.isNotEmpty()) return q9
        } catch (_: Throwable) {}
        if (System.currentTimeMillis() < deadline) {
            try {
                val moz = mozillaCloudflareDohLazy.value.lookup(hostname)
                if (moz.isNotEmpty()) return moz
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun lookupPrimaryDualThread(hostname: String, deadline: Long): List<InetAddress>? {
        val winner = AtomicReference<List<InetAddress>?>(null)
        val remaining = AtomicInteger(2)
        val latch = CountDownLatch(1)

        fun runResolver(resolver: Dns) {
            try {
                val resolved = resolver.lookup(hostname)
                if (resolved.isNotEmpty() && winner.compareAndSet(null, resolved)) {
                    latch.countDown()
                }
            } catch (_: Throwable) {
            } finally {
                if (remaining.decrementAndGet() == 0) {
                    latch.countDown()
                }
            }
        }

        val quad9Queued = try {
            dnsRaceExecutor.execute { runResolver(quad9DohLazy.value) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }

        if (!quad9Queued) {
            return lookupPrimarySequential(hostname, deadline)
        }

        val mozillaQueued = try {
            dnsRaceExecutor.execute { runResolver(mozillaCloudflareDohLazy.value) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }

        if (!mozillaQueued) {
            runResolver(mozillaCloudflareDohLazy.value)
        }

        val waitMs = (deadline - System.currentTimeMillis()).coerceIn(1L, 2000L)
        try {
            latch.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return winner.get()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        parseNumericAddress(hostname)?.let { return it }

        val deadline = System.currentTimeMillis() + 4000L

        // 1. Primary DoH: Quad9 (Unfiltered) + Mozilla Cloudflare
        val primaryResult = if (enableDualThreadPrimary) {
            lookupPrimaryDualThread(hostname, deadline)
        } else {
            lookupPrimarySequential(hostname, deadline)
        }
        if (!primaryResult.isNullOrEmpty()) return primaryResult

        if (System.currentTimeMillis() < deadline) {
            // 2. Secondary DoH: Google -> AdGuard (Unfiltered)
            try {
                val googleRes = googleDohLazy.value.lookup(hostname)
                if (googleRes.isNotEmpty()) return googleRes
            } catch (_: Throwable) {}
            if (System.currentTimeMillis() < deadline) {
                try {
                    val adguardRes = adguardDohLazy.value.lookup(hostname)
                    if (adguardRes.isNotEmpty()) return adguardRes
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

fun OkHttpClient.Builder.addResilientMultiTierDns(
    enableDualThreadPrimary: Boolean = false
): OkHttpClient.Builder {
    val resolverKey = if (enableDualThreadPrimary) {
        "__resilient_multi_tier_dual__"
    } else {
        "__resilient_multi_tier_seq__"
    }
    val sharedDns = sharedDnsResolvers.computeIfAbsent(resolverKey) {
        SafeDns(CachedDns(ResilientFallbackDns(sharedDohBootstrapClient, enableDualThreadPrimary)))
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
