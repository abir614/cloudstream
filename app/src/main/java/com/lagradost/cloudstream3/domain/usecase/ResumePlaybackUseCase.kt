package com.lagradost.cloudstream3.domain.usecase

import com.lagradost.cloudstream3.utils.persistence.DataRepository

/**
 * Domain UseCase that orchestrates playback progress, resume positions, and watch states.
 * Decouples resume business rules from UI Controllers and static DataStore helpers.
 */
class ResumePlaybackUseCase(
    private val repository: DataRepository
) {

    data class PlaybackProgress(
        val positionMs: Long,
        val durationMs: Long,
        val percentage: Int,
        val isCompleted: Boolean,
        val shouldResume: Boolean
    )

    data class ResumeDecision(
        val targetEpisodeId: Int?,
        val startPositionMs: Long,
        val isAdvancingToNext: Boolean
    )

    companion object {
        const val MIN_DURATION_FOR_RESUME_MS = 30_000L      // Videos < 30 seconds are not resumed
        const val MIN_RESUME_START_OFFSET_MS = 5_000L       // Don't resume within first 5 seconds
        const val COMPLETION_THRESHOLD_PERCENT = 85         // >= 85% is considered Watched
        const val KEY_PREFIX_RESUME = "resume_pos_"
    }

    /**
     * Calculates the normalized progress and completion status for a given playback position.
     */
    fun evaluateProgress(positionMs: Long, durationMs: Long): PlaybackProgress {
        if (durationMs <= 0) {
            return PlaybackProgress(
                positionMs = positionMs,
                durationMs = durationMs,
                percentage = 0,
                isCompleted = false,
                shouldResume = false
            )
        }

        val clampedPos = positionMs.coerceIn(0L, durationMs)
        val percent = ((clampedPos * 100L) / durationMs).toInt().coerceIn(0, 100)
        val isCompleted = percent >= COMPLETION_THRESHOLD_PERCENT

        // Only resume if video is long enough, hasn't reached end credits, and isn't at the very start
        val shouldResume = (durationMs >= MIN_DURATION_FOR_RESUME_MS) &&
                !isCompleted &&
                (clampedPos >= MIN_RESUME_START_OFFSET_MS)

        return PlaybackProgress(
            positionMs = clampedPos,
            durationMs = durationMs,
            percentage = percent,
            isCompleted = isCompleted,
            shouldResume = shouldResume
        )
    }

    /**
     * Determines whether to resume the current episode or advance to the next episode based on progress.
     */
    fun decideResumeTarget(
        currentEpisodeId: Int,
        nextEpisodeId: Int?,
        positionMs: Long,
        durationMs: Long
    ): ResumeDecision {
        val progress = evaluateProgress(positionMs, durationMs)

        return if (progress.isCompleted && nextEpisodeId != null) {
            // Episode finished: advance to next episode starting at 0
            ResumeDecision(
                targetEpisodeId = nextEpisodeId,
                startPositionMs = 0L,
                isAdvancingToNext = true
            )
        } else if (progress.shouldResume) {
            // In progress: resume where left off
            ResumeDecision(
                targetEpisodeId = currentEpisodeId,
                startPositionMs = progress.positionMs,
                isAdvancingToNext = false
            )
        } else {
            // Start from beginning
            ResumeDecision(
                targetEpisodeId = currentEpisodeId,
                startPositionMs = 0L,
                isAdvancingToNext = false
            )
        }
    }

    /**
     * Persists playback position using the decoupled DataRepository.
     */
    fun savePosition(episodeId: Int, positionMs: Long, durationMs: Long): Boolean {
        if (durationMs < MIN_DURATION_FOR_RESUME_MS) return false
        val key = "$KEY_PREFIX_RESUME$episodeId"
        val clampedPos = positionMs.coerceIn(0L, durationMs)
        return repository.set(key, clampedPos)
    }

    /**
     * Retrieves saved playback position for an episode, returning 0L if not saved or completed.
     */
    fun getSavedPosition(episodeId: Int, durationMs: Long): Long {
        val key = "$KEY_PREFIX_RESUME$episodeId"
        val saved = repository.get(key, java.lang.Long::class.java)?.toLong() ?: return 0L
        val progress = evaluateProgress(saved, durationMs)
        return if (progress.shouldResume) progress.positionMs else 0L
    }

    /**
     * Clears resume position for an episode (e.g. when marked uncompleted or fully finished).
     */
    fun clearPosition(episodeId: Int): Boolean {
        val key = "$KEY_PREFIX_RESUME$episodeId"
        return repository.remove(key)
    }
}
