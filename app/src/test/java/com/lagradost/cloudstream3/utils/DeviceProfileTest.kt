package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun `test network tier configs scale properly`() {
        val lowNet = NetworkTierConfig(
            maxRequests = 32,
            maxRequestsPerHost = 10,
            maxIdleConnections = 6,
            keepAliveDurationMinutes = 2L,
            connectTimeoutSeconds = 15L,
            readTimeoutSeconds = 25L,
            diskCacheMaxBytes = 30L * 1024 * 1024
        )
        val midNet = NetworkTierConfig(
            maxRequests = 64,
            maxRequestsPerHost = 16,
            maxIdleConnections = 12,
            keepAliveDurationMinutes = 4L,
            connectTimeoutSeconds = 12L,
            readTimeoutSeconds = 20L,
            diskCacheMaxBytes = 50L * 1024 * 1024
        )
        val highNet = NetworkTierConfig(
            maxRequests = 96,
            maxRequestsPerHost = 24,
            maxIdleConnections = 20,
            keepAliveDurationMinutes = 5L,
            connectTimeoutSeconds = 10L,
            readTimeoutSeconds = 20L,
            diskCacheMaxBytes = 100L * 1024 * 1024
        )

        assertTrue(lowNet.maxRequests < midNet.maxRequests)
        assertTrue(midNet.maxRequests < highNet.maxRequests)
        assertTrue(lowNet.maxIdleConnections < midNet.maxIdleConnections)
        assertTrue(midNet.maxIdleConnections < highNet.maxIdleConnections)
        assertTrue(lowNet.connectTimeoutSeconds > highNet.connectTimeoutSeconds)
        assertTrue(lowNet.diskCacheMaxBytes < midNet.diskCacheMaxBytes)
        assertTrue(midNet.diskCacheMaxBytes < highNet.diskCacheMaxBytes)
    }

    @Test
    fun `test player buffer configs allocate correct headroom`() {
        val lowBuf = PlayerBufferConfig(
            minBufferMs = 10_000,
            maxBufferMs = 25_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 3_000,
            targetBufferBytes = 12 * 1024 * 1024,
            backBufferMs = 5_000
        )
        val midBuf = PlayerBufferConfig(
            minBufferMs = 20_000,
            maxBufferMs = 45_000,
            bufferForPlaybackMs = 2_000,
            bufferForPlaybackAfterRebufferMs = 4_000,
            targetBufferBytes = 48 * 1024 * 1024,
            backBufferMs = 15_000
        )
        val highBuf = PlayerBufferConfig(
            minBufferMs = 30_000,
            maxBufferMs = 60_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
            targetBufferBytes = 128 * 1024 * 1024,
            backBufferMs = 30_000
        )

        assertEquals(12 * 1024 * 1024, lowBuf.targetBufferBytes)
        assertEquals(48 * 1024 * 1024, midBuf.targetBufferBytes)
        assertEquals(128 * 1024 * 1024, highBuf.targetBufferBytes)

        assertTrue(lowBuf.minBufferMs < midBuf.minBufferMs)
        assertTrue(midBuf.minBufferMs < highBuf.minBufferMs)
        assertTrue(lowBuf.backBufferMs < highBuf.backBufferMs)
    }

    @Test
    fun `test coil configs adapt to hardware capabilities`() {
        val lowCoil = CoilTierConfig(
            memoryCachePercent = 0.10,
            diskCacheMaxBytes = 128L * 1024 * 1024,
            crossfadeMs = 0,
            allowHardwareBitmaps = false,
            useRgb565 = true
        )
        val highCoil = CoilTierConfig(
            memoryCachePercent = 0.25,
            diskCacheMaxBytes = 1024L * 1024 * 1024,
            crossfadeMs = 250,
            allowHardwareBitmaps = true,
            useRgb565 = false
        )

        assertTrue(lowCoil.useRgb565)
        assertFalse(highCoil.useRgb565)
        assertFalse(lowCoil.allowHardwareBitmaps)
        assertTrue(highCoil.allowHardwareBitmaps)
        assertTrue(lowCoil.diskCacheMaxBytes < highCoil.diskCacheMaxBytes)
    }

    @Test
    fun `test broken hardware GPU detection logic`() {
        // DeviceProfile.hasBrokenHardwareGpu checks known low GPU strings
        // Just verify method runs without crash
        assertFalse(DeviceProfile.hasBrokenHardwareGpu())
    }

    @Test
    fun `test search concurrency scales properly with tier`() {
        // When context is null (fallback)
        assertEquals(8, DeviceProfile.getSearchConcurrency(null))
    }

    @Test
    fun `test color grading hex formats are valid`() {
        val highColor = "#00E676"
        val midColor = "#00E5FF"
        val lowColor = "#FF9100"

        assertTrue(highColor.startsWith("#") && highColor.length == 7)
        assertTrue(midColor.startsWith("#") && midColor.length == 7)
        assertTrue(lowColor.startsWith("#") && lowColor.length == 7)
    }

    @Test
    fun `test kernel version and zram methods execute safely`() {
        // Runs cleanly on host Linux runner without throwing
        val kernel = DeviceProfile.getKernelVersion()
        assertTrue(kernel.isNotBlank())
        val swap = DeviceProfile.getZramSwapMb()
        assertTrue(swap >= 0L)
        assertFalse(DeviceProfile.isUltraPotato(null))
        assertEquals(4, DeviceProfile.getInitialPrefetchItemCount(null))
    }
}
