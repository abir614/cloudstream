package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.io.File

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.TorrentSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.HomePageList

data class CachedSearchResponseItem(
    @JsonProperty("name") override val name: String = "",
    @JsonProperty("url") override val url: String = "",
    @JsonProperty("apiName") override val apiName: String = "",
    @JsonProperty("type") override var type: TvType? = null,
    @JsonProperty("posterUrl") override var posterUrl: String? = null,
    @JsonProperty("id") override var id: Int? = null,
    @JsonProperty("quality") override var quality: SearchQuality? = null,
    @JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
    @JsonProperty("year") var year: Int? = null,
    @JsonProperty("episodes") var episodes: Int? = null,
    @JsonProperty("lang") var lang: String? = null,
    @JsonProperty("dubStatus") var dubStatus: Set<DubStatus>? = null,
    @JsonProperty("concreteType") var concreteType: String? = null
) : SearchResponse {
    override var score: Score? = null

    fun toConcreteSearchResponse(): SearchResponse {
        return when (concreteType) {
            "AnimeSearchResponse" -> {
                @Suppress("DEPRECATION_ERROR")
                AnimeSearchResponse(
                    name = name,
                    url = url,
                    apiName = apiName,
                    type = type,
                    posterUrl = posterUrl,
                    year = year,
                    dubStatus = dubStatus?.toMutableSet(),
                    id = id,
                    quality = quality,
                    posterHeaders = posterHeaders
                )
            }
            "TvSeriesSearchResponse" -> {
                @Suppress("DEPRECATION_ERROR")
                TvSeriesSearchResponse(
                    name = name,
                    url = url,
                    apiName = apiName,
                    type = type,
                    posterUrl = posterUrl,
                    year = year,
                    episodes = episodes,
                    id = id,
                    quality = quality,
                    posterHeaders = posterHeaders
                )
            }
            "LiveSearchResponse" -> {
                @Suppress("DEPRECATION_ERROR")
                LiveSearchResponse(
                    name = name,
                    url = url,
                    apiName = apiName,
                    type = type,
                    posterUrl = posterUrl,
                    id = id,
                    quality = quality,
                    posterHeaders = posterHeaders,
                    lang = lang
                )
            }
            "TorrentSearchResponse" -> {
                @Suppress("DEPRECATION_ERROR")
                TorrentSearchResponse(
                    name = name,
                    url = url,
                    apiName = apiName,
                    type = type,
                    posterUrl = posterUrl,
                    id = id,
                    quality = quality,
                    posterHeaders = posterHeaders
                )
            }
            else -> {
                @Suppress("DEPRECATION_ERROR")
                MovieSearchResponse(
                    name = name,
                    url = url,
                    apiName = apiName,
                    type = type,
                    posterUrl = posterUrl,
                    year = year,
                    id = id,
                    quality = quality,
                    posterHeaders = posterHeaders
                )
            }
        }
    }

    companion object {
        fun fromSearchResponse(res: SearchResponse): CachedSearchResponseItem {
            val year = (res as? MovieSearchResponse)?.year ?: (res as? TvSeriesSearchResponse)?.year ?: (res as? AnimeSearchResponse)?.year
            val ep = (res as? TvSeriesSearchResponse)?.episodes
            val lang = (res as? LiveSearchResponse)?.lang
            val dub = (res as? AnimeSearchResponse)?.dubStatus
            val concreteType = when (res) {
                is AnimeSearchResponse -> "AnimeSearchResponse"
                is TvSeriesSearchResponse -> "TvSeriesSearchResponse"
                is LiveSearchResponse -> "LiveSearchResponse"
                is TorrentSearchResponse -> "TorrentSearchResponse"
                else -> "MovieSearchResponse"
            }
            return CachedSearchResponseItem(
                name = res.name,
                url = res.url,
                apiName = res.apiName,
                type = res.type,
                posterUrl = res.posterUrl,
                id = res.id,
                quality = res.quality,
                posterHeaders = res.posterHeaders,
                year = year,
                episodes = ep,
                lang = lang,
                dubStatus = dub,
                concreteType = concreteType
            )
        }
    }
}

