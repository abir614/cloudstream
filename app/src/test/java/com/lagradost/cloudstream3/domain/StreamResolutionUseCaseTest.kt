package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.domain.usecase.StreamResolutionUseCase
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamResolutionUseCaseTest {

    private val useCase = StreamResolutionUseCase()

    @Suppress("DEPRECATION")
    private fun createLink(
        name: String,
        url: String,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO
    ): ExtractorLink {
        return ExtractorLink(
            source = "TestProvider",
            name = name,
            url = url,
            referer = "https://example.com",
            quality = quality,
            headers = emptyMap(),
            extractorData = null,
            type = type
        )
    }

    @Test
    fun `test filterSafeStreams eliminates loopback and private ip streams`() {
        val safe = createLink("Safe Link", "https://media.cdn.com/stream.mp4", Qualities.P1080.value)
        val loopback = createLink("Loopback", "http://127.0.0.1:8080/secret", Qualities.P1080.value)
        val metadata = createLink("Metadata", "http://169.254.169.254/latest", Qualities.P1080.value)

        val filtered = useCase.filterSafeStreams(listOf(safe, loopback, metadata))
        assertEquals(1, filtered.size)
        assertEquals("Safe Link", filtered.first().name)
    }

    @Test
    fun `test prioritizeStreams sorts closest to preferred quality`() {
        val link360 = createLink("360p", "https://cdn.com/360.mp4", Qualities.P360.value)
        val link720 = createLink("720p", "https://cdn.com/720.mp4", Qualities.P720.value)
        val link1080 = createLink("1080p", "https://cdn.com/1080.mp4", Qualities.P1080.value)
        val link4k = createLink("4K", "https://cdn.com/4k.mp4", Qualities.P2160.value)

        // Preferred 1080p
        val prioritized = useCase.prioritizeStreams(
            listOf(link360, link4k, link720, link1080),
            preferredQuality = Qualities.P1080.value
        )

        assertEquals(4, prioritized.size)
        assertEquals("1080p", prioritized[0].name)
    }

    @Test
    fun `test prioritizeStreams prefers adaptive M3U8 when qualities match`() {
        val mp4Link = createLink("MP4 1080p", "https://cdn.com/1080.mp4", Qualities.P1080.value, ExtractorLinkType.VIDEO)
        val hlsLink = createLink("HLS 1080p", "https://cdn.com/1080.m3u8", Qualities.P1080.value, ExtractorLinkType.M3U8)

        val prioritized = useCase.prioritizeStreams(
            listOf(mp4Link, hlsLink),
            preferredQuality = Qualities.P1080.value,
            preferHls = true
        )

        assertEquals("HLS 1080p", prioritized.first().name)
    }

    @Test
    fun `test selectBestMirror returns single best link`() {
        val link720 = createLink("720p", "https://cdn.com/720.mp4", Qualities.P720.value)
        val link1080 = createLink("1080p", "https://cdn.com/1080.mp4", Qualities.P1080.value)

        val best = useCase.selectBestMirror(listOf(link720, link1080), Qualities.P1080.value)
        assertNotNull(best)
        assertEquals("1080p", best?.name)
    }

    @Test
    fun `test resolveFailoverCandidate selects next best link excluding failed url`() {
        val mirror1 = createLink("Primary 1080p", "https://primary.cdn.com/video.mp4", Qualities.P1080.value)
        val mirror2 = createLink("Secondary 1080p", "https://backup.cdn.com/video.mp4", Qualities.P1080.value)
        val mirror3 = createLink("Low Res 480p", "https://low.cdn.com/video.mp4", Qualities.P480.value)

        val failover = useCase.resolveFailoverCandidate(
            listOf(mirror1, mirror2, mirror3),
            failedUrl = "https://primary.cdn.com/video.mp4",
            preferredQuality = Qualities.P1080.value
        )

        assertNotNull(failover)
        assertEquals("Secondary 1080p", failover?.name)
        assertEquals("https://backup.cdn.com/video.mp4", failover?.url)
    }
}
