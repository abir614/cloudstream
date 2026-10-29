package com.lagradost.cloudstream3.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareKillerTest {

    @Test
    fun `test parseCookieMap parses standard cookie string`() {
        val rawCookie = "cf_clearance=abc123xyz; Path=/; Domain=.example.com; Secure; HttpOnly"
        val parsed = CloudflareKiller.parseCookieMap(rawCookie)

        assertEquals("abc123xyz", parsed["cf_clearance"])
        assertEquals("/", parsed["Path"])
        assertEquals(".example.com", parsed["Domain"])
    }

    @Test
    fun `test parseCookieMap handles empty and whitespace strings`() {
        val emptyMap = CloudflareKiller.parseCookieMap("")
        assertTrue(emptyMap.isEmpty())

        val whitespaceMap = CloudflareKiller.parseCookieMap("   ;  ; ")
        assertTrue(whitespaceMap.isEmpty())
    }
}
