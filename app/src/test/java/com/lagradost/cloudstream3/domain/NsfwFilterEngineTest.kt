package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine
import com.lagradost.cloudstream3.domain.engine.NsfwFilterLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwFilterEngineTest {

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `test filterSearchResults removes adult items when disabled`() {
        val mixedResults: List<SearchResponse> = listOf(
            AnimeSearchResponse("Attack on Titan", "https://anime.tv/aot", "AnimeProvider", TvType.Anime),
            MovieSearchResponse("Inception", "https://movies.tv/inception", "MovieProvider", TvType.Movie),
            MovieSearchResponse("Adult JAV Vol 1", "https://stream.tv/jav-1", "JavProvider", TvType.NSFW),
            AnimeSearchResponse("Spy x Family", "https://anime.tv/spy-family", "AnimeProvider", TvType.Anime)
        )

        val filtered = NsfwFilterEngine.filterSearchResults(mixedResults, isNsfwEnabled = false)
        assertEquals(3, filtered.size)
        assertEquals("Attack on Titan", filtered[0].name)
        assertEquals("Inception", filtered[1].name)
        assertEquals("Spy x Family", filtered[2].name)

        // When user explicitly enables NSFW, all items should be retained
        val allAllowed = NsfwFilterEngine.filterSearchResults(mixedResults, isNsfwEnabled = true)
        assertEquals(4, allAllowed.size)
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `test filterHomePageList eliminates adult rows and sanitizes mixed rows`() {
        // Adult-only row
        val adultRow = HomePageList(
            name = "Adult Trending",
            list = listOf(
                MovieSearchResponse("Adult 1", "https://stream.tv/a1", "JavProvider", TvType.NSFW),
                MovieSearchResponse("Adult 2", "https://stream.tv/a2", "JavProvider", TvType.NSFW)
            )
        )
        assertNull(NsfwFilterEngine.filterHomePageList(adultRow, isNsfwEnabled = false))
        assertNotNull(NsfwFilterEngine.filterHomePageList(adultRow, isNsfwEnabled = true))

        // Mixed row
        val mixedRow = HomePageList(
            name = "Popular Anime",
            list = listOf(
                AnimeSearchResponse("Attack on Titan", "https://anime.tv/aot", "AnimeProvider", TvType.Anime),
                MovieSearchResponse("Adult JAV Vol 1", "https://stream.tv/jav-1", "JavProvider", TvType.NSFW),
                AnimeSearchResponse("Hunter x Hunter (2011)", "https://anime.tv/hxh", "AnimeProvider", TvType.Anime)
            )
        )

        val filteredMixed = NsfwFilterEngine.filterHomePageList(mixedRow, isNsfwEnabled = false)
        assertNotNull(filteredMixed)
        assertEquals(2, filteredMixed!!.list.size)
        assertEquals("Attack on Titan", filteredMixed.list[0].name)
        assertEquals("Hunter x Hunter (2011)", filteredMixed.list[1].name)
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `test filterSearchResults catches ecchi and adult titles on mainstream providers`() {
        val results: List<SearchResponse> = listOf(
            AnimeSearchResponse("High School DxD", "https://anime.tv/hsdxd", "AnimePahe", TvType.Anime),
            AnimeSearchResponse("Demon Slayer", "https://anime.tv/ds", "AnimePahe", TvType.Anime),
            MovieSearchResponse("Yosuga no Sora OVA", "https://anime.tv/yns", "AnimePahe", TvType.Anime),
            AnimeSearchResponse("Frieren: Beyond Journey's End", "https://anime.tv/frieren", "AnimePahe", TvType.Anime)
        )

        val filtered = NsfwFilterEngine.filterSearchResults(results, isNsfwEnabled = false)
        assertEquals(2, filtered.size)
        assertEquals("Demon Slayer", filtered[0].name)
        assertEquals("Frieren: Beyond Journey's End", filtered[1].name)
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `test filterHomePageList eliminates row with adult or ecchi category name`() {
        val ecchiRow = HomePageList(
            name = "Top Ecchi Anime",
            list = listOf(
                AnimeSearchResponse("Show 1", "https://anime.tv/s1", "AnimePahe", TvType.Anime),
                AnimeSearchResponse("Show 2", "https://anime.tv/s2", "AnimePahe", TvType.Anime)
            )
        )
        assertNull(NsfwFilterEngine.filterHomePageList(ecchiRow, isNsfwEnabled = false))
        assertNotNull(NsfwFilterEngine.filterHomePageList(ecchiRow, isNsfwEnabled = true))
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `test sensitivity levels distinguish hardcore vs ecchi vs extreme`() {
        val hardcoreItem = MovieSearchResponse("Explicit Adult Video", "https://site.to/xxx", "PornProvider", TvType.Movie)
        assertTrue(NsfwFilterEngine.isNsfw(hardcoreItem, NsfwFilterLevel.LOW))
        assertTrue(NsfwFilterEngine.isNsfw(hardcoreItem, NsfwFilterLevel.MODERATE))
        assertTrue(NsfwFilterEngine.isNsfw(hardcoreItem, NsfwFilterLevel.EXTREME))

        // Ecchi is caught in LOW, MODERATE, and EXTREME (Low hardened to Moderate)
        val suggestiveItem = AnimeSearchResponse("High School DxD", "https://site.to/dxd", "AnimeProvider", TvType.Anime)
        assertTrue(NsfwFilterEngine.isNsfw(suggestiveItem, NsfwFilterLevel.LOW))
        assertTrue(NsfwFilterEngine.isNsfw(suggestiveItem, NsfwFilterLevel.MODERATE))
        assertTrue(NsfwFilterEngine.isNsfw(suggestiveItem, NsfwFilterLevel.EXTREME))

        // Summer / beach / swimsuit is allowed on LOW, and strictly filtered in MODERATE and EXTREME (Moderate hardened to Extreme)
        val beachItem = AnimeSearchResponse("Beach Swimsuit Episode", "https://site.to/swim", "AnimeProvider", TvType.Anime, posterUrl = "https://cdn.net/swim.jpg")
        assertFalse(NsfwFilterEngine.isNsfw(beachItem, NsfwFilterLevel.LOW))
        assertTrue(NsfwFilterEngine.isNsfw(beachItem, NsfwFilterLevel.MODERATE))
        assertTrue(NsfwFilterEngine.isNsfw(beachItem, NsfwFilterLevel.EXTREME))

        // Empty poster items must be strictly filtered out in Extreme filter
        val emptyPosterItem = MovieSearchResponse("Show Without Poster", "https://site.to/noposter", "Provider", TvType.Movie, posterUrl = null)
        assertFalse(NsfwFilterEngine.isNsfw(emptyPosterItem, NsfwFilterLevel.MODERATE))
        assertTrue(NsfwFilterEngine.isNsfw(emptyPosterItem, NsfwFilterLevel.EXTREME))

        val nonHttpPosterItem = MovieSearchResponse("Show With Bad Poster", "https://site.to/badposter", "Provider", TvType.Movie, posterUrl = "file:///local/bad.png")
        assertFalse(NsfwFilterEngine.isNsfw(nonHttpPosterItem, NsfwFilterLevel.MODERATE))
        assertTrue(NsfwFilterEngine.isNsfw(nonHttpPosterItem, NsfwFilterLevel.EXTREME))

        val borderlineItem = AnimeSearchResponse("Ordinary Show Special", "https://site.to/s1", "AnimeProvider", TvType.Anime, posterUrl = "https://cdn.net/harem_bath.jpg")
        assertTrue(NsfwFilterEngine.isNsfw(borderlineItem, NsfwFilterLevel.MODERATE))
        assertTrue(NsfwFilterEngine.isNsfw(borderlineItem, NsfwFilterLevel.EXTREME))

        // Super Extreme romance/ecchi tropes caught in EXTREME
        val superExtremeItem = AnimeSearchResponse("Domestic Girlfriend Special", "https://site.to/dg", "AnimeProvider", TvType.Anime, posterUrl = "https://cdn.net/p.jpg")
        assertFalse(NsfwFilterEngine.isNsfw(superExtremeItem, NsfwFilterLevel.LOW))
        assertFalse(NsfwFilterEngine.isNsfw(superExtremeItem, NsfwFilterLevel.MODERATE))
        assertTrue(NsfwFilterEngine.isNsfw(superExtremeItem, NsfwFilterLevel.EXTREME))

        // Compound word detection
        val compoundHentai = AnimeSearchResponse("HentaiVideoSpecial", "https://site.to/v1", "Provider", TvType.Anime, posterUrl = "https://cdn.net/p1.jpg")
        assertTrue(NsfwFilterEngine.isNsfw(compoundHentai, NsfwFilterLevel.LOW))

        val compoundEcchi = AnimeSearchResponse("EcchiGirlOVA", "https://site.to/v2", "Provider", TvType.Anime, posterUrl = "https://cdn.net/p2.jpg")
        assertTrue(NsfwFilterEngine.isNsfw(compoundEcchi, NsfwFilterLevel.LOW))
        assertTrue(NsfwFilterEngine.isNsfw(compoundEcchi, NsfwFilterLevel.MODERATE))

        val compoundBikini = AnimeSearchResponse("SummerBikiniParty", "https://site.to/v3", "Provider", TvType.Anime, posterUrl = "https://cdn.net/p3.jpg")
        assertFalse(NsfwFilterEngine.isNsfw(compoundBikini, NsfwFilterLevel.LOW))
        assertTrue(NsfwFilterEngine.isNsfw(compoundBikini, NsfwFilterLevel.MODERATE))
        assertTrue(NsfwFilterEngine.isNsfw(compoundBikini, NsfwFilterLevel.EXTREME))

        // Clean mainstream show not filtered at any level
        val cleanItem = AnimeSearchResponse("Attack on Titan", "https://site.to/aot", "AnimeProvider", TvType.Anime, posterUrl = "https://cdn.net/aot.jpg")
        assertFalse(NsfwFilterEngine.isNsfw(cleanItem, NsfwFilterLevel.LOW))
        assertFalse(NsfwFilterEngine.isNsfw(cleanItem, NsfwFilterLevel.MODERATE))
        assertFalse(NsfwFilterEngine.isNsfw(cleanItem, NsfwFilterLevel.EXTREME))
    }
}
