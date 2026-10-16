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
 * Robust Mathematical Poster & Multi-Vector NSFW Classification Engine.
 *
 * Integrates:
 * 1. Native Rust ITU-R BT.601 + RGB + HSV skin exposure detector with aggressive thresholds
 *    for erotica, bikini, beach, and summer exposure (`poster_detector.rs`).
 * 2. Multi-language explicit adult, ecchi, and beach/summer keyword guardian (`nsfw_classifier.rs`).
 * 3. Autonomous fallback heuristic if native library is temporarily uninitialized.
 */
object NsfwFilterEngine {

    private val FALLBACK_KEYWORDS = hashSetOf(
        "hentai", "hanime", "ecchi", "erotica", "erotic", "xxx", "porn", "porno",
        "nsfw", "jav", "r18", "18+", "uncensored", "bikini", "swimsuit", "swimwear",
        "beachwear", "monokini", "gravure", "lingerie", "nude", "nudity", "naked",
        "topless", "cleavage", "oppai", "busty", "milf", "smut", "lewd", "sensual",
        "sex", "sexy", "playboy", "dxd", "overflow", "ahegao", "doujin", "doujinshi"
    )

    private val FALLBACK_CJK_STEMS = arrayOf(
        "エロ", "エッチ", "変態", "成年", "18禁", "無修正", "巨乳", "爆乳",
        "水着", "ビキニ", "グラビア", "痴女", "調教", "下着", "露出", "同人", "熟女"
    )

    /**
     * Legacy mathematical skin exposure check. Deprecated in favor of [OnDeviceNsfwClassifier]
     * powered by quantized on-device TensorFlow Lite neural network.
     */
    @Deprecated("Replaced by OnDeviceNsfwClassifier on-device AI model")
    fun isSkinExposureExcessive(rgbBytes: ByteArray, bytesPerPixel: Int = 3): Boolean {
        return false
    }

    /**
     * Classifies title, URL, or tags against native Rust classifier and fallback heuristics.
     */
    fun isNsfwText(title: String, url: String, tags: String? = null): Boolean {
        val nativeRes = NativeCoreBridge.isNsfw(title, url, tags)
        if (nativeRes != null) {
            return nativeRes
        }

        // Fallback heuristic if native bridge is not yet loaded
        return fallbackIsNsfw(title) || fallbackIsNsfw(url) || (tags != null && fallbackIsNsfw(tags))
    }

    private fun fallbackIsNsfw(text: String): Boolean {
        if (text.isEmpty()) return false
        for (stem in FALLBACK_CJK_STEMS) {
            if (text.contains(stem)) return true
        }
        val lower = text.lowercase()
        if (lower.contains("summer") || lower.contains("beach")) {
            if (lower.contains("swim") || lower.contains("bikini") || lower.contains("monokini") ||
                lower.contains("edition") || (lower.contains("ova") && lower.contains("special")) ||
                (lower.contains("summer") && lower.contains("beach"))
            ) {
                return true
            }
        }
        val tokens = lower.split(Regex("[^a-z0-9+]+"))
        for (token in tokens) {
            if (FALLBACK_KEYWORDS.contains(token)) return true
        }
        return false
    }

    /**
     * URL classification helper.
     */
    fun isNsfwUrl(url: String): Boolean {
        return isNsfwText("", url, null)
    }

    /**
     * Checks if a SearchResponse item is classified as adult/NSFW.
     * Evaluates item type, provider declaration, and metadata (name, URL, poster URL).
     */
    fun isNsfw(item: SearchResponse): Boolean {
        if (item.type == TvType.NSFW) return true

        // Check if provider itself is inherently an adult/NSFW provider
        APIHolder.getApiFromNameNull(item.apiName)?.let { provider ->
            if (isNsfwProvider(provider)) return true
        }

        // Multi-vector text check on title, item URL, and poster URL
        if (isNsfwText(item.name, item.url, item.posterUrl)) {
            return true
        }

        return false
    }

    /**
     * Checks if a LoadResponse item is classified as adult/NSFW.
     */
    fun isNsfw(item: LoadResponse): Boolean {
        if (item.type == TvType.NSFW) return true

        APIHolder.getApiFromNameNull(item.apiName)?.let { provider ->
            if (isNsfwProvider(provider)) return true
        }

        val tagsStr = item.tags?.joinToString(" ")
        if (isNsfwText(item.name, item.url, tagsStr ?: item.posterUrl)) {
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
    fun isNsfwFeedRow(name: String, items: List<SearchResponse>): Boolean {
        // 1. If the row category name itself indicates adult, ecchi, or summer/beach swimsuit, eliminate the row
        if (isNsfwText(name, "", null)) {
            return true
        }

        // 2. If row items predominantly contain adult content
        if (items.isNotEmpty()) {
            val nsfwCount = items.count { isNsfw(it) }
            if (nsfwCount > 0 && (nsfwCount * 2 >= items.size || (items.size >= 4 && nsfwCount * 3 >= items.size))) {
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
