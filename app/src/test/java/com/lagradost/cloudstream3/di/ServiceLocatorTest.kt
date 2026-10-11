package com.lagradost.cloudstream3.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServiceLocatorTest {

    interface TestService {
        fun greet(): String
    }

    class TestServiceImpl(private val name: String) : TestService {
        override fun greet(): String = "Hello $name"
    }

    @Before
    fun setup() {
        ServiceLocator.resetForTesting()
    }

    @Test
    fun `test register and resolve singleton`() {
        val service = TestServiceImpl("CloudStream")
        ServiceLocator.register<TestService>(service)

        val resolved = ServiceLocator.resolve<TestService>()
        assertNotNull(resolved)
        assertEquals("Hello CloudStream", resolved.greet())
    }

    @Test
    fun `test register factory creates new instances`() {
        var count = 0
        ServiceLocator.registerFactory<TestService> {
            count++
            TestServiceImpl("Instance $count")
        }

        val first = ServiceLocator.resolve<TestService>()
        assertEquals("Hello Instance 1", first.greet())

        val second = ServiceLocator.resolve<TestService>()
        assertEquals("Hello Instance 2", second.greet())
    }

    @Test
    fun `test resolveOrNull returns null when unregistered`() {
        val resolved = ServiceLocator.resolveOrNull<TestService>()
        assertNull(resolved)
    }

    @Test
    fun `test all core architectural services are registered by default`() {
        assertNotNull(ServiceLocator.dataRepository)
        assertNotNull(ServiceLocator.streamSecurityUseCase)
        assertNotNull(ServiceLocator.appUpdateUseCase)
        assertNotNull(ServiceLocator.trackSelectionUseCase)
        assertNotNull(ServiceLocator.resumePlaybackUseCase)
        assertNotNull(ServiceLocator.streamResolutionUseCase)
        assertNotNull(ServiceLocator.telemetry)
        assertNotNull(ServiceLocator.streamFailoverEngine)
        assertNotNull(ServiceLocator.subtitleSyncEngine)
        assertNotNull(ServiceLocator.downloadQueueEngine)
        assertNotNull(ServiceLocator.pluginArchiveIntegrityEngine)
        assertNotNull(ServiceLocator.deepLinkCoordinator)
        assertNotNull(ServiceLocator.tvLayoutCoordinator)
        assertNotNull(ServiceLocator.appUpdateCoordinator)
    }

    @Test
    fun `test resetForTesting clears custom registrations and restores defaults`() {
        ServiceLocator.register<TestService>(TestServiceImpl("Test"))
        ServiceLocator.resetForTesting()

        val resolved = ServiceLocator.resolveOrNull<TestService>()
        assertNull(resolved)

        // Core architectural dependencies should be safely re-registered
        assertNotNull(ServiceLocator.dataRepository)
        assertNotNull(ServiceLocator.trackSelectionUseCase)
        assertNotNull(ServiceLocator.resumePlaybackUseCase)
        assertNotNull(ServiceLocator.streamResolutionUseCase)
        assertNotNull(ServiceLocator.streamFailoverEngine)
        assertNotNull(ServiceLocator.subtitleSyncEngine)
        assertNotNull(ServiceLocator.downloadQueueEngine)
        assertNotNull(ServiceLocator.pluginArchiveIntegrityEngine)
        assertNotNull(ServiceLocator.deepLinkCoordinator)
        assertNotNull(ServiceLocator.tvLayoutCoordinator)
        assertNotNull(ServiceLocator.appUpdateCoordinator)
    }
}
