package com.lagradost.cloudstream3.domain.engine

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.services.NativeCoreBridge

/**
 * Intelligent NSFW and Hentai Content Filtering Engine.
 *
 * Provides hybrid dual-engine classification (high-performance Rust core native execution
 * with zero-allocation JVM fallback) to filter adult content by default across Feed, Search,
 * and Extension discovery.
 *
 * Specifically handles disguised adult content such as Hentai categorized by providers
 * under generic TvType.Anime or TvType.OVA.
 */
object NsfwFilterEngine {

    private val EXPLICIT_ADULT_TOKENS = hashSetOf(
        "xxx", "porn", "porno", "pornography", "nsfw", "jav", "r18", "r-18", "r18+", "erotica", "erotic",
        "hentai", "hanime", "ero-anime", "eroanime", "eroge", "ahegao", "doujin", "doujinshi", "futanari",
        "netorare", "ntr", "chikan", "paizuri", "nakadashi", "shibari", "bukkake", "creampie", "blowjob",
        "gangbang", "deepthroat", "swinger", "cuckold", "masturbation", "incest", "uncensored", "decensored",
        "rance", "kuroinu", "taimanin", "mankitsu", "overflow", "euphoria", "futabu", "shoujoramune",
        "otomedori", "resortboin", "bakunyuu", "kyonyuu", "oppai", "nukige", "eromanga", "erogame",
        "onichichi", "seikatsushuukan", "smut",
        "bunnywalker", "pinkpineapple", "queenbee", "poros", "greenbunny", "whitebear", "naturalhigh",
        "nhentai", "tsumino", "fakku", "luscious",
        "pornhub", "xvideos", "xnxx", "spankbang", "eporner", "missav", "supjav", "javhd", "rule34"
    )

    private val EXPLICIT_ADULT_PHRASES = arrayOf(
        "boku no pico", "bible black", "oni chichi", "onichichi", "night shift nurses",
        "la blue girl", "seikatsu shuukan", "resort boin", "otome dori", "shoujo ramune",
        "gakuen de jikan", "kanojo x kanojo", "drop out", "pink pineapple", "queen bee",
        "green bunny", "white bear", "natural high", "ms pictures", "mary jane",
        "tsun tsun maid", "tamashii insert", "himekishi", "joshi luck", "urotsukidoji",
        "cream lemon", "adventure kid", "kyonyuu fantasy", "futanari bitch", "dokidoki little",
        "marshmallow imouto", "meshimase", "chikan joushaden", "velvet kiss", "aki sora",
        "ane wa yanmama", "uncensored ova", "uncensored anime", "h-anime", "h anime",
        "r-18 anime", "r18 anime", "adult anime", "adult animation", "hentai anime", "hentai ova"
    )

    private val ADULT_DOMAINS = arrayOf(
        "hanime.tv", "hentaihaven", "nhentai.net", "fakku.net", "tsumino.com", "luscious.net",
        "pornhub.com", "xvideos.com", "xnxx.com", "spankbang.com", "eporner.com", "missav.com",
        "supjav.com", "javhd.com", "rule34.xxx", "rule34video.com", "erome.com", "h-anime.net",
        "javmost", "javfree", "hentaicity", "hentaipulse", "hentaimama", "muchohentai",
        "animehentai", "hentai2read", "simply-hentai", "asmhentai"
    )

    private val ADULT_URL_SLUG_TOKENS = hashSetOf(
        "hentai", "hanime", "jav", "xxx", "porn", "r18", "nsfw",
        "doujin", "futanari", "uncensored", "netorare", "ntr",
        "rance", "kuroinu", "taimanin", "mankitsu", "overflow", "euphoria"
    )

    private val ADULT_TAG_TOKENS = hashSetOf(
        "hentai", "rx", "erotica", "erotic", "adult", "r18", "nsfw", "smut"
    )