data class CachedHomePageListItem(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("list") val list: List<CachedSearchResponseItem> = emptyList(),
    @JsonProperty("isHorizontalImages") val isHorizontalImages: Boolean = false
) {
    fun toHomePageList(): HomePageList {
        return HomePageList(
            name = name,
            list = list.map { it.toConcreteSearchResponse() },
            isHorizontalImages = isHorizontalImages
        )
    }

    companion object {
        fun fromHomePageList(item: HomePageList): CachedHomePageListItem {
            return CachedHomePageListItem(
                name = item.name,
                list = item.list.map { CachedSearchResponseItem.fromSearchResponse(it) },
                isHorizontalImages = item.isHorizontalImages
            )
        }
    }
}

data class CachedHomePageResponseItem(
    @JsonProperty("items") val items: List<CachedHomePageListItem> = emptyList(),
    @JsonProperty("hasNext") val hasNext: Boolean = false
) {
    fun toHomePageResponse(): HomePageResponse {
        @Suppress("DEPRECATION_ERROR")
        return HomePageResponse(
            items = items.map { it.toHomePageList() },
            hasNext = hasNext
        )
    }

    companion object {
        fun fromHomePageResponse(res: HomePageResponse?): CachedHomePageResponseItem? {
            if (res == null) return null
            return CachedHomePageResponseItem(
                items = res.items.map { CachedHomePageListItem.fromHomePageList(it) },
                hasNext = res.hasNext
            )
        }
    }
}

data class MainPageDiskCacheEntry(
    @JsonProperty("unixTime") val unixTime: Long = 0L,
    @JsonProperty("apiName") val apiName: String = "",
    @JsonProperty("page") val page: Int = 0,
    @JsonProperty("data") val data: List<CachedHomePageResponseItem?> = emptyList()
)

object MainPageDiskCache {
    private const val FOLDER = "cs3_mainpage_cache"
    private const val DISK_TTL_SECONDS = 3600L * 24L // 24 hours persistent cache for instant offline/warm load

