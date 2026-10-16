package com.lagradost.cloudstream3.domain.engine

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.APIRepository

/**
 * Configurable Content Safety Sensitivity Levels:
 * - EXTREME (0.12f): Maximum sensitivity strict family shield.
 * - MODERATE (0.26f): Default balanced family level.
 * - LOW (0.55f): Hardcore adult content only.
 */
enum class NsfwFilterLevel(
    val key: String,
    val visualThreshold: Float
) {
    LOW("low", 0.55f),
    MODERATE("moderate", 0.26f),
    EXTREME("extreme", 0.12f);

    companion object {
        fun fromString(value: String?): NsfwFilterLevel {
            return entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: MODERATE
        }
    }
}

/**
 * Robust Multi-Vector NSFW Classification Engine.
 *
 * Integrates:
 * 1. On-device lightweight quantized neural network AI model ([OnDeviceNsfwClassifier]) for visual posters.
 * 2. Multi-tier pure Kotlin explicit adult, ecchi, and beach/summer keyword guardian (zero JNI overhead).
 */
object NsfwFilterEngine {

    private val HARDCORE_KEYWORDS = hashSetOf(
        "xxx", "porn", "porno", "pornography", "nsfw", "jav", "r18", "r-18", "r18+", "+18", "18+", "18plus",
        "erotica", "erotic", "hentai", "hanime", "chikan", "paizuri", "nakadashi", "shibari", "bukkake",
        "creampie", "blowjob", "gangbang", "deepthroat", "swinger", "cuckold", "masturbation", "incest",
        "uncensored", "decensored", "nude", "nudity", "naked", "topless", "fella", "cunnilingus", "pegging",
        "rance", "kuroinu", "taimanin", "mankitsu", "overflow", "euphoria", "futabu", "shoujoramune",
        "otomedori", "resortboin", "onichichi", "seikatsushuukan", "bunnywalker", "pinkpineapple", "queenbee",
        "poros", "greenbunny", "whitebear", "naturalhigh", "nhentai", "tsumino", "fakku", "pornhub", "xvideos",
        "xnxx", "spankbang", "eporner", "missav", "supjav", "javhd", "rule34"
    )

    private val SUGGESTIVE_KEYWORDS = hashSetOf(
        "ecchi", "ero-anime", "eroanime", "eroge", "ero", "ahegao", "doujin", "doujinshi", "futanari",
        "netorare", "ntr", "smut", "lewd", "lust", "sensual", "sex", "sexy", "yuri", "yaoi", "playboy",
        "softcore", "hardcore", "bikini", "swimsuit", "swimwear", "beachwear", "monokini", "gravure",
        "mizugi", "lingerie", "cleavage", "oppai", "boob", "boobs", "breast", "breasts", "busty", "milf",
        "thong", "panties", "panty", "fetish", "bdsm", "bondage", "sadism", "masochism", "dxd", "shinmai",
        "valkyrie", "interspecies", "chijo"
    )

    private val EXTREME_KEYWORDS = hashSetOf(
        "bathing", "bath", "hotspring", "hot-spring", "onsen", "harem", "seductive", "provocative",
        "fanservice", "fan-service", "waifu", "suggestive", "pantyshot", "microkini", "spank", "spanking",
        "bathhouse"
    )

    private val HARDCORE_PHRASES = arrayOf(
        "boku no pico", "bible black", "oni chichi", "night shift nurses", "la blue girl",
        "seikatsu shuukan", "resort boin", "otome dori", "shoujo ramune"
    )

    private val SUGGESTIVE_PHRASES = arrayOf(
        "high school dxd", "shinmai maou", "yosuga no sora", "aki sora", "redo of healer",
        "interspecies reviewers", "valkyrie drive", "prison school", "kiss x sis", "to love-ru",
        "to love ru", "seikon no qwaser"
    )

    private val EXTREME_PHRASES = arrayOf(
        "hot spring", "fan service", "panty shot", "mixed bath"
    )

    private val CJK_HARDCORE = arrayOf(
        "成年", "18禁", "無修正", "裏ビデオ", "潮吹き", "乱交", "ポルノ", "パイズリ", "フェラ", "中出し"
    )

