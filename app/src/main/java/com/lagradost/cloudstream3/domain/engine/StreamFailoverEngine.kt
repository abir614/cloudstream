package com.lagradost.cloudstream3.domain.engine

/**
 * Categorization of playback exceptions for deterministic recovery and failover.
 */
enum class PlaybackErrorType {
    TRANSIENT_NETWORK,
    BEHIND_LIVE_WINDOW,
    PLAYLIST_STUCK,
    FATAL_CORRUPT,
    UNKNOWN
}

/**
 * Resulting action decided by the failover engine when playback encounters an error.
 */
sealed class FailoverDecision {
    data class RetryInPlace(val attempt: Int, val maxAttempts: Int, val delayMs: Long) : FailoverDecision()
    object SeekToLiveDefault : FailoverDecision()
    data class SeekAheadOfLive(val seekPositionMs: Long) : FailoverDecision()
    data class FatalTriggerFailover(val reason: String, val errorType: PlaybackErrorType) : FailoverDecision()
}

/**
 * Enterprise Playback Failover & Transient Error Recovery Engine.
 * Decouples playback error classification, retry backoff, live window realignment,
 * and failover decisions from the monolithic ExoPlayer UI layer.
 */
class StreamFailoverEngine(
    val maxTransientRetries: Int = 3,
    val initialRetryDelayMs: Long = 300L
) {
    private var currentRetryCount: Int = 0

    companion object {
        // ExoPlayer Error Codes
        const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
        const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val ERROR_CODE_TIMEOUT = 2003
        const val ERROR_CODE_IO_UNSPECIFIED = 2000
        const val ERROR_CODE_BEHIND_LIVE_WINDOW = 1002
        const val ERROR_CODE_PARSER_CONTAINER_MALFORMED = 3001
    }

    /**
     * Classifies a playback error code and exception cause into a domain error type.
     */
    fun classifyError(errorCode: Int, cause: Throwable?): PlaybackErrorType {
        return when {
            errorCode == ERROR_CODE_BEHIND_LIVE_WINDOW ->
                PlaybackErrorType.BEHIND_LIVE_WINDOW

            cause != null && cause::class.java.simpleName.contains("PlaylistStuckException") ->
                PlaybackErrorType.PLAYLIST_STUCK

            errorCode == ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    errorCode == ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    errorCode == ERROR_CODE_TIMEOUT ->
                PlaybackErrorType.TRANSIENT_NETWORK

            errorCode == ERROR_CODE_IO_UNSPECIFIED && cause != null &&
                    (cause is java.io.IOException || cause is java.net.SocketTimeoutException) ->
                PlaybackErrorType.TRANSIENT_NETWORK

            errorCode == ERROR_CODE_PARSER_CONTAINER_MALFORMED ->
                PlaybackErrorType.FATAL_CORRUPT

            else -> PlaybackErrorType.UNKNOWN
        }
    }

    /**
     * Evaluates a playback error and determines the appropriate recovery action.
     */
    fun handlePlaybackError(
        errorCode: Int,
        cause: Throwable?,
        currentPositionMs: Long = 0L,
        durationMs: Long = 0L,
        aheadOfLiveMs: Long? = null
    ): FailoverDecision {
        val errorType = classifyError(errorCode, cause)

        return when (errorType) {
            PlaybackErrorType.TRANSIENT_NETWORK -> {
                if (currentRetryCount < maxTransientRetries) {
                    currentRetryCount++
                    val delay = initialRetryDelayMs * (1L shl (currentRetryCount - 1))
                    FailoverDecision.RetryInPlace(
                        attempt = currentRetryCount,
                        maxAttempts = maxTransientRetries,
                        delayMs = delay
                    )
                } else {
                    FailoverDecision.FatalTriggerFailover(
                        reason = "Transient network retries exhausted ($maxTransientRetries/$maxTransientRetries)",
                        errorType = errorType
                    )
                }
            }

            PlaybackErrorType.BEHIND_LIVE_WINDOW -> {
                FailoverDecision.SeekToLiveDefault
            }

            PlaybackErrorType.PLAYLIST_STUCK -> {
                val ahead = aheadOfLiveMs ?: 0L
                if (ahead > 100L) {
                    val target = (currentPositionMs - ahead).coerceAtLeast(0L)
                    FailoverDecision.SeekAheadOfLive(target)
                } else {
                    FailoverDecision.SeekToLiveDefault
                }
            }

            PlaybackErrorType.FATAL_CORRUPT -> {
                FailoverDecision.FatalTriggerFailover(
                    reason = "Fatal container corruption or parsing error",
                    errorType = errorType
                )
            }

            PlaybackErrorType.UNKNOWN -> {
                FailoverDecision.FatalTriggerFailover(
                    reason = cause?.message ?: "Unknown playback error (code $errorCode)",
                    errorType = errorType
                )
            }
        }
    }

    /**
     * Resets the retry counter upon successful playback progress or user track switch.
     */
    fun resetRetries() {
        currentRetryCount = 0
    }

    /**
     * Returns current consecutive retry count.
     */
    fun getRetryCount(): Int = currentRetryCount
}
