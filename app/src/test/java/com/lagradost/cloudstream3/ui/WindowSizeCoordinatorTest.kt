package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.ui.coordinators.WindowHeightSizeClass
import com.lagradost.cloudstream3.ui.coordinators.WindowSizeCoordinator
import com.lagradost.cloudstream3.ui.coordinators.WindowWidthSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSizeCoordinatorTest {

    @Test
    fun `test resolveWidthSizeClass maps correctly to Material 3 breakpoints`() {
        // Compact (< 600dp)
        assertEquals(WindowWidthSizeClass.COMPACT, WindowSizeCoordinator.resolveWidthSizeClass(360f))
        assertEquals(WindowWidthSizeClass.COMPACT, WindowSizeCoordinator.resolveWidthSizeClass(599f))

        // Medium (600dp - 839dp)
        assertEquals(WindowWidthSizeClass.MEDIUM, WindowSizeCoordinator.resolveWidthSizeClass(600f))
        assertEquals(WindowWidthSizeClass.MEDIUM, WindowSizeCoordinator.resolveWidthSizeClass(768f))
        assertEquals(WindowWidthSizeClass.MEDIUM, WindowSizeCoordinator.resolveWidthSizeClass(839f))

        // Expanded (>= 840dp)
        assertEquals(WindowWidthSizeClass.EXPANDED, WindowSizeCoordinator.resolveWidthSizeClass(840f))
        assertEquals(WindowWidthSizeClass.EXPANDED, WindowSizeCoordinator.resolveWidthSizeClass(1280f))
    }

    @Test
    fun `test resolveHeightSizeClass maps correctly to Material 3 breakpoints`() {
        assertEquals(WindowHeightSizeClass.COMPACT, WindowSizeCoordinator.resolveHeightSizeClass(360f))
        assertEquals(WindowHeightSizeClass.MEDIUM, WindowSizeCoordinator.resolveHeightSizeClass(640f))
        assertEquals(WindowHeightSizeClass.EXPANDED, WindowSizeCoordinator.resolveHeightSizeClass(960f))
    }

    @Test
    fun `test TV mode returns fixed optimal spans`() {
        val dummyContext = android.content.ContextWrapper(null)
        // TV posters
        val tvPosterSpan = WindowSizeCoordinator.calculateGridSpanCount(
            context = dummyContext,
            isHorizontalItem = false,
            isTv = true
        )
        assertEquals(7, tvPosterSpan)

        // TV banners
        val tvBannerSpan = WindowSizeCoordinator.calculateGridSpanCount(
            context = dummyContext,
            isHorizontalItem = true,
            isTv = true
        )
        assertEquals(4, tvBannerSpan)
    }

    @Test
    fun `test getResponsiveDialogWidthPercent scales with size class`() {
        val tvContext = android.content.ContextWrapper(null)
        val tvDialogPercent = WindowSizeCoordinator.getResponsiveDialogWidthPercent(tvContext, isTv = true)
        assertEquals(0.60f, tvDialogPercent, 0.01f)
    }
}
