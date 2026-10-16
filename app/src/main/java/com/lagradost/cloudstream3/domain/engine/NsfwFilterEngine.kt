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
import com.lagradost.cloudstream3.services.NativeCoreBridge
import com.lagradost.cloudstream3.ui.APIRepository

/**
 * Configurable Content Safety Sensitivity Levels:
 * - EXTREME (0.12f / native 2): Maximum sensitivity strict family shield.
 * - MODERATE (0.26f / native 1): Default balanced family level.
 * - LOW (0.55f / native 0): Hardcore adult content only.
 */
enum class NsfwFilterLevel(
    val key: String,
    val visualThreshold: Float,
    val nativeLevel: Int
) {
    EXTREME("extreme", 0.12f, 2),
    MODERATE("moderate", 0.26f, 1),
    LOW("low", 0.55f, 0);

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
 * 2. Multi-language explicit adult, ecchi, and beach/summer keyword guardian (`nsfw_classifier.rs`).
 * 3. Autonomous fallback heuristic if native library is temporarily uninitialized.
 */
object NsfwFilterEngine {

    private val FALLBACK_HARDCORE_KEYWORDS = hashSetOf(
        "hentai", "hanime", "xxx", "porn", "porno", "nsfw", "jav", "r18", "18+", "uncensored",
        "decensored", "bukkake", "creampie", "blowjob", "gangbang", "deepthroat", "masturbation",
        "incest", "nude", "nudity", "naked", "topless", "erotica", "erotic", "overflow", "onichichi"
    )

    private val FALLBACK_SUGGESTIVE_KEYWORDS = hashSetOf(
        "ecchi", "ero", "eroge", "ahegao", "doujin", "doujinshi", "bikini", "swimsuit",
        "swimwear", "beachwear", "monokini", "gravure", "lingerie", "cleavage", "oppai",
        "busty", "milf", "smut", "lewd", "sensual", "sex", "sexy", "playboy", "dxd", "thong",
        "panties", "panty", "boobs", "breasts", "fetish", "bdsm"
    )

    private val FALLBACK_EXTREME_KEYWORDS = hashSetOf(
        "bathing", "bath", "onsen", "hotspring", "harem", "seductive", "provocative",
        "fanservice", "fan-service", "waifu", "pantyshot", "microkini", "spank"
    )

    private val FALLBACK_CJK_HARDCORE = arrayOf(
        "成年", "18禁", "無修正", "ポルノ", "裏ビデオ", "乱交", "中出し", "潮吹き", "フェラ", "パイズリ"
    )

    private val FALLBACK_CJK_SUGGESTIVE = arrayOf(
        "エロ", "エッチ", "変態", "巨乳", "爆乳", "水着", "ビキニ", "グラビア", "痴女", "調教",
        "下着", "露出", "同人", "熟女", "性的", "美乳", "微乳", "貧乳", "パンチラ"
    )

    private val FALLBACK_CJK_EXTREME = arrayOf(
        "混浴", "温泉", "お風呂", "ハーレム", "お色気", "水着回", "盗撮"
    )

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
     * Classifies title, URL, or tags against native Rust classifier and fallback heuristics.
     */
    fun isNsfwText(
        title: String,
        url: String,
        tags: String? = null,
        level: NsfwFilterLevel = getFilterLevel()
    ): Boolean {
        val nativeRes = NativeCoreBridge.isNsfw(title, url, tags, level.nativeLevel)
        if (nativeRes != null) {
            return nativeRes
        }

        // Fallback heuristic if native bridge is not yet loaded
        return fallbackIsNsfw(title, level) || fallbackIsNsfw(url, level) || (tags != null && fallbackIsNsfw(tags, level))
    }

    private fun fallbackIsNsfw(text: String, level: NsfwFilterLevel): Boolean {
        if (text.isEmpty()) return false
        for (stem in FALLBACK_CJK_HARDCORE) {
            if (text.contains(stem)) return true
        }
        if (level >= NsfwFilterLevel.MODERATE) {
            for (stem in FALLBACK_CJK_SUGGESTIVE) {
                if (text.contains(stem)) return true
            }
        }
        if (level >= NsfwFilterLevel.EXTREME) {
            for (stem in FALLBACK_CJK_EXTREME) {
                if (text.contains(stem)) return true
            }
        }

        val lower = text.lowercase()
        if (level >= NsfwFilterLevel.MODERATE) {
            if (lower.contains("summer") || lower.contains("beach")) {
                if (lower.contains("swim") || lower.contains("bikini") || lower.contains("monokini") ||
                    lower.contains("microkini") || lower.contains("lingerie") || lower.contains("edition") ||
                    (lower.contains("ova") && lower.contains("special")) ||
                    (lower.contains("summer") && lower.contains("beach"))
                ) {
                    return true
                }
            }
        }

        val tokens = lower.split(Regex("[^a-z0-9+]+"))
        for (token in tokens) {
            if (FALLBACK_HARDCORE_KEYWORDS.contains(token)) return true
            if (level >= NsfwFilterLevel.MODERATE && FALLBACK_SUGGESTIVE_KEYWORDS.contains(token)) return true
            if (level >= NsfwFilterLevel.EXTREME && FALLBACK_EXTREME_KEYWORDS.contains(token)) return true
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