    private fun extractTokens(text: String): List<String> {
        return text.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotEmpty() }
    }

    /**
     * Checks if a URL or image link belongs to a known adult domain or contains adult slug markers.
     */
    fun isNsfwUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase()
        for (domain in ADULT_DOMAINS) {
            if (lowerUrl.contains(domain)) return true
        }
        for (token in extractTokens(lowerUrl)) {
            if (ADULT_URL_SLUG_TOKENS.contains(token)) return true
        }
        return false
    }

    /**
     * Internal classification logic executing native Rust core when available,
     * falling back to optimized JVM token matching.
     */
    fun isNsfwText(title: String, url: String, tags: String? = null): Boolean {
        // 1. Try Native Rust Core acceleration first
        NativeCoreBridge.isNsfw(title, url, tags)?.let { return it }

        // 2. JVM Fallback
        val lowerTitle = title.lowercase()
        val lowerUrl = url.lowercase()

        for (domain in ADULT_DOMAINS) {
            if (lowerUrl.contains(domain)) return true
        }

        if (lowerTitle.contains("r-18") || lowerTitle.contains("r18+") || lowerTitle.contains("18+")) {
            return true
        }
        if (lowerUrl.contains("r-18") || lowerUrl.contains("r18+")) {
            return true
        }

        for (phrase in EXPLICIT_ADULT_PHRASES) {
            if (lowerTitle.contains(phrase) || lowerUrl.contains(phrase)) return true
        }

        val titleTokens = extractTokens(lowerTitle)
        for (token in titleTokens) {
            if (EXPLICIT_ADULT_TOKENS.contains(token)) return true
        }

        val urlTokens = extractTokens(lowerUrl)
        for (token in urlTokens) {
            if (ADULT_URL_SLUG_TOKENS.contains(token)) return true
        }

        if (tags != null) {
            val lowerTags = tags.lowercase()
            if (lowerTags.contains("18+") || lowerTags.contains("r-18") || lowerTags.contains("r18+")) {
                return true
            }
            for (phrase in EXPLICIT_ADULT_PHRASES) {
                if (lowerTags.contains(phrase)) return true
            }
            val tagTokens = extractTokens(lowerTags)
            for (token in tagTokens) {
                if (ADULT_TAG_TOKENS.contains(token) || EXPLICIT_ADULT_TOKENS.contains(token)) return true
                if (token == "ecchi" && lowerTitle.contains("18+")) return true
            }
        }

        return false
    }

    /**
     * Checks if an image buffer exhibits excessive exposed skin tone (> 48%)
     * using the ultra-fast native Rust chrominance analyzer.
     */
    fun isSkinExposureExcessive(rgbBytes: ByteArray, bytesPerPixel: Int = 3): Boolean {
        return NativeCoreBridge.isSkinExposureExcessive(rgbBytes, bytesPerPixel) ?: false
    }

    /**
     * Checks if a SearchResponse item is adult/NSFW or hentai.
     */
    fun isNsfw(item: SearchResponse): Boolean {
        if (item.type == TvType.NSFW) return true

        // Check if provider itself is inherently an NSFW provider
        APIHolder.getApiFromNameNull(item.apiName)?.let { provider ->
            if (isNsfwProvider(provider)) return true
        }

        val otherName = (item as? com.lagradost.cloudstream3.AnimeSearchResponse)?.otherName
        val combinedTitle = if (!otherName.isNullOrBlank()) "${item.name} $otherName" else item.name
        val poster = item.posterUrl ?: ""

        if (poster.isNotBlank() && isNsfwUrl(poster)) return true

        return isNsfwText(combinedTitle, item.url, poster.ifBlank { null })
    }

    /**
     * Checks if a LoadResponse item is adult/NSFW or hentai.
     */
    fun isNsfw(item: LoadResponse): Boolean {
        if (item.type == TvType.NSFW) return true

        APIHolder.getApiFromNameNull(item.apiName)?.let { provider ->
            if (isNsfwProvider(provider)) return true
        }

        val tagsStr = item.tags?.joinToString(", ")
        val rating = item.contentRating
        if (!rating.isNullOrBlank()) {
            val lowerRating = rating.lowercase().trim()
            if (lowerRating == "18+" || lowerRating == "r18" || lowerRating == "rx" || lowerRating == "nc-17" || lowerRating == "adult") {
                return true
            }
        }

        val poster = item.posterUrl ?: ""
        if (poster.isNotBlank() && isNsfwUrl(poster)) return true

        val plotStr = item.plot
        val metadataCombined = listOfNotNull(tagsStr, plotStr).joinToString(" | ")

        return isNsfwText(item.name, item.url, metadataCombined.ifBlank { null })
    }

    /**
     * Checks if a provider API is inherently an adult/NSFW provider.
     */
    fun isNsfwProvider(api: MainAPI): Boolean {
        if (api.supportedTypes.contains(TvType.NSFW) || api.supportedTypes == setOf(TvType.NSFW)) {
            return true
        }
        return isNsfwText(api.name, api.mainUrl, null)
    }

    fun isNsfwProvider(repo: APIRepository): Boolean {
        return isNsfwProvider(repo.api)
    }

    /**
     * Checks if a home feed row is an adult/NSFW row (e.g. "Hentai Trending" or majority adult).
     */
    fun isNsfwFeedRow(name: String, items: List<SearchResponse>): Boolean {
        val rowTokens = extractTokens(name)
        for (token in rowTokens) {
            if (token == "hentai" || token == "nsfw" || token == "jav" || token == "erotica" || token == "r18" || token == "adult") {
                return true
            }
        }
        if (items.isNotEmpty()) {
            val nsfwCount = items.count { isNsfw(it) }
            if (nsfwCount * 2 > items.size) {
                return true
            }
        }
        return false
    }

    /**
     * Filters a list of SearchResponse items based on whether NSFW content is enabled.
     */
    fun filterSearchResults(data: List<SearchResponse>, isNsfwEnabled: Boolean): List<SearchResponse> {
        if (isNsfwEnabled) return data
        return data.filterNot { isNsfw(it) }
    }

    /**
     * Filters a home page row, removing adult items or eliminating the row entirely if empty or adult.
     */
    fun filterHomePageList(data: HomePageList, isNsfwEnabled: Boolean): HomePageList? {
        if (isNsfwEnabled) return data
        if (isNsfwFeedRow(data.name, data.list)) return null

        val filteredItems = data.list.filterNot { isNsfw(it) }
        if (filteredItems.isEmpty()) return null

        return data.copy(list = filteredItems)
    }

    /**
     * Filters provider APIs, excluding adult providers when NSFW is disabled.
     */
    fun filterProviders(apis: List<MainAPI>, isNsfwEnabled: Boolean): List<MainAPI> {
        if (isNsfwEnabled) return apis
        return apis.filterNot { isNsfwProvider(it) }
    }
}
