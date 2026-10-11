package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.ui.coordinators.AppUpdateCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdateCoordinatorTest {

    private lateinit var coordinator: AppUpdateCoordinator

    @Before
    fun setup() {
        coordinator = AppUpdateCoordinator()
    }

    @Test
    fun `test evaluateUpdate returns UpToDate when installed version is equal or higher`() {
        val stateEqual = coordinator.evaluateUpdate(
            currentInstalledVersion = 1050L,
            remoteVersion = 1050L,
            downloadUrl = "https://example.com/update.apk"
        )
        assertEquals(AppUpdateCoordinator.UpdateState.UpToDate, stateEqual)

        val stateHigher = coordinator.evaluateUpdate(
            currentInstalledVersion = 1060L,
            remoteVersion = 1050L,
            downloadUrl = "https://example.com/update.apk"
        )
        assertEquals(AppUpdateCoordinator.UpdateState.UpToDate, stateHigher)
    }

    @Test
    fun `test evaluateUpdate returns UpdateAvailable when remote version is higher`() {
        val state = coordinator.evaluateUpdate(
            currentInstalledVersion = 1000L,
            remoteVersion = 1050L,
            downloadUrl = "https://example.com/v1050.apk"
        )
        assertTrue(state is AppUpdateCoordinator.UpdateState.UpdateAvailable)
        val update = state as AppUpdateCoordinator.UpdateState.UpdateAvailable
        assertEquals(1000L, update.currentVersion)
        assertEquals(1050L, update.newVersion)
        assertEquals("https://example.com/v1050.apk", update.updateUrl)
    }
}
