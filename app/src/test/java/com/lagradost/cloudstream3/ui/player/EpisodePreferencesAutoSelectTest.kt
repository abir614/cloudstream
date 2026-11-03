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

    private fun isDubLink(link: DisplayLink): Boolean {
        val ext = link.link.first ?: return false
        val dubRegex = Regex("""\b(dub|dubbed|dual[\s_-]*audio)\b""", RegexOption.IGNORE_CASE)
        return dubRegex.containsMatchIn(ext.name) ||
                dubRegex.containsMatchIn(ext.source) ||
                dubRegex.containsMatchIn(ext.url) ||
                dubRegex.containsMatchIn(link.name)
    }

    private fun findLinkMatchingLanguage(candidateLinks: List<DisplayLink>, targetLang: String): DisplayLink? {
        if (targetLang.isBlank() || targetLang == "none") return null

        val namesToCheck = mutableListOf(targetLang)
        if (targetLang.equals("en", ignoreCase = true)) {
            namesToCheck.addAll(listOf("eng", "english", "dub", "dubbed"))
        } else if (targetLang.equals("es", ignoreCase = true)) {
            namesToCheck.addAll(listOf("spa", "spanish", "español", "latino"))
        } else if (targetLang.equals("ja", ignoreCase = true)) {
            namesToCheck.addAll(listOf("jpn", "japanese", "sub", "raw"))
        } else if (targetLang.equals("fr", ignoreCase = true)) {
            namesToCheck.addAll(listOf("fra", "french", "français", "vf"))
        }

        return candidateLinks.find { candidate ->
            val ext = candidate.link.first ?: return@find false
            namesToCheck.any { name ->
                ext.name.contains(name, ignoreCase = true) ||
                ext.source.contains(name, ignoreCase = true) ||
                ext.url.contains(name, ignoreCase = true) ||
                candidate.name.contains(name, ignoreCase = true)
            }
        }
    }

    private fun findPreferredLink(
        candidateLinks: List<DisplayLink>,
        preferredSource: String?,
        preferredName: String?,
        preferredQuality: Int?,
        primaryAudioLang: String? = null,
        secondaryAudioLang: String? = null
    ): DisplayLink? {
        val remSource = preferredSource
        val remName = preferredName
        val remQuality = preferredQuality

        // 1. Explicit user selection in previous episode / series takes HIGHEST PRIORITY
        if (!remSource.isNullOrBlank()) {
            if (!remName.isNullOrBlank()) {
                val match = candidateLinks.find {
                    it.link.first?.source == remSource && it.link.first?.name.equals(remName, ignoreCase = true)
                }
                if (match != null) return match
            }

            if (remQuality != null && remQuality > 0) {
                val match = candidateLinks.find {
                    it.link.first?.source == remSource && it.link.first?.quality == remQuality
                }
                if (match != null) return match
            }

            val sourceMatch = candidateLinks.find { it.link.first?.source == remSource }
            if (sourceMatch != null) return sourceMatch
        }

        // 2. Default source selection based on Player Language Settings
        if (!primaryAudioLang.isNullOrBlank() && primaryAudioLang != "none") {
            val primaryMatch = findLinkMatchingLanguage(candidateLinks, primaryAudioLang)
            if (primaryMatch != null) return primaryMatch
        }

        // 3. Fallback to "dub"
        val dubMatch = candidateLinks.find { isDubLink(it) }
        if (dubMatch != null) return dubMatch

        // 4. Fallback to Secondary Audio Language
        if (!secondaryAudioLang.isNullOrBlank() && secondaryAudioLang != "none") {
            val secondaryMatch = findLinkMatchingLanguage(candidateLinks, secondaryAudioLang)
            if (secondaryMatch != null) return secondaryMatch
        }

        return null
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
    fun `test primary audio language auto-selects matching source by default`() {
        val candidates = listOf(
            createDisplayLink("Server1", "Japanese Original Audio 1080p", Qualities.P1080.value),
            createDisplayLink("Server2", "Spanish Audio Latino 1080p", Qualities.P1080.value),
            createDisplayLink("Server3", "French VF 1080p", Qualities.P1080.value)
        )

        // No previous explicit user selection, but user has Spanish as primary audio language
        val preferred = findPreferredLink(
            candidates,
            preferredSource = null,
            preferredName = null,
            preferredQuality = null,
            primaryAudioLang = "es"
        )
        assertNotNull(preferred)
        assertEquals("Server2", preferred?.link?.first?.source)
        assertEquals("Spanish Audio Latino 1080p", preferred?.link?.first?.name)
    }

    @Test
    fun `test fallback to dub when primary language is not explicitly in source name`() {
        val candidates = listOf(
            createDisplayLink("Gogoanime", "Gogoanime (Sub) 1080p", Qualities.P1080.value),
            createDisplayLink("Gogoanime", "Gogoanime (Dub) 1080p", Qualities.P1080.value),
            createDisplayLink("Zoro", "Zoro Raw 1080p", Qualities.P1080.value)
        )

        // User prefers English audio ("en"), server has (Sub) and (Dub)
        val preferred = findPreferredLink(
            candidates,
            preferredSource = null,
            preferredName = null,
            preferredQuality = null,
            primaryAudioLang = "en"
        )
        assertNotNull(preferred)
        assertEquals("Gogoanime (Dub) 1080p", preferred?.link?.first?.name)
    }

    @Test
    fun `test fallback to secondary audio language when primary is absent`() {
        val candidates = listOf(
            createDisplayLink("Server1", "Japanese Raw 1080p", Qualities.P1080.value),
            createDisplayLink("Server2", "French VF 1080p", Qualities.P1080.value)
        )

        // User primary is Spanish (absent), secondary is French ("fr")
        val preferred = findPreferredLink(
            candidates,
            preferredSource = null,
            preferredName = null,
            preferredQuality = null,
            primaryAudioLang = "es",
            secondaryAudioLang = "fr"
        )
        assertNotNull(preferred)
        assertEquals("French VF 1080p", preferred?.link?.first?.name)
    }

    @Test
    fun `test explicit episode source selection overrides default language setting`() {
        val candidates = listOf(
            createDisplayLink("EnglishServer", "English 1080p", Qualities.P1080.value),
            createDisplayLink("SpecialServer", "Special Dub 1080p", Qualities.P1080.value)
        )

        // User has English as primary language, but explicitly picked "SpecialServer" in previous episode
        val preferred = findPreferredLink(
            candidates,
            preferredSource = "SpecialServer",
            preferredName = "Special Dub 1080p",
            preferredQuality = Qualities.P1080.value,
            primaryAudioLang = "en"
        )
        assertNotNull(preferred)
        assertEquals("SpecialServer", preferred?.link?.first?.source)
    }

    @Test
    fun `test returns null when preferred source is unavailable and no language matches`() {
        val candidates = listOf(
            createDisplayLink("VidCloud", "VidCloud 1080p", Qualities.P1080.value),
            createDisplayLink("Mp4Upload", "Mp4Upload 1080p", Qualities.P1080.value)
        )

        val preferred = findPreferredLink(
            candidates,
            preferredSource = "DoodStream",
            preferredName = "DoodStream 1080p",
            preferredQuality = Qualities.P1080.value,
            primaryAudioLang = "hi"
        )
        assertNull(preferred)
    }
}