    private val CJK_SUGGESTIVE = arrayOf(
        "エロ", "エッチ", "変態", "巨乳", "爆乳", "水着", "ビキニ", "グラビア", "痴女", "調教",
        "下着", "露出", "同人", "熟女", "性的", "美乳", "貧乳", "微乳", "パンチラ", "性愛", "アダルト"
    )

    private val CJK_EXTREME = arrayOf(
        "混浴", "温泉", "お風呂", "ハーレム", "お色気", "水着回", "盗撮"
    )

    private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9+]+")

    /**
     * Resolves the user's active filter sensitivity level.
     */
    fun getFilterLevel(ctx: Context? = null): NsfwFilterLevel {
        val context = ctx ?: CloudStreamApp.context ?: return NsfwFilterLevel.MODERATE
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val levelStr = prefs.getString(context.getString(R.string.nsfw_filter_level_key), "moderate")
            NsfwFilterLevel.fromString(levelStr)
        } catch (_: Throwable) {
            NsfwFilterLevel.MODERATE
        }
    }

    /**
     * Classifies title, URL, or tags against high-speed pure Kotlin classifier.
     * Evaluates in microseconds without crossing JNI or creating heap garbage.
     */
    fun isNsfwText(
        title: String,
        url: String,
        tags: String? = null,
        level: NsfwFilterLevel = getFilterLevel()
    ): Boolean {
        return isTextNsfwSingle(title, level) ||
               isTextNsfwSingle(url, level) ||
               (tags != null && isTextNsfwSingle(tags, level))
    }

    private fun isTextNsfwSingle(text: String, level: NsfwFilterLevel): Boolean {
        if (text.isEmpty()) return false

        // 1. CJK stem checks
        for (stem in CJK_HARDCORE) {
            if (text.contains(stem)) return true
        }
        if (level >= NsfwFilterLevel.MODERATE) {
            for (stem in CJK_SUGGESTIVE) {
                if (text.contains(stem)) return true
            }
        }
        if (level >= NsfwFilterLevel.EXTREME) {
            for (stem in CJK_EXTREME) {
                if (text.contains(stem)) return true
            }
        }

        val lower = text.lowercase()

        // 2. Multi-word phrases
        for (phrase in HARDCORE_PHRASES) {
            if (lower.contains(phrase)) return true
        }
        if (level >= NsfwFilterLevel.MODERATE) {
            for (phrase in SUGGESTIVE_PHRASES) {
                if (lower.contains(phrase)) return true
            }
        }
        if (level >= NsfwFilterLevel.EXTREME) {
            for (phrase in EXTREME_PHRASES) {
                if (lower.contains(phrase)) return true
            }
        }

        // 3. Summer / Beach contextual triggers
        if (level >= NsfwFilterLevel.MODERATE) {
            val hasSummerOrBeach = lower.contains("summer") || lower.contains("beach")
            if (hasSummerOrBeach && (
                lower.contains("swim") || lower.contains("bikini") || lower.contains("monokini") ||
                lower.contains("microkini") || lower.contains("lingerie") || lower.contains("topless") ||
                lower.contains("nude") || lower.contains("naked") || lower.contains("edition") ||
                (lower.contains("ova") && lower.contains("special")) ||
                (lower.contains("summer") && lower.contains("beach"))
            )) {
                return true
            }
        }

        if (level >= NsfwFilterLevel.EXTREME && (
            lower.contains("onsen") || lower.contains("bathhouse") ||
            (lower.contains("hot") && lower.contains("spring")) ||
            (lower.contains("bath") && (lower.contains("girl") || lower.contains("scene") || lower.contains("special")))
        )) {
            return true
        }

        // 4. Delimited token matching (words separated by punctuation/spaces)
        val tokens = lower.split(TOKEN_SPLIT_REGEX)
        for (token in tokens) {
            if (token.isEmpty()) continue
            if (HARDCORE_KEYWORDS.contains(token)) return true
            if (level >= NsfwFilterLevel.MODERATE && SUGGESTIVE_KEYWORDS.contains(token)) return true
            if (level >= NsfwFilterLevel.EXTREME && EXTREME_KEYWORDS.contains(token)) return true
        }
        return false
    }

    /**
     * URL classification helper.
     */
    fun isNsfwUrl(url: String, level: NsfwFilterLevel = getFilterLevel()): Boolean {
        return isNsfwText("", url, null, level)
    }

    /**
     * Checks if a SearchResponse item is classified as adult/NSFW.
     * Evaluates item type, provider declaration, cached AI poster classification, and metadata.
     */
    fun isNsfw(item: SearchResponse, level: NsfwFilterLevel = getFilterLevel()): Boolean {
        if (item.type == TvType.NSFW) return true

        // Check if provider itself is inherently an adult/NSFW provider
        APIHolder.getApiFromNameNull(item.apiName)?.let { provider ->
            if (isNsfwProvider(provider)) return true
        }

        // Check AI model visual cache if poster URL has already been evaluated as NSFW
        item.posterUrl?.let { url ->
            if (OnDeviceNsfwClassifier.isCachedNsfw(url)) return true
        }

        // Multi-vector text check on title, item URL, and poster URL
        if (isNsfwText(item.name, item.url, item.posterUrl, level)) {
            return true
        }

        return false
    }

    /**
     * Checks if a LoadResponse item is classified as adult/NSFW.
     */
    fun isNsfw(item: LoadResponse, level: NsfwFilterLevel = getFilterLevel()): Boolean {
        if (item.type == TvType.NSFW) return true

        APIHolder.getApiFromNameNull(item.apiName)?.let { provider ->
            if (isNsfwProvider(provider)) return true
        }

        item.posterUrl?.let { url ->
            if (OnDeviceNsfwClassifier.isCachedNsfw(url)) return true
        }

        val tagsStr = item.tags?.joinToString(" ")
        if (isNsfwText(item.name, item.url, tagsStr ?: item.posterUrl, level)) {
            return true
        }

        return false
    }

    /**
     * Checks if a provider API is declared as an adult/NSFW provider.
     */
    fun isNsfwProvider(api: MainAPI): Boolean {
        return api.supportedTypes.contains(TvType.NSFW) || api.supportedTypes == setOf(TvType.NSFW)
    }

    fun isNsfwProvider(repo: APIRepository): Boolean {
        return isNsfwProvider(repo.api)
    }

    /**
     * Checks if a home feed row is an adult/NSFW row based on category name or row contents.
     */
    fun isNsfwFeedRow(name: String, items: List<SearchResponse>, level: NsfwFilterLevel = getFilterLevel()): Boolean {
        // 1. If the row category name itself indicates adult, ecchi, or summer/beach swimsuit, eliminate the row
        if (isNsfwText(name, "", null, level)) {
            return true
        }

        // 2. If row items predominantly contain adult content
        if (items.isNotEmpty()) {
            val nsfwCount = items.count { isNsfw(it, level) }
            if (nsfwCount > 0 && (nsfwCount * 2 >= items.size || (items.size >= 4 && nsfwCount * 3 >= items.size))) {
                return true
            }
        }
        return false
    }

    /**
     * Filters a list of SearchResponse items based on whether NSFW content is enabled.
     * When disabled (default), all adult items are completely removed.
     */
    fun filterSearchResults(data: List<SearchResponse>, isNsfwEnabled: Boolean): List<SearchResponse> {
        if (isNsfwEnabled) return data
        val level = getFilterLevel()
        return data.filterNot { isNsfw(it, level) }
    }

    /**
     * Filters a home page row, removing adult items or eliminating the row entirely if empty or adult.
     * When disabled (default), all adult categories and items are completely removed.
     */
    fun filterHomePageList(data: HomePageList, isNsfwEnabled: Boolean): HomePageList? {
        if (isNsfwEnabled) return data
        val level = getFilterLevel()
        if (isNsfwFeedRow(data.name, data.list, level)) return null

        val filteredItems = data.list.filterNot { isNsfw(it, level) }
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
