package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodePreferencesAutoSelectTest {

    private fun createDisplayLink(
        source: String,
        name: String,
        quality: Int = Qualities.P1080.value
    ): DisplayLink {
        @Suppress("DEPRECATION")
        val extractor = ExtractorLink(
            source = source,
            name = name,
            url = "https://example.com/$source/$quality",
            referer = "https://example.com",
            quality = quality,
            type = ExtractorLinkType.VIDEO
        )
        return DisplayLink(extractor to null, priority = quality, shouldUseLink = true)
    }

    private fun findPreferredLink(
        candidateLinks: List<DisplayLink>,
        preferredSource: String?,
        preferredName: String?,
        preferredQuality: Int?
    ): DisplayLink? {
        val remSource = preferredSource ?: return null
        val remName = preferredName
        val remQuality = preferredQuality

        // 1. Exact source AND name match (highest fidelity)
        if (!remName.isNullOrBlank()) {
            val match = candidateLinks.find {
                it.link.first?.source == remSource && it.link.first?.name.equals(remName, ignoreCase = true)
            }
            if (match != null) return match
        }

        // 2. Exact source AND quality match
        if (remQuality != null && remQuality > 0) {
            val match = candidateLinks.find {
                it.link.first?.source == remSource && it.link.first?.quality == remQuality
            }
            if (match != null) return match
        }

        // 3. Same source/extractor match
        return candidateLinks.find { it.link.first?.source == remSource }
    }

    @Test
    fun `test exact source and name match takes highest priority`() {
        val candidates = listOf(
            createDisplayLink("VidCloud", "VidCloud 720p", Qualities.P720.value),
            createDisplayLink("StreamTape", "StreamTape 1080p", Qualities.P1080.value),
            createDisplayLink("StreamTape", "StreamTape 720p", Qualities.P720.value),
            createDisplayLink("Mp4Upload", "Mp4Upload 1080p", Qualities.P1080.value)
        )

        val preferred = findPreferredLink(candidates, "StreamTape", "StreamTape 1080p", Qualities.P1080.value)
        assertNotNull(preferred)
        assertEquals("StreamTape", preferred?.link?.first?.source)
        assertEquals("StreamTape 1080p", preferred?.link?.first?.name)
    }

    @Test
    fun `test fallback to source and quality when name has slight variance`() {
        val candidates = listOf(
            createDisplayLink("VidCloud", "VidCloud 1080p", Qualities.P1080.value),
            createDisplayLink("StreamTape", "StreamTape Server 2 - 1080p", Qualities.P1080.value),
            createDisplayLink("StreamTape", "StreamTape 720p", Qualities.P720.value)
        )

        // Previous episode was named "StreamTape 1080p", current episode is "StreamTape Server 2 - 1080p"
        val preferred = findPreferredLink(candidates, "StreamTape", "StreamTape 1080p", Qualities.P1080.value)
        assertNotNull(preferred)
        assertEquals("StreamTape", preferred?.link?.first?.source)
        assertEquals(Qualities.P1080.value, preferred?.link?.first?.quality)
    }

    @Test
    fun `test fallback to source when quality differs`() {
        val candidates = listOf(
            createDisplayLink("VidCloud", "VidCloud 1080p", Qualities.P1080.value),
            createDisplayLink("StreamTape", "StreamTape 720p", Qualities.P720.value)
        )

        // Previous episode had 1080p, current episode only has 720p for StreamTape
        val preferred = findPreferredLink(candidates, "StreamTape", "StreamTape 1080p", Qualities.P1080.value)
        assertNotNull(preferred)
        assertEquals("StreamTape", preferred?.link?.first?.source)
        assertEquals(Qualities.P720.value, preferred?.link?.first?.quality)
    }

    @Test
    fun `test returns null when preferred source is unavailable`() {
        val candidates = listOf(
            createDisplayLink("VidCloud", "VidCloud 1080p", Qualities.P1080.value),
            createDisplayLink("Mp4Upload", "Mp4Upload 1080p", Qualities.P1080.value)
        )

        val preferred = findPreferredLink(candidates, "DoodStream", "DoodStream 1080p", Qualities.P1080.value)
        assertNull(preferred)
    }
}
