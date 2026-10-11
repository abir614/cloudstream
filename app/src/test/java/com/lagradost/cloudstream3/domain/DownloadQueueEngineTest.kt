package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.domain.engine.DomainDownloadItem
import com.lagradost.cloudstream3.domain.engine.DownloadQueueEngine
import com.lagradost.cloudstream3.domain.engine.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.MalformedURLException

class DownloadQueueEngineTest {

    private lateinit var engine: DownloadQueueEngine

    @Before
    fun setup() {
        engine = DownloadQueueEngine(maxRetries = 5, initialRetryDelayMs = 1000L, maxRetryDelayMs = 30_000L)
    }

    @Test
    fun `test progress percentage calculations`() {
        assertEquals(0, engine.calculateProgress(0L, 1000L))
        assertEquals(50, engine.calculateProgress(500L, 1000L))
        assertEquals(100, engine.calculateProgress(1000L, 1000L))
        assertEquals(100, engine.calculateProgress(1500L, 1000L)) // Clamped at 100%
        assertEquals(0, engine.calculateProgress(500L, 0L))     // Zero total bytes handled safely
    }

    @Test
    fun `test exponential retry backoff calculation`() {
        assertEquals(0L, engine.computeRetryDelayMs(0))
        assertEquals(1000L, engine.computeRetryDelayMs(1))  // 1s * 1 = 1s
        assertEquals(2000L, engine.computeRetryDelayMs(2))  // 1s * 2 = 2s
        assertEquals(4000L, engine.computeRetryDelayMs(3))  // 1s * 4 = 4s
        assertEquals(8000L, engine.computeRetryDelayMs(4))  // 1s * 8 = 8s
        assertEquals(16000L, engine.computeRetryDelayMs(5)) // 1s * 16 = 16s

        // Large retry count is capped at maxRetryDelayMs (30s)
        assertEquals(30000L, engine.computeRetryDelayMs(10))
    }

    @Test
    fun `test fatal error detection prevents hopeless retries`() {
        // Disk full errors
        assertTrue(engine.isFatalError(IOException("write failed: ENOSPC (No space left on device)")))
        // Permission denied
        assertTrue(engine.isFatalError(IOException("open failed: EACCES (Permission denied)")))
        // Malformed URL
        assertTrue(engine.isFatalError(MalformedURLException("no protocol: invalid_stream")))

        // Transient network drops are NOT fatal
        assertFalse(engine.isFatalError(IOException("Connection reset by peer")))
        assertFalse(engine.isFatalError(java.net.SocketTimeoutException("Timeout")))
    }

    @Test
    fun `test shouldRetry respects fatal errors and retry limits`() {
        // Transient error within limit should retry
        assertTrue(engine.shouldRetry(1, IOException("Socket closed")))
        assertTrue(engine.shouldRetry(4, IOException("Socket closed")))

        // Retries exhausted
        assertFalse(engine.shouldRetry(5, IOException("Socket closed")))

        // Fatal disk error should never retry even on attempt 0
        assertFalse(engine.shouldRetry(0, IOException("ENOSPC: No space left on device")))
    }

    @Test
    fun `test queue prioritization respects user priority and FIFO ordering`() {
        val now = System.currentTimeMillis()
        val itemLow = DomainDownloadItem(1, "url1", "Item Low", 1000L, 0L, DownloadState.QUEUED, priority = 0, queuedTimestamp = now)
        val itemHigh = DomainDownloadItem(2, "url2", "Item High", 1000L, 0L, DownloadState.QUEUED, priority = 10, queuedTimestamp = now + 100)
        val itemNormalEarly = DomainDownloadItem(3, "url3", "Item Early", 1000L, 0L, DownloadState.QUEUED, priority = 5, queuedTimestamp = now - 50)
        val itemNormalLate = DomainDownloadItem(4, "url4", "Item Late", 1000L, 0L, DownloadState.QUEUED, priority = 5, queuedTimestamp = now + 50)

        val prioritized = engine.prioritizeQueue(listOf(itemLow, itemHigh, itemNormalLate, itemNormalEarly))

        // High priority first, then normal sorted by early timestamp, then low
        assertEquals(2, prioritized[0].id)
        assertEquals(3, prioritized[1].id)
        assertEquals(4, prioritized[2].id)
        assertEquals(1, prioritized[3].id)
    }

    @Test
    fun `test hardware-adaptive segment concurrency tunes to RAM tier`() {
        val ram512Mb = 512L * 1024 * 1024
        val ram1Gb = 1024L * 1024 * 1024
        val ram4Gb = 4096L * 1024 * 1024

        // 512MB RAM TV sticks get 1 segment thread to prevent OOM
        assertEquals(1, engine.computeOptimalSegmentConcurrency(ram512Mb, false))
        // Force low ram device flag forces 1 thread regardless of reported total RAM
        assertEquals(1, engine.computeOptimalSegmentConcurrency(ram4Gb, true))

        // 1GB RAM mid devices get 2 segment threads
        assertEquals(2, engine.computeOptimalSegmentConcurrency(ram1Gb, false))

        // 4GB RAM phones get 4 threads
        assertEquals(4, engine.computeOptimalSegmentConcurrency(ram4Gb, false))
    }
}
