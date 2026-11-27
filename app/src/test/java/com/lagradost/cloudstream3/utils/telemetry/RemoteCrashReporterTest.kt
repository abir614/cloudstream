package com.lagradost.cloudstream3.utils.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `test sanitizeTelemetryText preserves benign words, slashes, and dots`() {
        val raw = "Cache entry cache_key=posters/123/banner.jpg.webp and item_key=anime/episodes/1.0.json, foreign_key=user_data.id/settings.pref"
        val sanitized = RemoteCrashReporter.sanitizeTelemetryText(raw)

        assertTrue(sanitized.contains("cache_key=posters/123/banner.jpg.webp"))
        assertTrue(sanitized.contains("item_key=anime/episodes/1.0.json"))
        assertTrue(sanitized.contains("foreign_key=user_data.id/settings.pref"))
    }

    @Test
    fun `test sanitizeTelemetryText preserves version numbers and local diagnostic IPs`() {
        val raw = "Loaded library v1.2.3.4 and connecting to 127.0.0.1:8080 or bind 0.0.0.0"
        val sanitized = RemoteCrashReporter.sanitizeTelemetryText(raw)

        assertTrue(sanitized.contains("v1.2.3.4"))
        assertTrue(sanitized.contains("127.0.0.1:8080"))
        assertTrue(sanitized.contains("0.0.0.0"))
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

    @Test
    fun `test buildPayload produces structured FATAL crash payload with forensic traces`() {
        val testException = RuntimeException("Fatal memory allocation failure in player")
        val fullForensicDump = "CLOUDSTREAM ENTERPRISE CRASH TOMBSTONE\nFatal memory allocation failure"
        val payload = RemoteCrashReporter.buildPayload(
            context = null,
            level = "FATAL",
            tag = "FatalCrash",
            message = testException.message ?: "Uncaught fatal crash",
            thread = Thread.currentThread(),
            error = testException,
            fullLog = fullForensicDump
        )

        assertEquals("FATAL", payload.getString("level"))
        assertEquals("FatalCrash", payload.getString("tag"))
        assertTrue(payload.getString("exception").contains("RuntimeException: Fatal memory allocation failure in player"))
        assertTrue(payload.getString("stackTrace").contains("Fatal memory allocation failure in player"))
        assertEquals(fullForensicDump, payload.getString("fullLog"))
    }

    @Test
    fun `test app-level telemetry filters external scraper and CDN noise without dropping real failures`() {
        assertTrue(
            RemoteCrashReporter.shouldSuppressNonFatalDiagnostic(
                level = "ERROR",
                tag = "safeAmap",
                message = "org.json.JSONException: Value <!DOCTYPE html> of type java.lang.String cannot be converted to JSONArray"
            )
        )
        assertTrue(
            RemoteCrashReporter.shouldSuppressNonFatalDiagnostic(
                level = "WARN",
                tag = "runLimitedAsync",
                message = "Failed request: Expected URL scheme 'http' or 'https' but no scheme was found"
            )
        )
        assertTrue(
            RemoteCrashReporter.shouldSuppressNonFatalDiagnostic(
                level = "ERROR",
                tag = "CS3ExoPlayer",
                message = "Playback HTTP error: androidx.media3.datasource.HttpDataSource\$InvalidResponseCodeException: Response code: 403"
            )
        )
        assertTrue(
            RemoteCrashReporter.shouldSuppressNonFatalDiagnostic(
                level = "WARN",
                tag = "BillingClient",
                message = "Billing service unavailable on device."
            )
        )

        // Critical failures must NEVER be suppressed
        assertFalse(
            RemoteCrashReporter.shouldSuppressNonFatalDiagnostic(
                level = "FATAL",
                tag = "runLimitedAsync",
                message = "Value <!DOCTYPE crash"
            )
        )
        assertFalse(
            RemoteCrashReporter.shouldSuppressNonFatalDiagnostic(
                level = "ERROR",
                tag = "safeAmap",
                message = "Failed to load provider class",
                throwable = NoClassDefFoundError("com.lagradost.cloudstream3.MissingClass")
            )
        )
        assertFalse(
            RemoteCrashReporter.shouldSuppressNonFatalDiagnostic(
                level = "ERROR",
                tag = "CS3ExoPlayer",
                message = "MediaCodecVideoRenderer error: Decoder init failed: c2.qti.hevc.decoder"
            )
        )
        assertFalse(
            RemoteCrashReporter.shouldSuppressNonFatalDiagnostic(
                level = "ERROR",
                tag = "PluginManager",
                message = "Failed downloading plugin from https://example.com/plugin.cs3"
            )
        )

        // Exact short tag matching must not swallow first-party tags containing "ion"
        assertTrue(AutoLogcatMonitor.isNoiseTag("ion"))
        assertTrue(AutoLogcatMonitor.isNoiseTag("QT"))
        assertTrue(AutoLogcatMonitor.isNoiseTag("LB"))
        assertFalse(AutoLogcatMonitor.isNoiseTag("ExtensionManager"))
        assertFalse(AutoLogcatMonitor.isNoiseTag("ConnectionPool"))
        assertFalse(AutoLogcatMonitor.isNoiseTag("RepositoryManager"))
        assertFalse(AutoLogcatMonitor.isNoiseTag("SandboxClient"))
    }
}
