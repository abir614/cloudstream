package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.ui.coordinators.LayoutMode
import com.lagradost.cloudstream3.ui.coordinators.TvLayoutCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TvLayoutCoordinatorTest {

    private lateinit var coordinator: TvLayoutCoordinator

    @Before
    fun setup() {
        coordinator = TvLayoutCoordinator()
    }

    @Test
    fun `test phone layout resolves when no TV flags or features are active`() {
        val mode = coordinator.resolveLayoutMode(
            userPreferenceLayout = 0,
            isSystemTelevision = false,
            hasLeanbackFeature = false
        )
        assertEquals(LayoutMode.PHONE_TABLET, mode)
    }

    @Test
    fun `test user TV preference forces TV layout`() {
        val mode = coordinator.resolveLayoutMode(
            userPreferenceLayout = 1, // TV flag
            isSystemTelevision = false,
            hasLeanbackFeature = false
        )
        assertEquals(LayoutMode.TV, mode)
    }

    @Test
    fun `test system television or leanback hardware features force TV layout`() {
        val mode1 = coordinator.resolveLayoutMode(
            userPreferenceLayout = 0,
            isSystemTelevision = true,
            hasLeanbackFeature = false
        )
        assertEquals(LayoutMode.TV, mode1)

        val mode2 = coordinator.resolveLayoutMode(
            userPreferenceLayout = 0,
            isSystemTelevision = false,
            hasLeanbackFeature = true
        )
        assertEquals(LayoutMode.TV, mode2)
    }

    @Test
    fun `test emulator preference resolves to EMULATOR`() {
        val mode = coordinator.resolveLayoutMode(
            userPreferenceLayout = 2, // Emulator flag
            isSystemTelevision = false,
            hasLeanbackFeature = false
        )
        assertEquals(LayoutMode.EMULATOR, mode)
    }

    @Test
    fun `test shouldRenderFocusOutline only enabled in TV mode with animated outline flag`() {
        assertTrue(coordinator.shouldRenderFocusOutline(LayoutMode.TV, userAnimatedOutlineEnabled = true))
        assertFalse(coordinator.shouldRenderFocusOutline(LayoutMode.TV, userAnimatedOutlineEnabled = false))
        assertFalse(coordinator.shouldRenderFocusOutline(LayoutMode.PHONE_TABLET, userAnimatedOutlineEnabled = true))
    }

    @Test
    fun `test isFocusCenteringException identifies excluded control buttons`() {
        val exceptions = setOf(101, 102, 103)
        assertTrue(coordinator.isFocusCenteringException(101, exceptions))
        assertFalse(coordinator.isFocusCenteringException(999, exceptions))
    }
}
