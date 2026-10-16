package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine
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
            MovieSearchResponse("Summer Bikini Vacation OVA", "https://anime.tv/summer-bikini", "AnimePahe", TvType.Anime),
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
}
