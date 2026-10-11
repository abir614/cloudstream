package com.lagradost.cloudstream3.utils.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TelemetryServiceTest {

    private lateinit var telemetry: TelemetryService

    @Before
    fun setUp() {
        telemetry = TelemetryService(maxBreadcrumbs = 5, isDebug = true)
    }

    @Test
    fun `test breadcrumb recording within capacity`() {
        telemetry.recordBreadcrumb("UI", "User clicked home")
        telemetry.recordBreadcrumb("Network", "Fetching catalog")

        val snapshot = telemetry.getBreadcrumbsSnapshot()
        assertEquals(2, snapshot.size)
        assertEquals("User clicked home", snapshot[0].message)
        assertEquals("Fetching catalog", snapshot[1].message)
    }

    @Test
    fun `test ring buffer bounded capacity evicts oldest entries`() {
        for (i in 1..10) {
            telemetry.recordBreadcrumb("Step", "Action $i")
        }

        val snapshot = telemetry.getBreadcrumbsSnapshot()
        assertEquals(5, snapshot.size)
        // Should contain actions 6 through 10
        assertEquals("Action 6", snapshot.first().message)
        assertEquals("Action 10", snapshot.last().message)
    }

    @Test
    fun `test privacy sanitization redacts auth tokens and api keys`() {
        val raw = "GET /api/v1?token=secret12345&apiKey=my_super_secret_key&normal=hello"
        val sanitized = telemetry.sanitize(raw)

        assertTrue(sanitized.contains("token=[REDACTED]"))
        assertTrue(sanitized.contains("apiKey=[REDACTED]"))
        assertTrue(sanitized.contains("normal=hello"))
        assertFalse(sanitized.contains("secret12345"))
        assertFalse(sanitized.contains("my_super_secret_key"))
    }

    @Test
    fun `test privacy sanitization masks private and loopback IPs`() {
        val raw = "Connecting to 127.0.0.1:8080 and 192.168.1.100 and public 8.8.8.8"
        val sanitized = telemetry.sanitize(raw)

        assertTrue(sanitized.contains("[PRIVATE_IP]:8080"))
        assertTrue(sanitized.contains("[PRIVATE_IP]"))
        assertTrue(sanitized.contains("8.8.8.8"))
        assertFalse(sanitized.contains("127.0.0.1"))
        assertFalse(sanitized.contains("192.168.1.100"))
    }

    @Test
    fun `test crash report capture format and breadcrumbs inclusion`() {
        telemetry.recordBreadcrumb("Player", "Initialized player engine")
        telemetry.recordBreadcrumb("Stream", "Resolving 1080p mirror")

        val dummyException = IllegalStateException("Simulated decoder failure")
        val dump = telemetry.captureCrash(dummyException)

        assertTrue(dump.contains("CLOUDSTREAM ENTERPRISE CRASH FORENSIC DUMP"))
        assertTrue(dump.contains("IllegalStateException: Simulated decoder failure"))
        assertTrue(dump.contains("Initialized player engine"))
        assertTrue(dump.contains("Resolving 1080p mirror"))
        assertTrue(dump.contains("STACK TRACE"))
    }

    @Test
    fun `test disabled telemetry rejects new events`() {
        telemetry.setEnabled(false)
        telemetry.recordBreadcrumb("Test", "Should not be recorded")

        assertTrue(telemetry.getBreadcrumbsSnapshot().isEmpty())
    }
}
