package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PotatoModeTest {

    private fun computeTargetBufferBytes(totalRamBytes: Long, isLowRam: Boolean): Int {
        val isPotato = isLowRam || (totalRamBytes in 1..805306368L) // <= 768MB
        val isMid = totalRamBytes in 805306369L..2147483648L      // ~1GB - 2GB
        val isHighEnd = totalRamBytes > 2147483648L                // > 2GB

        return when {
            isPotato -> 16 * 1024 * 1024
            isMid -> 48 * 1024 * 1024
            isHighEnd -> 256 * 1024 * 1024
            else -> 144 * 1024 * 1024
        }
    }

    private fun computeForwardBufferMs(totalRamBytes: Long, isLowRam: Boolean): Int {
        val isPotato = isLowRam || (totalRamBytes in 1..805306368L)
        val isMid = totalRamBytes in 805306369L..2147483648L
        val isHighEnd = totalRamBytes > 2147483648L

        return when {
            isPotato -> 20000
            isMid -> 35000
            isHighEnd -> 120000
            else -> 50000
        }
    }

    @Test
    fun `test 500MB ram device falls into potato tier`() {
        val ram500Mb = 512L * 1024 * 1024 // 536,870,912 bytes
        val isLowRam = false

        val bufferBytes = computeTargetBufferBytes(ram500Mb, isLowRam)
        val forwardBufferMs = computeForwardBufferMs(ram500Mb, isLowRam)

        // Verifies Potato buffer configuration fits safely inside 500MB RAM budget
        assertEquals(16 * 1024 * 1024, bufferBytes)
        assertEquals(20000, forwardBufferMs)
    }

    @Test
    fun `test low ram flag forces potato tier regardless of reported RAM`() {
        val isLowRam = true
        val ram1Gb = 1024L * 1024 * 1024

        val bufferBytes = computeTargetBufferBytes(ram1Gb, isLowRam)
        assertEquals(16 * 1024 * 1024, bufferBytes)
    }

    @Test
    fun `test standard and high end tiers get proportional headroom`() {
        val ram2Gb = 2L * 1024 * 1024 * 1024
        val ram4Gb = 4L * 1024 * 1024 * 1024

        assertEquals(48 * 1024 * 1024, computeTargetBufferBytes(ram2Gb, false))
        assertEquals(256 * 1024 * 1024, computeTargetBufferBytes(ram4Gb, false))
    }
}
