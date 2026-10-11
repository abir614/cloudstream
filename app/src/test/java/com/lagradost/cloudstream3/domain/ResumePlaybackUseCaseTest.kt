package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.domain.usecase.ResumePlaybackUseCase
import com.lagradost.cloudstream3.utils.persistence.DataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ResumePlaybackUseCaseTest {

    private class MockDataRepository : DataRepository {
        val map = ConcurrentHashMap<String, Any>()
        override fun <T : Any> get(key: String, clazz: Class<T>): T? {
            @Suppress("UNCHECKED_CAST")
            return map[key] as? T
        }

        override fun <T : Any> set(key: String, value: T): Boolean {
            map[key] = value
            return true
        }

        override fun remove(key: String): Boolean = map.remove(key) != null
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clearAll(): Boolean {
            map.clear()
            return true
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> observe(key: String, clazz: Class<T>, defaultValue: T?): Flow<T?> {
            return MutableStateFlow(get(key, clazz) ?: defaultValue).asStateFlow()
        }
    }

    private lateinit var repository: MockDataRepository
    private lateinit var useCase: ResumePlaybackUseCase

    @Before
    fun setup() {
        repository = MockDataRepository()
        useCase = ResumePlaybackUseCase(repository)
    }

    @Test
    fun `test video shorter than minimum threshold is not resumable`() {
        // Video is 20 seconds (< 30 seconds minimum)
        val progress = useCase.evaluateProgress(positionMs = 10_000L, durationMs = 20_000L)
        assertFalse(progress.shouldResume)
    }

    @Test
    fun `test position within first 5 seconds does not trigger resume`() {
        // Video is 20 minutes, current position is 3 seconds
        val progress = useCase.evaluateProgress(positionMs = 3_000L, durationMs = 1_200_000L)
        assertFalse(progress.shouldResume)
        assertEquals(0, progress.percentage)
    }

    @Test
    fun `test position mid-video is marked resumable with accurate percentage`() {
        // 50% through a 10-minute video
        val progress = useCase.evaluateProgress(positionMs = 300_000L, durationMs = 600_000L)
        assertTrue(progress.shouldResume)
        assertFalse(progress.isCompleted)
        assertEquals(50, progress.percentage)
    }

    @Test
    fun `test position past completion threshold is marked completed and not resumable`() {
        // 90% through a video (completion threshold is 85%)
        val progress = useCase.evaluateProgress(positionMs = 900_000L, durationMs = 1_000_000L)
        assertTrue(progress.isCompleted)
        assertFalse(progress.shouldResume)
        assertEquals(90, progress.percentage)
    }

    @Test
    fun `test decideResumeTarget advances to next episode when current is completed`() {
        val decision = useCase.decideResumeTarget(
            currentEpisodeId = 101,
            nextEpisodeId = 102,
            positionMs = 950_000L,
            durationMs = 1_000_000L
        )

        assertTrue(decision.isAdvancingToNext)
        assertEquals(102, decision.targetEpisodeId)
        assertEquals(0L, decision.startPositionMs)
    }

    @Test
    fun `test decideResumeTarget resumes current episode when in progress`() {
        val decision = useCase.decideResumeTarget(
            currentEpisodeId = 101,
            nextEpisodeId = 102,
            positionMs = 450_000L,
            durationMs = 1_000_000L
        )

        assertFalse(decision.isAdvancingToNext)
        assertEquals(101, decision.targetEpisodeId)
        assertEquals(450_000L, decision.startPositionMs)
    }

    @Test
    fun `test savePosition and getSavedPosition round trip`() {
        val episodeId = 55
        val duration = 1_000_000L
        val position = 350_000L

        val saved = useCase.savePosition(episodeId, position, duration)
        assertTrue(saved)

        val retrieved = useCase.getSavedPosition(episodeId, duration)
        assertEquals(position, retrieved)

        useCase.clearPosition(episodeId)
        val afterClear = useCase.getSavedPosition(episodeId, duration)
        assertEquals(0L, afterClear)
    }
}
