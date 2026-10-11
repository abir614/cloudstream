package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.domain.usecase.TrackSelectionUseCase
import com.lagradost.cloudstream3.ui.player.AudioTrack
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSelectionUseCaseTest {

    private val useCase = TrackSelectionUseCase()

    private fun createSubtitle(
        name: String,
        langCode: String?,
        origin: SubtitleOrigin = SubtitleOrigin.URL,
        suffix: String = "1"
    ): SubtitleData {
        return SubtitleData(
            originalName = name,
            nameSuffix = suffix,
            url = "https://example.com/subs/$name.vtt",
            origin = origin,
            mimeType = "text/vtt",
            headers = emptyMap(),
            languageCode = langCode
        )
    }

    private fun createAudioTrack(
        id: String,
        label: String,
        language: String,
        channels: Int = 2
    ): AudioTrack {
        return AudioTrack(
            id = id,
            label = label,
            language = language,
            sampleMimeType = "audio/mp4a-latm",
            channelCount = channels,
            formatIndex = 0
        )
    }

    @Test
    fun `test select best subtitle by exact language preference`() {
        val subs = listOf(
            createSubtitle("Spanish", "es"),
            createSubtitle("French", "fr"),
            createSubtitle("English", "en")
        )

        val selected = useCase.selectBestSubtitle(subs, listOf("en"))
        assertNotNull(selected)
        assertEquals("English", selected?.originalName)
    }

    @Test
    fun `test select best subtitle respects preference ordering`() {
        val subs = listOf(
            createSubtitle("German", "de"),
            createSubtitle("Spanish", "es"),
            createSubtitle("English", "en")
        )

        // User prefers Spanish first, then English
        val selected = useCase.selectBestSubtitle(subs, listOf("es", "en"))
        assertNotNull(selected)
        assertEquals("Spanish", selected?.originalName)
    }

    @Test
    fun `test select best subtitle prioritizes local downloaded file over URL and embedded`() {
        val embedded = createSubtitle("English", "en", SubtitleOrigin.EMBEDDED_IN_VIDEO)
        val remoteUrl = createSubtitle("English", "en", SubtitleOrigin.URL)
        val downloaded = createSubtitle("English", "en", SubtitleOrigin.DOWNLOADED_FILE)

        val selected = useCase.selectBestSubtitle(listOf(embedded, remoteUrl, downloaded), listOf("en"))
        assertNotNull(selected)
        assertEquals(SubtitleOrigin.DOWNLOADED_FILE, selected?.origin)
    }

    @Test
    fun `test select best subtitle falls back to english when permitted`() {
        val subs = listOf(
            createSubtitle("German", "de"),
            createSubtitle("English", "en")
        )

        // User requested Japanese, which is absent
        val selected = useCase.selectBestSubtitle(
            subs,
            preferredLanguages = listOf("ja"),
            allowEnglishFallback = true
        )
        assertNotNull(selected)
        assertEquals("English", selected?.originalName)
    }

    @Test
    fun `test select best subtitle returns null when no matching language and fallback disabled`() {
        val subs = listOf(
            createSubtitle("German", "de"),
            createSubtitle("French", "fr")
        )

        val selected = useCase.selectBestSubtitle(
            subs,
            preferredLanguages = listOf("ja"),
            allowEnglishFallback = false
        )
        assertNull(selected)
    }

    @Test
    fun `test filter subtitles by text search query`() {
        val subs = listOf(
            createSubtitle("English [Full]", "en"),
            createSubtitle("English [SDH]", "en"),
            createSubtitle("Spanish", "es")
        )

        val filtered = useCase.filterSubtitles(subs, "sdh")
        assertEquals(1, filtered.size)
        assertTrue(filtered.first().name.contains("SDH"))
    }

    @Test
    fun `test select best audio track matches language and prefers higher channel count`() {
        val stereoEnglish = createAudioTrack("1", "English Stereo", "en", channels = 2)
        val surroundEnglish = createAudioTrack("2", "English 5.1", "en", channels = 6)
        val frenchSurround = createAudioTrack("3", "French 5.1", "fr", channels = 6)

        val selected = useCase.selectBestAudioTrack(
            listOf(stereoEnglish, surroundEnglish, frenchSurround),
            preferredLanguages = listOf("en")
        )

        assertNotNull(selected)
        assertEquals("2", selected?.id)
        assertEquals(6, selected?.channelCount)
    }
}
