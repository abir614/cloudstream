package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.domain.engine.SubtitleSyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubtitleSyncEngineTest {

    private lateinit var engine: SubtitleSyncEngine

    @Before
    fun setup() {
        engine = SubtitleSyncEngine(maxOffsetMs = 1_800_000L) // ±30 mins
    }

    @Test
    fun `test initial state is zero and inactive`() {
        assertEquals(0L, engine.getOffset())
        assertFalse(engine.isOffsetActive())
        assertEquals("0 ms", engine.formatOffset())
    }

    @Test
    fun `test setOffset clamps to operational bounds`() {
        engine.setOffset(500L)
        assertEquals(500L, engine.getOffset())
        assertTrue(engine.isOffsetActive())

        // Exceeding max bound
        engine.setOffset(3_000_000L)
        assertEquals(1_800_000L, engine.getOffset())

        // Exceeding negative bound
        engine.setOffset(-3_000_000L)
        assertEquals(-1_800_000L, engine.getOffset())
    }

    @Test
    fun `test adjustOffsetBy applies incremental steps`() {
        engine.adjustOffsetBy(SubtitleSyncEngine.STEP_FINE_MS) // +100ms
        assertEquals(100L, engine.getOffset())

        engine.adjustOffsetBy(SubtitleSyncEngine.STEP_STANDARD_MS) // +250ms -> 350ms
        assertEquals(350L, engine.getOffset())

        engine.adjustOffsetBy(-SubtitleSyncEngine.STEP_COARSE_MS) // -1000ms -> -650ms
        assertEquals(-650L, engine.getOffset())
    }

    @Test
    fun `test applyOffset shifts cue timecode with zero lower bound`() {
        engine.setOffset(500L)
        assertEquals(1500L, engine.applyOffset(1000L))

        engine.setOffset(-500L)
        assertEquals(500L, engine.applyOffset(1000L))

        // Large negative offset should clamp at 0ms, not produce negative timecode
        engine.setOffset(-5000L)
        assertEquals(0L, engine.applyOffset(1000L))
    }

    @Test
    fun `test reset restores zero offset`() {
        engine.setOffset(1500L)
        assertTrue(engine.isOffsetActive())

        val resetVal = engine.reset()
        assertEquals(0L, resetVal)
        assertEquals(0L, engine.getOffset())
        assertFalse(engine.isOffsetActive())
    }

    @Test
    fun `test formatOffset formats sub-second and multi-second offsets`() {
        engine.setOffset(250L)
        assertEquals("+250 ms", engine.formatOffset())

        engine.setOffset(-750L)
        assertEquals("-750 ms", engine.formatOffset())

        engine.setOffset(1500L)
        assertEquals("+1.50 s", engine.formatOffset())

        engine.setOffset(-2250L)
        assertEquals("-2.25 s", engine.formatOffset())
    }
}