    private fun getCacheFile(apiName: String, page: Int): File? {
        val ctx = CloudStreamApp.context ?: return null
        val safeName = apiName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = ctx.cacheDir.resolve(FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir.resolve("${safeName}_${page}.json")
    }

    fun get(apiName: String, page: Int): List<HomePageResponse?>? {
        return try {
            val file = getCacheFile(apiName, page) ?: return null
            if (!file.exists() || !file.isFile) return null
            val text = file.readText()
            val entry = tryParseJson<MainPageDiskCacheEntry>(text) ?: return null
            if (unixTime - entry.unixTime < DISK_TTL_SECONDS) {
                entry.data.map { it?.toHomePageResponse() }
            } else {
                null
            }
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    fun put(apiName: String, page: Int, response: List<HomePageResponse?>) {
        ioSafe {
            try {
                val file = getCacheFile(apiName, page) ?: return@ioSafe
                val cachedData = response.map { CachedHomePageResponseItem.fromHomePageResponse(it) }
                val entry = MainPageDiskCacheEntry(
                    unixTime = unixTime,
                    apiName = apiName,
                    page = page,
                    data = cachedData
                )
                file.writeText(entry.toJsonLiteral())
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    fun clear() {
        ioSafe {
            try {
                val ctx = CloudStreamApp.context ?: return@ioSafe
                val dir = ctx.cacheDir.resolve(FOLDER)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            } catch (_: Throwable) {
            }
        }
    }
}

class APIRepository(val api: MainAPI) {
    companion object {
        // Overall timeout: 1 minute (60s) for all source loading to prevent hung extractors
        private const val DEFAULT_TIMEOUT = 60_000L
        private const val MAX_TIMEOUT = 2 * DEFAULT_TIMEOUT
        private const val MIN_TIMEOUT = 5_000L

        var dubStatusActive = HashSet<DubStatus>()

        val noneApi = object : MainAPI() {
            override var name = "None"
            override val supportedTypes = emptySet<TvType>()
            override var lang = ""
        }
        val randomApi = object : MainAPI() {
            override var name = "Random"
            override val supportedTypes = emptySet<TvType>()
            override var lang = ""
        }

        fun isInvalidData(data: String): Boolean {
            return data.isEmpty() || data == "[]" || data == "about:blank"
        }

        data class SavedLoadResponse(
            val unixTime: Long,
            val response: LoadResponse,
            val hash: Pair<String, String>
        )

        private val cache = atomicListOf<SavedLoadResponse>()
        private var cacheIndex: Int = 0
        const val CACHE_SIZE = 20

        data class SavedMainPageResponse(
            val unixTime: Long,
            val response: List<HomePageResponse?>,
            val key: Pair<String, Int>
        )

        private val mainPageCache = atomicListOf<SavedMainPageResponse>()
        const val MAIN_PAGE_CACHE_SIZE = 10

        fun getTimeout(desired: Long?): Long {
            return (desired ?: DEFAULT_TIMEOUT).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
        }

        fun getCachedMainPage(apiName: String, page: Int): List<HomePageResponse?>? {
            val cacheKey = Pair(apiName, page)
            val mem = mainPageCache.withLock {
                mainPageCache.firstOrNull { it.key == cacheKey }?.response
            }
            if (mem != null) return mem
            return MainPageDiskCache.get(apiName, page)
        }

        fun clearMainPageCache() {
            mainPageCache.clear()
            MainPageDiskCache.clear()
        }
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        if (forceReload) {
            cache.clear()
            mainPageCache.clear()
            MainPageDiskCache.clear()
        }
    }

    init {
        afterPluginsLoadedEvent += ::afterPluginsLoaded
    }

    val hasMainPage = api.hasMainPage
    val providerType = api.providerType
    val name = api.name
    val mainUrl = api.mainUrl
    val mainPage = api.mainPage
    val hasQuickSearch = api.hasQuickSearch
    val vpnStatus = api.vpnStatus

    suspend fun load(url: String): Resource<LoadResponse> {
        return safeApiCall {
            withTimeout(getTimeout(api.loadTimeoutMs)) {
                if (isInvalidData(url)) throw ErrorLoadingException()
                val fixedUrl = api.fixUrl(url)
                val lookingForHash = Pair(api.name, fixedUrl)

                val cached = cache.withLock {
                    var found: LoadResponse? = null
                    for (item in cache) {
                        // 10 min save
                        if (item.hash == lookingForHash && (unixTime - item.unixTime) < 60 * 10) {
                            found = item.response
                            break
                        }
                    }
                    found
                }

                if (cached != null) return@withTimeout cached
                api.load(fixedUrl)?.also { response ->
                    // Remove all blank tags as early as possible
                    response.tags = response.tags?.filter { it.isNotBlank() }
                    val add = SavedLoadResponse(unixTime, response, lookingForHash)

                    cache.withLock {
                        if (cache.size > CACHE_SIZE) {
                            cache[cacheIndex] = add // rolling cache
                            cacheIndex = (cacheIndex + 1) % CACHE_SIZE
                        } else {
                            cache.add(add)
                        }
                    }
                } ?: throw ErrorLoadingException()
            }
        }
    }

    suspend fun search(query: String, page: Int): Resource<SearchResponseList> {
        if (query.isEmpty())
            return Resource.Success(newSearchResponseList(emptyList<SearchResponse>()))

        return safeApiCall {
            withTimeout(getTimeout(api.searchTimeoutMs)) {
                (api.search(query, page)
                    ?: throw ErrorLoadingException())
                //                .filter { typesActive.contains(it.type) }
            }
        }
    }

    suspend fun quickSearch(query: String): Resource<SearchResponseList> {
        if (query.isEmpty())
            return Resource.Success(newSearchResponseList(emptyList<SearchResponse>()))

        return safeApiCall {
            withTimeout(getTimeout(api.quickSearchTimeoutMs)) {
                newSearchResponseList(
                    api.quickSearch(query) ?: throw ErrorLoadingException(),
                    false
                )
            }
        }
    }

    suspend fun waitForHomeDelay() {
        val delta = api.sequentialMainPageScrollDelay + api.lastHomepageRequest - unixTimeMS
        if (delta < 0) return
        delay(delta)
    }

    suspend fun getMainPage(
        page: Int,
        nameIndex: Int? = null,
        forceReload: Boolean = false
    ): Resource<List<HomePageResponse?>> {
        return safeApiCall {
            withTimeout(getTimeout(api.getMainPageTimeoutMs)) {
                api.lastHomepageRequest = unixTimeMS

                nameIndex?.let { api.mainPage.getOrNull(it) }?.let { data ->
                    listOf(
                        try {
                            api.getMainPage(
                                page,
                                MainPageRequest(data.name, data.data, data.horizontalImages)
                            )
                        } catch (t: Throwable) {
                            logError(t)
                            null
                        }
                    )
                } ?: run {
                    val cacheKey = Pair(api.name, page)
                    if (!forceReload) {
                        val cached = mainPageCache.withLock {
                            mainPageCache.firstOrNull {
                                it.key == cacheKey && (unixTime - it.unixTime) < 300 // 5-minute TTL
                            }?.response
                        }
                        if (cached != null) return@withTimeout cached

                        val diskCached = MainPageDiskCache.get(api.name, page)
                        if (diskCached != null) {
                            val entry = SavedMainPageResponse(unixTime, diskCached, cacheKey)
                            mainPageCache.withLock {
                                mainPageCache.removeAll { it.key == cacheKey }
                                mainPageCache.add(entry)
                            }
                            return@withTimeout diskCached
                        }
                    }

                    val results = if (api.sequentialMainPage) {
                        var first = true
                        api.mainPage.map { data ->
                            if (!first) // dont want to sleep on first request
                                delay(api.sequentialMainPageDelay)
                            first = false

                            try {
                                api.getMainPage(
                                    page,
                                    MainPageRequest(data.name, data.data, data.horizontalImages)
                                )
                            } catch (t: Throwable) {
                                logError(t)
                                null
                            }
                        }
                    } else {
                        supervisorScope {
                            api.mainPage.map { data ->
                                async {
                                    try {
                                        api.getMainPage(
                                            page,
                                            MainPageRequest(data.name, data.data, data.horizontalImages)
                                        )
                                    } catch (t: Throwable) {
                                        logError(t)
                                        null
                                    }
                                }
                            }.map {
                                try {
                                    it.await()
                                } catch (t: Throwable) {
                                    logError(t)
                                    null
                                }
                            }
                        }
                    }

                    if (results.any { it != null }) {
                        val entry = SavedMainPageResponse(unixTime, results, cacheKey)
                        mainPageCache.withLock {
                            mainPageCache.removeAll { it.key == cacheKey }
                            if (mainPageCache.size >= MAIN_PAGE_CACHE_SIZE) {
                                mainPageCache.removeAt(0)
                            }
                            mainPageCache.add(entry)
                        }
                        MainPageDiskCache.put(api.name, page, results)
                    }

                    results
                }
            }
        }
    }

    suspend fun extractorVerifierJob(extractorData: String?) {
        safeApiCall {
            api.extractorVerifierJob(extractorData)
        }
    }

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (isInvalidData(data)) return false // this makes providers cleaner
        return try {
            withTimeout(getTimeout(api.loadLinksTimeoutMs)) {
                api.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        } catch (throwable: Throwable) {
            logError(throwable)
            return false
        }
    }
}
