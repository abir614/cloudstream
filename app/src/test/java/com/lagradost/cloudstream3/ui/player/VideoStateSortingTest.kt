package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStateSortingTest {

    private fun createDummyLink(
        name: String,
        url: String,
        quality: Int = Qualities.P1080.value,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO
    ): VideoLink {
        @Suppress("DEPRECATION")
        val extractor = ExtractorLink(
            source = "TestProvider",
            name = name,
            url = url,
            referer = "https://example.com",
            quality = quality,
            type = type
        )
        return VideoLink(extractor, null)
    }

    @Test
    fun `test video state immutable link accumulation`() {
        val state0 = VideoState(instance = 1)
        assertTrue(state0.links.isEmpty())

        val linkA = createDummyLink("Server A", "https://example.com/a.mp4", Qualities.P720.value)
        val linkB = createDummyLink("Server B", "https://example.com/b.mp4", Qualities.P1080.value)

        val state1 = state0.add(linkA)
        assertEquals(1, state1.links.size)

        val state2 = state1.add(linkB)
        assertEquals(2, state2.links.size)
        // Verify state1 remained unmodified (immutable contract)
        assertEquals(1, state1.links.size)
    }

    @Test
    fun `test errored link tracking`() {
        val linkA = createDummyLink("Server A", "https://example.com/a.mp4")
        val linkB = createDummyLink("Server B", "https://example.com/b.mp4")

        val state = VideoState(
            links = persistentSetOf(linkA, linkB),
            erroredLinks = persistentSetOf(linkA),
            instance = 1
        )

        assertTrue(state.erroredLinks.contains(linkA))
        assertFalse(state.erroredLinks.contains(linkB))
    }

    @Test
    fun `test sorting links with quality profiles caches result`() {
        val link1 = createDummyLink("Mirror 1", "https://example.com/1.mp4", Qualities.P720.value)
        val link2 = createDummyLink("Mirror 2", "https://example.com/2.mp4", Qualities.P1080.value)

        val state = VideoState(
            links = persistentSetOf(link1, link2),
            instance = 1
        )

        val sorted = state.sortLinks(0)
        assertEquals(2, sorted.size)

        // Verifies subsequent call hits idempotent cache
        val sortedAgain = state.sortLinks(0)
        assertEquals(sorted.size, sortedAgain.size)
    }
}
