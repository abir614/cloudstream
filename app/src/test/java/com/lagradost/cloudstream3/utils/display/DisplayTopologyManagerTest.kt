package com.lagradost.cloudstream3.utils.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTopologyManagerTest {

    @Test
    fun `test default ExternalDisplayState initial values`() {
        val state = ExternalDisplayState()
        assertFalse(state.isConnected)
        assertFalse(state.isHdmiHardwarePlugged)
        assertFalse(state.isHdmiAudioRouted)
        assertEquals(0, state.externalDisplayCount)
        assertEquals(0, state.primaryDisplayId)
        assertNull(state.secondaryDisplayId)
        assertNull(state.activeMonitor)
        assertTrue(state.displays.isEmpty())
    }

    @Test
    fun `test MonitorMetrics aspect ratio and 4K classification`() {
        val mode4k60 = DisplayModeInfo(modeId = 1, width = 3840, height = 2160, refreshRate = 60.0f)
        val mode4k120 = DisplayModeInfo(modeId = 2, width = 3840, height = 2160, refreshRate = 120.0f)

        val metrics4k = MonitorMetrics(
            displayId = 1,
            name = "Samsung 4K TV",
            isDefault = false,
            isPresentation = true,
            isValid = true,
            state = 2,
            physicalWidth = 3840,
            physicalHeight = 2160,
            currentRefreshRate = 60.0f,
            maxRefreshRate = 120.0f,
            supportedModes = listOf(mode4k60, mode4k120),
            widthInches = 47.6f,
            heightInches = 26.8f,
            diagonalInches = 54.6f,
            aspectRatio = 3840f / 2160f,
            is16by9 = true,
            is16by10 = false,
            isUltraWide = false,
            is4KOrHigher = true,
            isHdrSupported = true,
            supportedHdrTypes = listOf(1, 2),
            isWideColorGamut = true
        )

        assertTrue(metrics4k.is16by9)
        assertFalse(metrics4k.isUltraWide)
        assertTrue(metrics4k.is4KOrHigher)
        assertTrue(metrics4k.isHdrSupported)
        assertEquals(120.0f, metrics4k.maxRefreshRate, 0.01f)
        assertEquals(54.6f, metrics4k.diagonalInches ?: 0f, 0.1f)
    }

    @Test
    fun `test Ultrawide 21 by 9 monitor classification`() {
        val metricsUltrawide = MonitorMetrics(
            displayId = 2,
            name = "LG Ultrawide 34",
            isDefault = false,
            isPresentation = true,
            isValid = true,
            state = 2,
            physicalWidth = 3440,
            physicalHeight = 1440,
            currentRefreshRate = 144.0f,
            maxRefreshRate = 144.0f,
            supportedModes = listOf(DisplayModeInfo(1, 3440, 1440, 144.0f)),
            widthInches = 31.5f,
            heightInches = 13.2f,
            diagonalInches = 34.1f,
            aspectRatio = 3440f / 1440f,
            is16by9 = false,
            is16by10 = false,
            isUltraWide = true,
            is4KOrHigher = false,
            isHdrSupported = true,
            supportedHdrTypes = listOf(1),
            isWideColorGamut = true
        )

        assertFalse(metricsUltrawide.is16by9)
        assertTrue(metricsUltrawide.isUltraWide)
        assertFalse(metricsUltrawide.is4KOrHigher)
        assertEquals(144.0f, metricsUltrawide.maxRefreshRate, 0.01f)
        assertTrue(metricsUltrawide.aspectRatio > 2.3f)
    }

    @Test
    fun `test ExternalDisplayState connects and picks secondary display as activeMonitor`() {
        val primaryPhone = MonitorMetrics(
            displayId = 0,
            name = "Built-in Screen",
            isDefault = true,
            isPresentation = false,
            isValid = true,
            state = 2,
            physicalWidth = 1080,
            physicalHeight = 2400,
            currentRefreshRate = 120.0f,
            maxRefreshRate = 120.0f,
            supportedModes = listOf(DisplayModeInfo(1, 1080, 2400, 120.0f)),
            widthInches = 2.8f,
            heightInches = 6.2f,
            diagonalInches = 6.8f,
            aspectRatio = 1080f / 2400f,
            is16by9 = false,
            is16by10 = false,
            isUltraWide = false,
            is4KOrHigher = false,
            isHdrSupported = true,
            supportedHdrTypes = listOf(1),
            isWideColorGamut = true
        )

        val secondaryTV = MonitorMetrics(
            displayId = 1,
            name = "External 4K Monitor",
            isDefault = false,
            isPresentation = true,
            isValid = true,
            state = 2,
            physicalWidth = 3840,
            physicalHeight = 2160,
            currentRefreshRate = 60.0f,
            maxRefreshRate = 60.0f,
            supportedModes = listOf(DisplayModeInfo(1, 3840, 2160, 60.0f)),
            widthInches = 24.0f,
            heightInches = 13.5f,
            diagonalInches = 27.5f,
            aspectRatio = 16f / 9f,
            is16by9 = true,
            is16by10 = false,
            isUltraWide = false,
            is4KOrHigher = true,
            isHdrSupported = true,
            supportedHdrTypes = listOf(1),
            isWideColorGamut = true
        )

        val state = ExternalDisplayState(
            isConnected = true,
            isHdmiHardwarePlugged = true,
            isHdmiAudioRouted = true,
            externalDisplayCount = 1,
            primaryDisplayId = 0,
            secondaryDisplayId = 1,
            activeMonitor = secondaryTV,
            displays = listOf(primaryPhone, secondaryTV)
        )

        assertTrue(state.isConnected)
        assertTrue(state.isHdmiHardwarePlugged)
        assertTrue(state.isHdmiAudioRouted)
        assertEquals(1, state.externalDisplayCount)
        assertEquals(1, state.secondaryDisplayId)
        assertNotNull(state.activeMonitor)
        assertEquals(3840, state.activeMonitor?.physicalWidth)
        assertEquals(2160, state.activeMonitor?.physicalHeight)
        assertTrue(state.activeMonitor?.is4KOrHigher == true)
    }

    @Test
    fun `test listener add and remove lifecycle`() {
        var receivedState: ExternalDisplayState? = null
        val listener: (ExternalDisplayState) -> Unit = { state ->
            receivedState = state
        }

        DisplayTopologyManager.addListener(listener)
        assertNotNull(receivedState)

        DisplayTopologyManager.removeListener(listener)
        DisplayTopologyManager.destroy()
    }

    @Test
    fun `test extractHdmiPluggedValue polymorphic conversion`() {
        // Integer (AOSP standard)
        assertTrue(DisplayTopologyManager.extractHdmiPluggedValue(1))
        assertFalse(DisplayTopologyManager.extractHdmiPluggedValue(0))

        // Boolean (MediaTek / Infinix / OEM custom extra)
        assertTrue(DisplayTopologyManager.extractHdmiPluggedValue(true))
        assertFalse(DisplayTopologyManager.extractHdmiPluggedValue(false))

        // String representations
        assertTrue(DisplayTopologyManager.extractHdmiPluggedValue("1"))
        assertTrue(DisplayTopologyManager.extractHdmiPluggedValue("true"))
        assertTrue(DisplayTopologyManager.extractHdmiPluggedValue("TRUE"))
        assertFalse(DisplayTopologyManager.extractHdmiPluggedValue("0"))
        assertFalse(DisplayTopologyManager.extractHdmiPluggedValue("false"))

        // Null and unexpected types
        assertFalse(DisplayTopologyManager.extractHdmiPluggedValue(null))
        assertFalse(DisplayTopologyManager.extractHdmiPluggedValue(listOf(1)))
    }
}
