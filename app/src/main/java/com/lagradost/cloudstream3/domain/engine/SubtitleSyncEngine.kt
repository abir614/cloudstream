package com.lagradost.cloudstream3.domain.engine

import kotlin.math.abs

/**
 * Enterprise Subtitle Synchronization & Timecode Engine.
 * Decouples subtitle offset arithmetic, bounds safety, step incrementing,
 * and timecode alignment from the monolithic player controller.
 */
class SubtitleSyncEngine(
    val maxOffsetMs: Long = 1_800_000L // Bound to ±30 minutes
) {
    private var currentOffsetMs: Long = 0L

    companion object {
        const val STEP_FINE_MS = 100L
        const val STEP_STANDARD_MS = 250L
        const val STEP_COARSE_MS = 1000L
    }

    /**
     * Gets the current subtitle offset in milliseconds.
     */
    fun getOffset(): Long = currentOffsetMs

    /**
     * Sets the subtitle offset, clamping to safe operational bounds.
     */
    fun setOffset(offsetMs: Long): Long {
        currentOffsetMs = offsetMs.coerceIn(-maxOffsetMs, maxOffsetMs)
        return currentOffsetMs
    }

    /**
     * Adjusts the current offset by a delta in milliseconds.
     */
    fun adjustOffsetBy(deltaMs: Long): Long {
        return setOffset(currentOffsetMs + deltaMs)
    }

    /**
     * Resets the offset back to 0.
     */
    fun reset(): Long {
        currentOffsetMs = 0L
        return 0L
    }

    /**
     * Applies the current offset to a given subtitle cue timecode, clamping at 0ms.
     */
    fun applyOffset(timecodeMs: Long): Long {
        return (timecodeMs + currentOffsetMs).coerceAtLeast(0L)
    }

    /**
     * Checks if a subtitle offset is currently active.
     */
    fun isOffsetActive(): Boolean = currentOffsetMs != 0L

    /**
     * Formats the current offset into a human-readable string for UI display.
     * Examples: "+250 ms", "-1.50 s", "0 ms"
     */
    fun formatOffset(): String {
        if (currentOffsetMs == 0L) return "0 ms"

        val prefix = if (currentOffsetMs > 0) "+" else "-"
        val absVal = abs(currentOffsetMs)

        return if (absVal >= 1000L) {
            val seconds = absVal / 1000.0
            String.format(java.util.Locale.US, "%s%.2f s", prefix, seconds)
        } else {
            "$prefix$absVal ms"
        }
    }
}
