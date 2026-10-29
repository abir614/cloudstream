package com.lagradost.cloudstream3.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DdosGuardKillerTest {

    @Test
    fun `test savedCookiesMap initialized and thread safe`() {
        val killer = DdosGuardKiller(alwaysBypass = false)
        assertNotNull(killer.savedCookiesMap)
        assertTrue(killer.savedCookiesMap.isEmpty())

        killer.savedCookiesMap["example.com"] = mapOf("__ddg1" to "token123")
        assertEquals("token123", killer.savedCookiesMap["example.com"]?.get("__ddg1"))
    }
}
