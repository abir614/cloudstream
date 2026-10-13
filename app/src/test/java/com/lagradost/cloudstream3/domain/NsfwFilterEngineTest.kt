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

    @Test
    fun `test isNsfwText detects hentai disguised or blended into anime`() {
        // Disguised titles or releases marked as uncensored / hentai / adult
        assertTrue(NsfwFilterEngine.isNsfwText("Overflow (Uncensored)", "https://anime.tv/watch/overflow"))
        assertTrue(NsfwFilterEngine.isNsfwText("Mankitsu Happening", "https://anime.tv/watch/mankitsu-happening", "Hentai, OVA"))
        assertTrue(NsfwFilterEngine.isNsfwText("Ane wa Yanmama Junyuuchuu Episode 1", "https://anime.tv/watch/ane-wa-yanmama-hentai"))
        assertTrue(NsfwFilterEngine.isNsfwText("Rance 01: Hikari o Motomete The Animation", "https://anime.tv/watch/rance", "Action, Fantasy, Hentai"))
        assertTrue(NsfwFilterEngine.isNsfwText("Some Anime Title", "https://hanime.tv/videos/hentai/video-1"))
        assertTrue(NsfwFilterEngine.isNsfwText("Eroge H-Game OVA", "https://anime.tv/watch/eroge-ova"))
        assertTrue(NsfwFilterEngine.isNsfwText("Netorare NTR Special", "https://anime.tv/watch/ntr-special"))
        assertTrue(NsfwFilterEngine.isNsfwText("Futanari Bitch OVA", "https://anime.tv/watch/futanari-bitch"))
        assertTrue(NsfwFilterEngine.isNsfwText("Ahegao Maid", "https://anime.tv/watch/ahegao-maid"))
        assertTrue(NsfwFilterEngine.isNsfwText("Uncensored Doujin Anime", "https://anime.tv/watch/doujin"))
        // Untagged notorious hentai franchise stems
        assertTrue(NsfwFilterEngine.isNsfwText("Boku no Pico", "https://anime.tv/watch/boku-no-pico"))
        assertTrue(NsfwFilterEngine.isNsfwText("Bible Black: La Noche de Walpurgis", "https://anime.tv/watch/bible-black"))
        assertTrue(NsfwFilterEngine.isNsfwText("Kuroinu: Kedakaki Seishi ni Somaru Yami", "https://anime.tv/watch/kuroinu"))
        assertTrue(NsfwFilterEngine.isNsfwText("Taimanin Asagi Episode 1", "https://anime.tv/watch/taimanin-asagi"))
        assertTrue(NsfwFilterEngine.isNsfwText("Oni Chichi: Re-born", "https://anime.tv/watch/oni-chichi"))
        assertTrue(NsfwFilterEngine.isNsfwText("Seikatsu Shuukan OVA", "https://anime.tv/watch/seikatsu-shuukan"))
        assertTrue(NsfwFilterEngine.isNsfwText("Euphoria HD", "https://anime.tv/watch/euphoria"))
        assertTrue(NsfwFilterEngine.isNsfwText("Resort Boin", "https://anime.tv/watch/resort-boin"))
        assertTrue(NsfwFilterEngine.isNsfwText("Shoujo Ramune", "https://anime.tv/watch/shoujo-ramune"))
        assertTrue(NsfwFilterEngine.isNsfwText("Otome Dori", "https://anime.tv/watch/otome-dori"))
        assertTrue(NsfwFilterEngine.isNsfwText("Night Shift Nurses", "https://anime.tv/watch/night-shift-nurses"))
    }

    @Test
    fun `test isNsfwUrl detects adult domains and slugs`() {
        assertTrue(NsfwFilterEngine.isNsfwUrl("https://hanime.tv/poster/123.jpg"))
        assertTrue(NsfwFilterEngine.isNsfwUrl("https://cdn.anime.com/covers/hentai/pic.png"))
        assertTrue(NsfwFilterEngine.isNsfwUrl("https://images.site.org/r18/image.webp"))
        assertFalse(NsfwFilterEngine.isNsfwUrl("https://cdn.anime.com/covers/action/attack-on-titan.jpg"))
    }

    @Test
    fun `test isNsfwText preserves mainstream anime without false positives`() {
        assertFalse(NsfwFilterEngine.isNsfwText("Attack on Titan", "https://anime.tv/watch/attack-on-titan", "Action, Shounen"))
        assertFalse(NsfwFilterEngine.isNsfwText("Hunter x Hunter (2011)", "https://anime.tv/watch/hunter-x-hunter-2011", "Adventure, Shounen"))
        assertFalse(NsfwFilterEngine.isNsfwText("Assassination Classroom", "https://anime.tv/watch/assassination-classroom", "Action, Comedy"))
        assertFalse(NsfwFilterEngine.isNsfwText("Haikyuu!! Second Season", "https://anime.tv/watch/haikyuu", "Sports"))
        assertFalse(NsfwFilterEngine.isNsfwText("Bleach: Thousand-Year Blood War", "https://anime.tv/watch/bleach-tybw", "Action, Supernatural"))
        assertFalse(NsfwFilterEngine.isNsfwText("Classroom of the Elite", "https://anime.tv/watch/classroom-of-the-elite", "Drama, Mystery"))
        assertFalse(NsfwFilterEngine.isNsfwText("Spy x Family", "https://anime.tv/watch/spy-x-family", "Comedy, Action"))
        assertFalse(NsfwFilterEngine.isNsfwText("One Piece", "https://anime.tv/watch/one-piece", "Adventure, Comedy"))
        assertFalse(NsfwFilterEngine.isNsfwText("Naruto: Shippuden", "https://anime.tv/watch/naruto-shippuden", "Action"))
        assertFalse(NsfwFilterEngine.isNsfwText("Demon Slayer: Kimetsu no Yaiba", "https://anime.tv/watch/demon-slayer", "Supernatural"))
    }

    @Test
    fun `test isNsfwText detects general adult, JAV and explicit ratings`() {
        assertTrue(NsfwFilterEngine.isNsfwText("JAV Uncensored Collection", "https://stream.com/watch/jav-123"))
        assertTrue(NsfwFilterEngine.isNsfwText("Hot Erotica Night", "https://stream.com/movie/hot-erotica"))
        assertTrue(NsfwFilterEngine.isNsfwText("Random Title", "https://spankbang.com/watch/video"))
        assertTrue(NsfwFilterEngine.isNsfwText("Adult Film", "https://stream.com/watch/title", "Drama, 18+, Adult"))
        assertTrue(NsfwFilterEngine.isNsfwText("Special Series (R-18)", "https://stream.com/watch/title"))
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `test filterSearchResults removes adult and blended hentai items when disabled`() {
        val mixedResults: List<SearchResponse> = listOf(
            AnimeSearchResponse("Attack on Titan", "https://anime.tv/aot", "AnimeProvider", TvType.Anime),
            AnimeSearchResponse("Overflow (Uncensored)", "https://anime.tv/overflow", "AnimeProvider", TvType.Anime),
            MovieSearchResponse("Inception", "https://movies.tv/inception", "MovieProvider", TvType.Movie),
            AnimeSearchResponse("Mankitsu Happening", "https://anime.tv/mankitsu-hentai", "AnimeProvider", TvType.OVA),
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
        assertEquals(6, allAllowed.size)
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `test filterHomePageList eliminates adult rows and sanitizes mixed rows`() {
        // Adult-only row
        val adultRow = HomePageList(
            name = "Hentai Trending",
            list = listOf(
                AnimeSearchResponse("Hentai 1", "https://anime.tv/h1", "AnimeProvider", TvType.Anime),
                AnimeSearchResponse("Hentai 2", "https://anime.tv/h2", "AnimeProvider", TvType.Anime)
            )
        )
        assertNull(NsfwFilterEngine.filterHomePageList(adultRow, isNsfwEnabled = false))
        assertNotNull(NsfwFilterEngine.filterHomePageList(adultRow, isNsfwEnabled = true))

        // Mixed row
        val mixedRow = HomePageList(
            name = "Popular Anime",
            list = listOf(
                AnimeSearchResponse("Attack on Titan", "https://anime.tv/aot", "AnimeProvider", TvType.Anime),
                AnimeSearchResponse("Overflow (Uncensored)", "https://anime.tv/overflow", "AnimeProvider", TvType.Anime),
                AnimeSearchResponse("Hunter x Hunter (2011)", "https://anime.tv/hxh", "AnimeProvider", TvType.Anime)
            )
        )

        val filteredMixed = NsfwFilterEngine.filterHomePageList(mixedRow, isNsfwEnabled = false)
        assertNotNull(filteredMixed)
        assertEquals(2, filteredMixed!!.list.size)
        assertEquals("Attack on Titan", filteredMixed.list[0].name)
        assertEquals("Hunter x Hunter (2011)", filteredMixed.list[1].name)
    }
}
