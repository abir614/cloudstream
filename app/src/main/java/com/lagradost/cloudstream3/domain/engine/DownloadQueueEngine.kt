package com.lagradost.cloudstream3.domain.engine

/**
 * Download state lifecycle enum.
 */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

/**
 * Represents a queued or active download item in the domain layer.
 */
data class DomainDownloadItem(
    val id: Int,
    val sourceUrl: String,
    val title: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val state: DownloadState,
    val retryCount: Int = 0,
    val priority: Int = 0, // Higher numbers take precedence
    val queuedTimestamp: Long = System.currentTimeMillis()
)

/**
 * Enterprise Download Queue & Resilience Engine.
 * Decouples queue prioritization, retry backoff calculation, fatal error evaluation,
 * and hardware-adaptive RAM segment allocation from Android Services and background Workers.
 */
class DownloadQueueEngine(
    val maxRetries: Int = 5,
    val initialRetryDelayMs: Long = 1000L,
    val maxRetryDelayMs: Long = 30_000L
) {
    /**
     * Calculates integer progress percentage [0, 100].
     */
    fun calculateProgress(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        val clamped = downloadedBytes.coerceIn(0L, totalBytes)
        return ((clamped * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    /**
     * Computes exponential backoff delay for retrying failed segments or downloads.
     */
    fun computeRetryDelayMs(retryCount: Int): Long {
        if (retryCount <= 0) return 0L
        val multiplier = 1L shl (retryCount - 1).coerceAtMost(5) // Max 2^5 = 32x
        val delay = initialRetryDelayMs * multiplier
        return delay.coerceAtMost(maxRetryDelayMs)
    }

    /**
     * Evaluates whether an error is non-recoverable (fatal) and should immediately halt retries.
     */
    fun isFatalError(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        val msg = throwable.message?.lowercase() ?: ""

        return when {
            // Out of disk space
            msg.contains("enospc") || msg.contains("no space left on device") -> true
            // Permission / Read-only filesystem
            msg.contains("eacces") || msg.contains("permission denied") || msg.contains("read-only file system") -> true
            // Invalid URI or blocked protocol
            throwable is java.net.MalformedURLException -> true
            else -> false
        }
    }

    /**
     * Determines whether a failed download should be retried.
     */
    fun shouldRetry(currentRetryCount: Int, throwable: Throwable?): Boolean {
        if (isFatalError(throwable)) return false
        return currentRetryCount < maxRetries
    }

    /**
     * Sorts a list of download items by priority (higher priority first)
     * and secondarily by FIFO queuing timestamp.
     */
    fun prioritizeQueue(items: List<DomainDownloadItem>): List<DomainDownloadItem> {
        return items.sortedWith(
            compareByDescending<DomainDownloadItem> { it.priority }
                .thenBy { it.queuedTimestamp }
        )
    }

    /**
     * Computes optimal concurrent segment download threads based on device RAM tier.
     * Prevents Out-Of-Memory errors on low-end TV sticks (512MB RAM).
     */
    fun computeOptimalSegmentConcurrency(totalRamBytes: Long, isLowRamDevice: Boolean): Int {
        val isPotato = isLowRamDevice || (totalRamBytes in 1..805306368L) // <= 768MB
        val isMid = totalRamBytes in 805306369L..2147483648L             // ~1GB - 2GB

        return when {
            isPotato -> 1 // Single thread to avoid OOM on 512MB RAM sticks
            isMid -> 2    // Dual thread for balanced throughput
            else -> 4     // Quad thread for high-end phones and tablets
        }
    }
}
