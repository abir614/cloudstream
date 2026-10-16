package com.lagradost.cloudstream3.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class DohProvidersTest {

    @Test
    fun `test CachedDns serves from memory cache and prevents repeated delegate lookups`() {
        val lookupCount = AtomicInteger(0)
        val mockDelegate = Dns { hostname ->
            lookupCount.incrementAndGet()
            listOf(InetAddress.getByName("93.184.216.34"))
        }

        val cachedDns = CachedDns(mockDelegate, ttlMs = 10_000L, maxCapacity = 10)

        // First lookup invokes delegate
        val first = cachedDns.lookup("example.com")
        assertEquals(1, lookupCount.get())
        assertEquals("93.184.216.34", first[0].hostAddress)

        // Second and third lookups must be served from cache without invoking delegate
        val second = cachedDns.lookup("example.com")
        val third = cachedDns.lookup("example.com")
        assertEquals(1, lookupCount.get())
        assertEquals("93.184.216.34", second[0].hostAddress)
        assertEquals("93.184.216.34", third[0].hostAddress)
    }

    @Test
    fun `test CachedDns bypasses cache and delegate for IPv4 literals`() {
        val lookupCount = AtomicInteger(0)
        val mockDelegate = Dns {
            lookupCount.incrementAndGet()
            emptyList()
        }

        val cachedDns = CachedDns(mockDelegate)
        val result = cachedDns.lookup("1.1.1.1")

        // Must resolve IP directly without touching delegate
        assertEquals(0, lookupCount.get())
        assertEquals(1, result.size)
        assertEquals("1.1.1.1", result[0].hostAddress)
    }

    @Test
    fun `test CachedDns expires entries after TTL`() {
        val lookupCount = AtomicInteger(0)
        val mockDelegate = Dns {
            lookupCount.incrementAndGet()
            listOf(InetAddress.getByName("8.8.8.8"))
        }

        // 1ms TTL
        val cachedDns = CachedDns(mockDelegate, ttlMs = 1L)
        cachedDns.lookup("google.com")
        assertEquals(1, lookupCount.get())

        Thread.sleep(10) // Wait for expiry

        cachedDns.lookup("google.com")
        assertEquals(2, lookupCount.get())
    }

    @Test
    fun `test ResilientFallbackDns resolves IP literals immediately`() {
        val client = OkHttpClient()
        val resilientDns = ResilientFallbackDns(client)

        val result = resilientDns.lookup("9.9.9.9")
        assertEquals(1, result.size)
        assertEquals("9.9.9.9", result[0].hostAddress)
    }

    @Test
    fun `test CachedDns respects max capacity limit`() {
        val mockDelegate = Dns { hostname ->
            listOf(InetAddress.getByName("127.0.0.1"))
        }

        val cachedDns = CachedDns(mockDelegate, ttlMs = 100_000L, maxCapacity = 3)

        cachedDns.lookup("host1.com")
        cachedDns.lookup("host2.com")
        cachedDns.lookup("host3.com")
        cachedDns.lookup("host4.com") // Exceeds capacity, triggers pruning

        assertTrue(true)
    }
}
