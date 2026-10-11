package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.domain.engine.FailoverDecision
import com.lagradost.cloudstream3.domain.engine.PlaybackErrorType
import com.lagradost.cloudstream3.domain.engine.StreamFailoverEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class StreamFailoverEngineTest {

    private lateinit var engine: StreamFailoverEngine

    @Before
    fun setup() {
        engine = StreamFailoverEngine(maxTransientRetries = 3, initialRetryDelayMs = 300L)
    }

    @Test
    fun `test error classification for transient network glitches`() {
        assertEquals(
            PlaybackErrorType.TRANSIENT_NETWORK,
            engine.classifyError(StreamFailoverEngine.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null)
        )
        assertEquals(
            PlaybackErrorType.TRANSIENT_NETWORK,
            engine.classifyError(StreamFailoverEngine.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null)
        )
        assertEquals(
            PlaybackErrorType.TRANSIENT_NETWORK,
            engine.classifyError(StreamFailoverEngine.ERROR_CODE_TIMEOUT, null)
        )
        assertEquals(
            PlaybackErrorType.TRANSIENT_NETWORK,
            engine.classifyError(StreamFailoverEngine.ERROR_CODE_IO_UNSPECIFIED, SocketTimeoutException("Read timed out"))
        )
        assertEquals(
            PlaybackErrorType.TRANSIENT_NETWORK,
            engine.classifyError(StreamFailoverEngine.ERROR_CODE_IO_UNSPECIFIED, IOException("Connection reset by peer"))
        )
    }

    @Test
    fun `test transient network error retries with exponential backoff until exhaustion`() {
        // Attempt 1: delay = 300ms
        val d1 = engine.handlePlaybackError(StreamFailoverEngine.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null)
        assertTrue(d1 is FailoverDecision.RetryInPlace)
        val r1 = d1 as FailoverDecision.RetryInPlace
        assertEquals(1, r1.attempt)
        assertEquals(300L, r1.delayMs)

        // Attempt 2: delay = 600ms
        val d2 = engine.handlePlaybackError(StreamFailoverEngine.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null)
        assertTrue(d2 is FailoverDecision.RetryInPlace)
        val r2 = d2 as FailoverDecision.RetryInPlace
        assertEquals(2, r2.attempt)
        assertEquals(600L, r2.delayMs)

        // Attempt 3: delay = 1200ms
        val d3 = engine.handlePlaybackError(StreamFailoverEngine.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null)
        assertTrue(d3 is FailoverDecision.RetryInPlace)
        val r3 = d3 as FailoverDecision.RetryInPlace
        assertEquals(3, r3.attempt)
        assertEquals(1200L, r3.delayMs)

        // Attempt 4: retries exhausted -> trigger mirror failover
        val d4 = engine.handlePlaybackError(StreamFailoverEngine.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null)
        assertTrue(d4 is FailoverDecision.FatalTriggerFailover)
        val f = d4 as FailoverDecision.FatalTriggerFailover
        assertEquals(PlaybackErrorType.TRANSIENT_NETWORK, f.errorType)
    }

    @Test
    fun `test resetRetries clears retry count`() {
        engine.handlePlaybackError(StreamFailoverEngine.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null)
        assertEquals(1, engine.getRetryCount())

        engine.resetRetries()
        assertEquals(0, engine.getRetryCount())

        val next = engine.handlePlaybackError(StreamFailoverEngine.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null)
        assertTrue(next is FailoverDecision.RetryInPlace)
        assertEquals(1, (next as FailoverDecision.RetryInPlace).attempt)
    }

    @Test
    fun `test behind live window returns SeekToLiveDefault`() {
        val decision = engine.handlePlaybackError(StreamFailoverEngine.ERROR_CODE_BEHIND_LIVE_WINDOW, null)
        assertTrue(decision is FailoverDecision.SeekToLiveDefault)
    }

    @Test
    fun `test fatal container corruption immediately triggers failover without retrying`() {
        val decision = engine.handlePlaybackError(StreamFailoverEngine.ERROR_CODE_PARSER_CONTAINER_MALFORMED, null)
        assertTrue(decision is FailoverDecision.FatalTriggerFailover)
        assertEquals(PlaybackErrorType.FATAL_CORRUPT, (decision as FailoverDecision.FatalTriggerFailover).errorType)
        assertEquals(0, engine.getRetryCount())
    }

    @Test
    fun `test playlist stuck seeks ahead of live when gap is significant`() {
        class MockPlaylistStuckException : Exception("PlaylistStuckException")

        val stuckDecision = engine.handlePlaybackError(
            errorCode = StreamFailoverEngine.ERROR_CODE_IO_UNSPECIFIED,
            cause = MockPlaylistStuckException(),
            currentPositionMs = 50_000L,
            aheadOfLiveMs = 500L
        )

        assertTrue(stuckDecision is FailoverDecision.SeekAheadOfLive)
        val seek = stuckDecision as FailoverDecision.SeekAheadOfLive
        assertEquals(49_500L, seek.seekPositionMs)
    }
}
