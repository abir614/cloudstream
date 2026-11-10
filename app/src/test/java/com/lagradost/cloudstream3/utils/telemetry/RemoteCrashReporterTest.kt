package com.lagradost.cloudstream3.utils.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCrashReporterTest {

    @Test
    fun `test sanitizeTelemetryText redacts sensitive auth tokens and passwords`() {
        val raw = "Error during request: token=secretToken123&password=superSecretPassword&bearer=headerSecret"
        val sanitized = RemoteCrashReporter.sanitizeTelemetryText(raw)

        assertFalse(sanitized.contains("secretToken123"))
        assertFalse(sanitized.contains("superSecretPassword"))
        assertFalse(sanitized.contains("headerSecret"))
        assertTrue(sanitized.contains("token=[REDACTED]"))
        assertTrue(sanitized.contains("password=[REDACTED]"))
        assertTrue(sanitized.contains("bearer=[REDACTED]"))
    }

    @Test
    fun `test sanitizeTelemetryText redacts Telegram bot tokens`() {
        val dummyToken = "123456789:" + "AAEn7ekGfE8KGCJalPKR4FBJEYTXyITCHUE"
        val raw = "Failed to dispatch alert to bot$dummyToken on edge"
        val sanitized = RemoteCrashReporter.sanitizeTelemetryText(raw)

        assertFalse(sanitized.contains(dummyToken))
        assertTrue(sanitized.contains("[REDACTED_BOT_TOKEN]"))
    }

    @Test
    fun `test sanitizeTelemetryText redacts IPv4 addresses`() {
        val raw = "Socket connection timed out connecting to 198.51.100.42:443"
        val sanitized = RemoteCrashReporter.sanitizeTelemetryText(raw)

        assertFalse(sanitized.contains("198.51.100.42"))
        assertTrue(sanitized.contains("[REDACTED_IP]:443"))
    }

    @Test
    fun `test sanitizeTelemetryText redacts email addresses`() {
        val raw = "User reported issue via support-user@cloudstream.org in feedback"
        val sanitized = RemoteCrashReporter.sanitizeTelemetryText(raw)

        assertFalse(sanitized.contains("support-user@cloudstream.org"))
        assertTrue(sanitized.contains("[REDACTED_EMAIL]"))
    }

    @Test
    fun `test buildPayload produces structured fields matching worker ingestion contract`() {
        val testException = NullPointerException("Simulated null pointer in video parser")
        val payload = RemoteCrashReporter.buildPayload(
            context = null,
            level = "ERROR",
            tag = "VideoParser",
            message = "Parser failed",
            thread = Thread.currentThread(),
            error = testException,
            fullLog = null,
            extraData = mapOf("provider" to "SuperStream", "quality" to "1080p")
        )

        assertEquals("ERROR", payload.getString("level"))
        assertEquals("VideoParser", payload.getString("tag"))
        assertEquals("Parser failed", payload.getString("message"))
        assertTrue(payload.has("device"))
        assertTrue(payload.has("soc"))
        assertTrue(payload.has("ram"))
        assertTrue(payload.has("tier"))
        assertTrue(payload.has("os"))
        assertTrue(payload.has("kernel"))
        assertTrue(payload.has("version"))
        assertTrue(payload.has("plugin"))
        assertTrue(payload.has("thread"))
        assertTrue(payload.getString("exception").contains("NullPointerException"))
        assertTrue(payload.getString("stackTrace").contains("Simulated null pointer in video parser"))

        val extraData = payload.getJSONObject("extraData")
        assertEquals("SuperStream", extraData.getString("provider"))
        assertEquals("1080p", extraData.getString("quality"))
    }

    @Test
    fun `test buildBatchPayload formats consolidated events for edge worker batch endpoint`() {
        val events = listOf(
            RemoteCrashReporter.DiagnosticEvent(
                level = "WARN",
                tag = "Network",
                message = "HTTP 429 rate limit fallback",
                throwable = null,
                data = emptyMap()
            ),
            RemoteCrashReporter.DiagnosticEvent(
                level = "ERROR",
                tag = "Playback",
                message = "MediaCodec decoder error",
                throwable = IllegalStateException("Codec format unsupported"),
                data = mapOf("codec" to "video/avc")
            )
        )

        val batchPayload = RemoteCrashReporter.buildBatchPayload(
            context = null,
            events = events
        )

        assertEquals("BATCH", batchPayload.getString("type"))
        assertEquals(2, batchPayload.getInt("count"))
        assertTrue(batchPayload.has("device"))
        assertTrue(batchPayload.has("soc"))
        assertTrue(batchPayload.has("ram"))
        assertTrue(batchPayload.has("tier"))
        assertTrue(batchPayload.has("os"))
        assertTrue(batchPayload.has("kernel"))
        assertTrue(batchPayload.has("version"))

        val eventsArray = batchPayload.getJSONArray("events")
        assertEquals(2, eventsArray.length())

        val event0 = eventsArray.getJSONObject(0)
        assertEquals("WARN", event0.getString("level"))
        assertEquals("Network", event0.getString("tag"))
        assertEquals("HTTP 429 rate limit fallback", event0.getString("message"))

        val event1 = eventsArray.getJSONObject(1)
        assertEquals("ERROR", event1.getString("level"))
        assertEquals("Playback", event1.getString("tag"))
        assertEquals("MediaCodec decoder error", event1.getString("message"))
        assertTrue(event1.getString("exception").contains("IllegalStateException"))
        assertEquals("video/avc", event1.getJSONObject("data").getString("codec"))
    }

    @Test
    fun `test public telemetry endpoints point to production edge worker`() {
        assertEquals("https://cloudstream.app-log-collector.workers.dev", RemoteCrashReporter.BASE_ENDPOINT)
        assertEquals("https://cloudstream.app-log-collector.workers.dev/handshake", RemoteCrashReporter.HANDSHAKE_ENDPOINT)
        assertEquals("https://cloudstream.app-log-collector.workers.dev/report", RemoteCrashReporter.REPORT_ENDPOINT)
    }
}
