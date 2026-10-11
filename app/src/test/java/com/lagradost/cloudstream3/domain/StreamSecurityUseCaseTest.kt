package com.lagradost.cloudstream3.domain
 
import com.lagradost.cloudstream3.domain.usecase.StreamSecurityUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSecurityUseCaseTest {

    private val useCase = StreamSecurityUseCase()

    @Test
    fun `test sanitizeUrl returns identical string when native core not loaded`() {
        val rawUrl = "https://example.com/video.m3u8?utm_source=tracker"
        val result = useCase.sanitizeUrl(rawUrl)
        // If native core is not loaded in unit test JVM, it gracefully returns unchanged url
        assertEquals(rawUrl, result)
    }

    @Test
    fun `test isAllowedScheme permits valid media stream schemes`() {
        assertTrue(useCase.isAllowedScheme("https://cdn.example.com/stream.m3u8"))
        assertTrue(useCase.isAllowedScheme("http://cdn.example.com/video.mp4"))
        assertTrue(useCase.isAllowedScheme("magnet:?xt=urn:btih:abc123"))
        assertTrue(useCase.isAllowedScheme("rtmp://live.stream.org/app/feed"))
    }

    @Test
    fun `test isAllowedScheme blocks unsafe and unexpected URI schemes`() {
        assertFalse(useCase.isAllowedScheme("file:///etc/passwd"))
        assertFalse(useCase.isAllowedScheme("content://com.android.providers.telephony/messages"))
        assertFalse(useCase.isAllowedScheme("ftp://private.internal/secret.bin"))
        assertFalse(useCase.isAllowedScheme("javascript:alert(1)"))
        assertFalse(useCase.isAllowedScheme("data:text/html,<html>"))
        assertFalse(useCase.isAllowedScheme(""))
        assertFalse(useCase.isAllowedScheme("   "))
    }

    @Test
    fun `test isSafeFallback detects SSRF loopback and metadata violations`() {
        assertFalse(useCase.isSafeFallback("http://127.0.0.1:8080/manifest.mpd"))
        assertFalse(useCase.isSafeFallback("http://localhost:3000/stream.m3u8"))
        assertFalse(useCase.isSafeFallback("http://169.254.169.254/latest/meta-data/"))
        assertFalse(useCase.isSafeFallback("http://0.0.0.0:8000/test"))

        // Public URLs are safe under fallback
        assertTrue(useCase.isSafeFallback("https://edge.mediaserver.com/live/stream.m3u8"))
        assertTrue(useCase.isSafeFallback("http://public-iptv.org/playlist.m3u8"))
    }
}
