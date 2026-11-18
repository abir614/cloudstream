package com.lagradost.cloudstream3.ui.home

import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine
import com.lagradost.cloudstream3.domain.engine.NsfwFilterLevel
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.isNsfwEnabled
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.AppContextUtils.shouldCensorPosters

import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.deleteAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllWatchStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount
import com.lagradost.cloudstream3.utils.DataStoreHelper.getLastWatched
import com.lagradost.cloudstream3.utils.DataStoreHelper.getResultWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.CachePolicy
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.DeviceProfile
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

private data class PosterPreloadTarget(
    val name: String,
    val url: String,
    val posterUrl: String,
    val posterHeaders: Map<String, String>?
)

class HomeViewModel : ViewModel() {
    companion object {
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? {
            val resumeWatching = withContext(Dispatchers.IO) {
                getAllResumeStateIds()?.mapNotNull { id ->
                    getLastWatched(id)
                }?.sortedBy { -it.updateTime }
            }
            val resumeWatchingResult = withContext(Dispatchers.IO) {
                resumeWatching?.mapNotNull { resume ->
                    val data = getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE,
                        resume.parentId.toString()
                    ) ?: getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE_BACKUP,
                        resume.parentId.toString()
                    ) ?: return@mapNotNull null

                    val watchPos = getViewPos(resume.episodeId)

                    DataStoreHelper.ResumeWatchingResult(
                        data.name,
                        data.url,
                        data.apiName,
                        data.type,
                        data.poster,
                        watchPos,
                        resume.episodeId,
                        resume.parentId,
                        resume.episode,
                        resume.season,
                        resume.isFromDownload
                    )
                }
            }
            return resumeWatchingResult
        }
    }

    fun deleteResumeWatching() {
        deleteAllResumeStateIds()
        loadResumeWatching()
    }

    fun deleteBookmarks(list: List<SearchResponse>) {
        list.forEach { DataStoreHelper.deleteBookmarkedData(it.id) }
        loadStoredData()
    }

    var repo: APIRepository? = null

    private val _apiName = MutableLiveData<String>()
    val apiName: LiveData<String> = _apiName

    private val _currentAccount = MutableLiveData<DataStoreHelper.Account?>()
    val currentAccount: MutableLiveData<DataStoreHelper.Account?> = _currentAccount

    private val _randomItems = MutableLiveData<List<SearchResponse>?>(null)
    val randomItems: LiveData<List<SearchResponse>?> = _randomItems

    private var currentShuffledList: List<SearchResponse> = listOf()

    private val _availableWatchStatusTypes =
        MutableLiveData<Pair<Set<WatchType>, Set<WatchType>>>()
    val availableWatchStatusTypes: LiveData<Pair<Set<WatchType>, Set<WatchType>>> =
        _availableWatchStatusTypes
    private val _bookmarks = MutableLiveData<Pair<Boolean, List<SearchResponse>>>()
    val bookmarks: LiveData<Pair<Boolean, List<SearchResponse>>> = _bookmarks

    private val _resumeWatching = MutableLiveData<List<SearchResponse>>()
    private val _preview = MutableLiveData<Resource<Pair<Boolean, List<LoadResponse>>>>()
    private val previewResponses = CopyOnWriteArrayList<LoadResponse>()
    private val previewResponsesAdded = mutableSetOf<String>()

    val resumeWatching: LiveData<List<SearchResponse>> = _resumeWatching
    val preview: LiveData<Resource<Pair<Boolean, List<LoadResponse>>>> = _preview

    private fun loadResumeWatching() = viewModelScope.launchSafe {
        val resumeWatchingResult = getResumeWatching()
        if (isLayout(TV) && resumeWatchingResult != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ioSafe {
                // this WILL crash on non tvs, so keep this inside a try catch
                activity?.addProgramsToContinueWatching(resumeWatchingResult)
            }
        }
        resumeWatchingResult?.let {
            _resumeWatching.postValue(it)
        }
    }

    fun loadStoredData(preferredWatchStatus: Set<WatchType>?) = viewModelScope.launchSafe {
        val watchStatusIds = withContext(Dispatchers.IO) {
            getAllWatchStateIds()?.map { id ->
                Pair(id, getResultWatchState(id))
            }
        }?.distinctBy { it.first } ?: return@launchSafe

        val length = WatchType.entries.size
        val currentWatchTypes = mutableSetOf<WatchType>()

        for (watch in watchStatusIds) {
            currentWatchTypes.add(watch.second)
            if (currentWatchTypes.size >= length) {
                break
            }
        }

        currentWatchTypes.remove(WatchType.NONE)

        if (currentWatchTypes.size <= 0) {
            DataStoreHelper.homeBookmarkedList = intArrayOf()
            _availableWatchStatusTypes.postValue(setOf<WatchType>() to setOf())
            _bookmarks.postValue(Pair(false, ArrayList()))
            return@launchSafe
        }

        val watchPrefNotNull = preferredWatchStatus ?: EnumSet.of(currentWatchTypes.first())

        DataStoreHelper.homeBookmarkedList = watchPrefNotNull.map { it.internalId }.toIntArray()
        _availableWatchStatusTypes.postValue(

            watchPrefNotNull to
                    currentWatchTypes,

            )

        val list = withContext(Dispatchers.IO) {
            watchStatusIds.filter { watchPrefNotNull.contains(it.second) }
                .mapNotNull { getBookmarkedData(it.first) }
                .sortedBy { -it.latestUpdatedTime }
        }
        _bookmarks.postValue(Pair(true, list))
    }

    private var onGoingLoad: Job? = null
    private var isCurrentlyLoadingName: String? = null
    private fun loadAndCancel(api: MainAPI, forceReload: Boolean = false) {
        onGoingLoad?.cancel()
        isCurrentlyLoadingName = api.name
        onGoingLoad = load(api, forceReload)
    }

    data class ExpandableHomepageList(
        var list: HomePageList,
        var currentPage: Int,
        var hasNext: Boolean,
    )

    data class ContentFilterProgress(
        val isFiltering: Boolean,
        val current: Int = 0,
        val total: Int = 0,
        val message: String = "Content Filtering in Progress"
    )

    private val _contentFilterProgress = MutableLiveData<ContentFilterProgress?>()
    val contentFilterProgress: LiveData<ContentFilterProgress?> = _contentFilterProgress

    fun clearFilterProgress() {
        _contentFilterProgress.postValue(null)
    }

    private val expandable: ConcurrentHashMap<String, ExpandableHomepageList> = ConcurrentHashMap()
    private val _page =
        MutableLiveData<Resource<Map<String, ExpandableHomepageList>>>(Resource.Loading())
    val page: LiveData<Resource<Map<String, ExpandableHomepageList>>> = _page

    val lock: MutableSet<String> = mutableSetOf()

    suspend fun expandAndReturn(name: String): ExpandableHomepageList? {
        if (lock.contains(name)) return null
        lock += name

        repo?.apply {
            waitForHomeDelay()

            expandable[name]?.let { current ->
                debugAssert({ !current.hasNext }) {
                    "Expand called when not needed"
                }

                val nextPage = current.currentPage + 1
                val next = getMainPage(nextPage, mainPage.indexOfFirst { it.name == name })
                if (next is Resource.Success) {
                    val nsfwEnabled = context?.isNsfwEnabled() ?: false
                    next.value.filterNotNull().forEach { main ->
                        main.items.forEach { rawList ->
                            val newList = NsfwFilterEngine.filterHomePageList(rawList, nsfwEnabled) ?: return@forEach
                            val key = newList.name
                            expandable[key]?.apply {
                                hasNext = main.hasNext
                                currentPage = nextPage

                                debugWarning({ newList.list.any { outer -> this.list.list.any { it.url == outer.url } } }) {
                                    "Expanded contained an item that was previously already in the list\n${list.name} = ${this.list.list}\n${newList.name} = ${newList.list}"
                                }

                                this.list.list += newList.list
                                this.list.list.distinctBy { it.url } // just to be sure we are not adding the same shit for some reason
                            } ?: debugWarning {
                                "Expanded an item not in main load named $key, current list is ${expandable.keys}"
                            }
                        }
                    }
                } else {
                    current.hasNext = false
                }
            }
            _page.postValue(Resource.Success(HashMap(expandable)))
        }

        lock -= name

        return expandable[name]
    }

    // this is soo over engineered, but idk how I can make it clean without making the main api harder to use :pensive:
    fun expand(name: String) = viewModelScope.launchSafe {
        expandAndReturn(name)
    }

    // returns the amount of items added and modifies current
    private suspend fun updatePreviewResponses(
        current: MutableList<LoadResponse>,
        alreadyAdded: MutableSet<String>,
        shuffledList: List<SearchResponse>,
        size: Int
    ): Int {
        var count = 0

        val addItems = arrayListOf<SearchResponse>()
        for (searchResponse in shuffledList) {
            if (!alreadyAdded.contains(searchResponse.url)) {
                addItems.add(searchResponse)
                previewResponsesAdded.add(searchResponse.url)
                if (++count >= size) {
                    break
                }
            }
        }

        val add = addItems.amap { searchResponse ->
            repo?.load(searchResponse.url)
        }.mapNotNull { if (it != null && it is Resource.Success) it.value else null }

        val nsfwEnabled = context?.isNsfwEnabled() ?: false
        val filterLevel = NsfwFilterEngine.getFilterLevel(context)
        val validAdd = if (!nsfwEnabled) {
            add.filterNot { item ->
                val posterUrl = item.backgroundPosterUrl ?: item.posterUrl
                val hasInvalidPoster = filterLevel == NsfwFilterLevel.EXTREME && (
                    posterUrl.isNullOrBlank() ||
                    posterUrl == "null" ||
                    (!posterUrl.startsWith("http://") && !posterUrl.startsWith("https://"))
                )
                NsfwFilterEngine.isNsfw(item, filterLevel) || hasInvalidPoster
            }
        } else {
            add
        }

        current.addAll(validAdd)
        return validAdd.size
    }

    private var addJob: Job? = null
    fun loadMoreHomeScrollResponses() {
        addJob = ioSafe {
            updatePreviewResponses(previewResponses, previewResponsesAdded, currentShuffledList, 1)
            _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
        }
    }

    private var rawHomeData: List<HomePageResponse?>? = null
    private var rescanJob: Job? = null

    fun rescanLoadedSources() {
        rescanJob?.cancel()
        rescanJob = ioSafe {
            val data = rawHomeData
            if (data != null && data.isNotEmpty()) {
                // Smooth in-memory re-filtering without flashing the loading screen or thrashing
                populateHomeFromData(data, isBackgroundUpdate = true)
            } else {
                loadAndCancel(DataStoreHelper.currentHomePage, forceReload = true)
            }
        }
    }

    private suspend fun populateHomeFromData(
        homeData: List<HomePageResponse?>,
        isBackgroundUpdate: Boolean = false
    ) {
        try {
            val nsfwEnabled = context?.isNsfwEnabled() ?: true
            val isPotato = context?.let { com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.isPotatoDevice(it) } ?: false

            val allRows = homeData.mapNotNull { it?.items }.flatten()
            val totalTextItems = allRows.sumOf { it.list.size }

            val shouldFilter = !nsfwEnabled || (context?.shouldCensorPosters() == true)
            val filterMsg = context?.getString(R.string.content_filtering_in_progress) ?: "Content Filtering in Progress"

            if (!isBackgroundUpdate && shouldFilter && totalTextItems > 0) {
                _contentFilterProgress.postValue(ContentFilterProgress(isFiltering = true, current = 0, total = 100, message = filterMsg))
            }

            val newExpandable = ConcurrentHashMap<String, ExpandableHomepageList>()
            var processedCount = 0
            homeData.forEach { home ->
                home?.items?.forEach { list ->
                    val nsfwFiltered = NsfwFilterEngine.filterHomePageList(list, nsfwEnabled)
                    processedCount += list.list.size
                    if (!isBackgroundUpdate && shouldFilter && totalTextItems > 0) {
                        val phase1Percent = (processedCount * 15 / totalTextItems).coerceIn(0, 15)
                        _contentFilterProgress.postValue(
                            ContentFilterProgress(
                                isFiltering = true,
                                current = phase1Percent,
                                total = 100,
                                message = filterMsg
                            )
                        )
                        if (isPotato) {
                            // Single-threaded pacing for ultra-potato (<=1GB) devices to prevent CPU/LMK memory spikes
                            kotlinx.coroutines.delay(10L)
                        }
                    }
                    if (nsfwFiltered != null) {
                        val filteredList =
                            context?.filterHomePageListByFilmQuality(nsfwFiltered) ?: nsfwFiltered
                        newExpandable[list.name] =
                            ExpandableHomepageList(
                                filteredList.copy(
                                    list = CopyOnWriteArrayList(
                                        filteredList.list
                                    )
                                ), 1, home.hasNext
                            )
                    }
                }
            }

            previewResponses.clear()
            previewResponsesAdded.clear()

            var randomItems: List<SearchResponse> = emptyList()
            var initialPreviews: List<LoadResponse> = emptyList()

            // Reuse already-filtered items from newExpandable to eliminate duplicate second-pass filterSearchResults
            val filteredHomeItems = newExpandable.values
                .flatMap { it.list.list }
                .distinctBy { it.url }

            if (filteredHomeItems.isNotEmpty()) {
                randomItems =
                    context?.filterSearchResultByFilmQuality(filteredHomeItems.shuffled())
                        ?: filteredHomeItems.shuffled()

                _randomItems.postValue(randomItems)
                currentShuffledList = randomItems

                val targetApi = repo?.api ?: noneApi
                initialPreviews = randomItems.take(3).map { item ->
                    val providerApi = getApiFromNameNull(item.apiName) ?: targetApi
                    providerApi.newMovieLoadResponse(
                        name = item.name,
                        url = item.url,
                        type = item.type ?: com.lagradost.cloudstream3.TvType.Movie,
                        dataUrl = item.url
                    ) {
                        this.posterUrl = item.posterUrl
                        this.backgroundPosterUrl = item.posterUrl
                        this.posterHeaders = item.posterHeaders
                    }
                }
            }

            // Phase 2: Hardware-Adaptive Preload & Viewport Cache Warmer
            val preloaderContext = context ?: com.lagradost.cloudstream3.CloudStreamApp.context
            val budget = preloaderContext?.let { DeviceProfile.getDynamicResourceBudget(it) }
            val imageLoader = preloaderContext?.let { SingletonImageLoader.get(it) }
            val threshold = preloaderContext?.let { NsfwFilterEngine.getVisualThreshold(it) } ?: 0.40f
            val posterWidthPx = 114.toPx
            val posterHeightPx = 180.toPx
            var uncachedOffscreenItems: List<PosterPreloadTarget> = emptyList()

            if (preloaderContext != null && budget != null && imageLoader != null && (newExpandable.isNotEmpty() || initialPreviews.isNotEmpty())) {
                com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.ensureScoresLoaded(preloaderContext)

                val bannerItems = initialPreviews
                    .filter { !it.posterUrl.isNullOrBlank() || !it.backgroundPosterUrl.isNullOrBlank() }
                    .take(5)
                    .map { preview ->
                        val pUrl = preview.backgroundPosterUrl ?: preview.posterUrl!!
                        PosterPreloadTarget(preview.name, preview.url, pUrl, preview.posterHeaders)
                    }

                val allRailItems = newExpandable.values
                    .flatMap { it.list.list }
                    .filter { !it.posterUrl.isNullOrBlank() }
                    .map { PosterPreloadTarget(it.name, it.url, it.posterUrl!!, it.posterHeaders) }

                val nsfwUrlsToRemove = ConcurrentHashMap.newKeySet<String>()

                // 1. Instant metadata & persistent score evaluation for offscreen rails (0ms, zero bitmap allocation):
                if (shouldFilter) {
                    val allItems = (bannerItems + allRailItems).distinctBy { it.posterUrl }
                    for (item in allItems) {
                        val cachedScore = com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.getCachedScore(item.posterUrl, preloaderContext)
                        if (cachedScore != null) {
                            if (!nsfwEnabled && cachedScore >= threshold) {
                                nsfwUrlsToRemove.add(item.url)
                            }
                        } else if (NsfwFilterEngine.isCleanTitleAllowed(item.name)) {
                            // Clean title guaranteed safe by precompiled Aho-Corasick & allowlist
                            com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.recordScore(item.posterUrl, 0.0f, preloaderContext)
                        }
                    }
                }

                // 2. Hardware-Adaptive Workload Partitioning:
                // Viewport targets (hero preview banners + first visible rails):
                // These are the ONLY posters visible on screen upon launch. Prioritizing these allows
                // the startup loading dialog to finish in under 1 second without stalling the user.
                val viewportTargets = (bannerItems + allRailItems.take(budget.viewportPreloadCount))
                    .distinctBy { it.posterUrl }
                    .take(budget.viewportPreloadCount)

                val uncachedViewportItems = mutableListOf<PosterPreloadTarget>()
                for (target in viewportTargets) {
                    val isMemCached = try {
                        imageLoader.memoryCache?.get(coil3.memory.MemoryCache.Key(target.posterUrl)) != null
                    } catch (_: Throwable) { false }
                    if (!isMemCached) {
                        uncachedViewportItems.add(target)
                    }
                }

                // For Low-End devices, collect offscreen items to prefetch asynchronously into flash DiskCache:
                uncachedOffscreenItems = if (budget.isLowEnd) {
                    (bannerItems + allRailItems)
                        .distinctBy { it.posterUrl }
                        .filter { offscreen -> !viewportTargets.any { it.posterUrl == offscreen.posterUrl } }
                        .filter { item ->
                            val isScored = com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.getCachedScore(item.posterUrl, preloaderContext) != null || NsfwFilterEngine.isCleanTitleAllowed(item.name)
                            val isDiskCached = try {
                                val snapshot = imageLoader.diskCache?.openSnapshot(item.posterUrl)
                                val exists = snapshot != null
                                try { snapshot?.close() } catch (_: Throwable) {}
                                exists
                            } catch (_: Throwable) { false }
                            !isDiskCached || !isScored
                        }
                } else emptyList()

                // Foreground preloader only loads viewport items, making the startup screen super fast & snappy!
                val itemsToLoad = uncachedViewportItems

                if (itemsToLoad.isNotEmpty()) {
                    val totalPosters = itemsToLoad.size
                    val posterMsg = preloaderContext.getString(R.string.loading_posters)
                    val maxConcurrency = budget.preloaderConcurrency

                    // Only show progress dialog if there are multiple uncached items to fetch
                    if (!isBackgroundUpdate && totalPosters > 2) {
                        val initialPercent = if (shouldFilter) 15 else 0
                        _contentFilterProgress.postValue(
                            ContentFilterProgress(
                                isFiltering = true,
                                current = initialPercent,
                                total = 100,
                                message = "$posterMsg (0/$totalPosters)"
                            )
                        )
                    }

                    var postersProcessed = 0
                    coroutineScope {
                        val channel = Channel<PosterPreloadTarget>(Channel.UNLIMITED)
                        itemsToLoad.forEach { channel.trySend(it) }
                        channel.close()

                        val workers = (0 until maxConcurrency).map {
                            async(Dispatchers.IO) {
                                for (item in channel) {
                                    val isViewportItem = viewportTargets.any { it.posterUrl == item.posterUrl }

                                    try {
                                        val timeoutMs = if (budget.isLowEnd) 4000L else 6000L
                                        withTimeoutOrNull(timeoutMs) {
                                            val request = ImageRequest.Builder(preloaderContext)
                                                .data(item.posterUrl)
                                                .memoryCacheKey(item.posterUrl)
                                                .diskCacheKey(item.posterUrl)
                                                .size(width = posterWidthPx, height = posterHeightPx)
                                                .httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
                                                    headerBuilder["User-Agent"] = USER_AGENT
                                                    item.posterHeaders?.forEach { (key, value) ->
                                                        headerBuilder[key] = value
                                                    }
                                                }.build())
                                                .allowHardware(budget.allowHardwareBitmaps)
                                                .bitmapConfig(if (budget.useRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                                                .diskCachePolicy(CachePolicy.ENABLED)
                                                .memoryCachePolicy(CachePolicy.ENABLED)
                                                .build()

                                            val result = imageLoader.execute(request)
                                            if (result is SuccessResult) {
                                                val drawable = result.image.asDrawable(preloaderContext.resources)
                                                if (shouldFilter && !NsfwFilterEngine.isCleanTitleAllowed(item.name)) {
                                                    val cachedScore = com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.getCachedScore(item.posterUrl, preloaderContext)
                                                    val score = cachedScore ?: com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.classifyDrawableScore(preloaderContext, drawable)
                                                    com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.recordScore(item.posterUrl, score, preloaderContext)
                                                    if (!nsfwEnabled && score >= threshold) {
                                                        nsfwUrlsToRemove.add(item.url)
                                                    }
                                                } else {
                                                    com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.recordScore(item.posterUrl, 0.0f, preloaderContext)
                                                }
                                            } else {
                                                com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.recordScore(item.posterUrl, 0.0f, preloaderContext)
                                            }
                                        }
                                    } catch (_: Throwable) {
                                        com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.recordScore(item.posterUrl, 0.0f, preloaderContext)
                                    }

                                    synchronized(itemsToLoad) {
                                        postersProcessed++
                                    }
                                    if (!isBackgroundUpdate && totalPosters > 2) {
                                        val posterPercent = if (shouldFilter) {
                                            (15 + (postersProcessed * 85 / totalPosters)).coerceIn(15, 100)
                                        } else {
                                            (postersProcessed * 100 / totalPosters).coerceIn(0, 100)
                                        }
                                        _contentFilterProgress.postValue(
                                            ContentFilterProgress(
                                                isFiltering = true,
                                                current = posterPercent,
                                                total = 100,
                                                message = "$posterMsg ($postersProcessed/$totalPosters)"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        workers.awaitAll()
                    }
                }

                // Remove any items identified as NSFW during visual inspection BEFORE submitList
                if (nsfwUrlsToRemove.isNotEmpty()) {
                    newExpandable.keys.toList().forEach { key ->
                        val row = newExpandable[key] ?: return@forEach
                        val cleanedList = row.list.list.filterNot { nsfwUrlsToRemove.contains(it.url) }
                        newExpandable[key] = row.copy(list = row.list.copy(list = CopyOnWriteArrayList(cleanedList)))
                    }
                }
            }

            if (!isBackgroundUpdate) {
                _contentFilterProgress.postValue(
                    ContentFilterProgress(
                        isFiltering = true,
                        current = 100,
                        total = 100,
                        message = context?.getString(R.string.loading) ?: "Loading"
                    )
                )
            }

            // Atomically update expandable cache with new data
            synchronized(expandable) {
                expandable.clear()
                expandable.putAll(newExpandable)
            }

            // Render home sections with posters already loaded, safe, and cached
            _page.postValue(Resource.Success(HashMap(newExpandable)))
            if (preloaderContext != null) {
                com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.recordSessionHeartbeat(
                    preloaderContext,
                    stage = "HomeRendered: ${_apiName.value ?: "None"}",
                    pluginName = _apiName.value ?: PluginManager.currentlyLoading ?: "None",
                    isForeground = true
                )
            }

            // Phase 3: Hardware & Kernel Accelerated Background Offscreen Prefetcher
            // Pre-caches offscreen posters directly into flash DiskCache with MemoryCache DISABLED (0 MB JVM heap footprint)
            // Ensures 100% of offscreen rails are primed in flash DiskCache before the user scrolls down!
            if (budget?.isLowEnd == true && uncachedOffscreenItems.isNotEmpty() && preloaderContext != null && imageLoader != null) {
                ioSafe {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        val offscreenChannel = Channel<PosterPreloadTarget>(Channel.UNLIMITED)
                        uncachedOffscreenItems.forEach { offscreenChannel.trySend(it) }
                        offscreenChannel.close()

                        val offscreenWorkers = (0 until minOf(budget.preloaderConcurrency, 4)).map {
                            async {
                                for (item in offscreenChannel) {
                                    try {
                                        val req = ImageRequest.Builder(preloaderContext)
                                            .data(item.posterUrl)
                                            .memoryCacheKey(item.posterUrl)
                                            .diskCacheKey(item.posterUrl)
                                            .size(width = posterWidthPx, height = posterHeightPx)
                                            .httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
                                                headerBuilder["User-Agent"] = USER_AGENT
                                                item.posterHeaders?.forEach { (key, value) ->
                                                    headerBuilder[key] = value
                                                }
                                            }.build())
                                            .allowHardware(false)
                                            .bitmapConfig(Bitmap.Config.RGB_565)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .memoryCachePolicy(CachePolicy.DISABLED) // NEVER evict visible viewport!
                                            .build()

                                        val res = imageLoader.execute(req)
                                        if (res is SuccessResult && shouldFilter && !NsfwFilterEngine.isCleanTitleAllowed(item.name)) {
                                            val d = res.image.asDrawable(preloaderContext.resources)
                                            val s = com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.classifyDrawableScore(preloaderContext, d)
                                            com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.recordScore(item.posterUrl, s, preloaderContext)
                                            if (!nsfwEnabled && s >= threshold) {
                                                pruneOffscreenNsfwItem(item.url)
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                        offscreenWorkers.awaitAll()
                        com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.flushScoresToDisk(preloaderContext)
                        // Post-prefetch inference reclamation for low-end hardware
                        com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.release()
                    } catch (_: Throwable) {}
                }
            } else if (preloaderContext != null) {
                if (budget?.isLowEnd == true) {
                    // 1. Immediately persist all evaluated scores to flash storage
                    com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.flushScoresToDisk(preloaderContext)
                    // 2. Release idle TFLite inference sessions and scratch buffers to free native heap for UI rendering on budget hardware
                    com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.release()
                } else {
                    // Persist evaluated scores to flash storage
                    com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.flushScoresToDisk(preloaderContext)
                }
            }

            if (initialPreviews.isNotEmpty()) {
                previewResponses.clear()
                previewResponses.addAll(initialPreviews)
                _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))

                // Asynchronously enrich with full metadata in background without stalling home screen
                addJob?.cancel()
                addJob = ioSafe {
                    updatePreviewResponses(
                        previewResponses,
                        previewResponsesAdded,
                        randomItems,
                        3
                    )
                    if (previewResponses.isNotEmpty()) {
                        _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
                    }
                }
            }

            if (previewResponses.isEmpty()) {
                _preview.postValue(
                    Resource.Failure(
                        false,
                        "No homepage responses"
                    )
                )
            }
        } catch (e: Exception) {
            _randomItems.postValue(emptyList())
            _contentFilterProgress.postValue(null)
            _page.postValue(Resource.Failure(false, e.message ?: "Failed to load homepage"))
            logError(e)
        }
    }

    private fun pruneOffscreenNsfwItem(url: String) {
        synchronized(expandable) {
            var modified = false
            expandable.keys.toList().forEach { key ->
                val row = expandable[key] ?: return@forEach
                if (row.list.list.any { it.url == url }) {
                    val cleaned = row.list.list.filterNot { it.url == url }
                    expandable[key] = row.copy(list = row.list.copy(list = CopyOnWriteArrayList(cleaned)))
                    modified = true
                }
            }
            if (modified) {
                _page.postValue(Resource.Success(HashMap(expandable)))
            }
        }
    }

    private fun isHomeDataEqual(
        oldData: List<HomePageResponse?>?,
        newData: List<HomePageResponse?>?
    ): Boolean {
        if (oldData === newData) return true
        if (oldData == null || newData == null) return false
        if (oldData.size != newData.size) return false
        for (i in oldData.indices) {
            val oldItem = oldData[i]
            val newItem = newData[i]
            if (oldItem === newItem) continue
            if (oldItem == null || newItem == null) return false
            if (oldItem.hasNext != newItem.hasNext) return false
            if (oldItem.items.size != newItem.items.size) return false
            for (j in oldItem.items.indices) {
                val oldRow = oldItem.items[j]
                val newRow = newItem.items[j]
                if (oldRow.name != newRow.name || oldRow.isHorizontalImages != newRow.isHorizontalImages) return false
                if (oldRow.list.size != newRow.list.size) return false
                for (k in oldRow.list.indices) {
                    val oldSearch = oldRow.list[k]
                    val newSearch = newRow.list[k]
                    if (oldSearch.url != newSearch.url || oldSearch.name != newSearch.name || oldSearch.posterUrl != newSearch.posterUrl || oldSearch.type != newSearch.type) return false
                }
            }
        }
        return true
    }

    private fun load(api: MainAPI, forceReload: Boolean = false): Job = ioSafe {
        repo = APIRepository(api)

        _apiName.postValue(repo?.name)
        _randomItems.postValue(listOf())

        if (repo?.hasMainPage != true) {
            _page.postValue(Resource.Success(emptyMap()))
            _preview.postValue(Resource.Failure(false, "No homepage"))
            return@ioSafe
        }

        // Check for cached homepage data (stale-while-revalidate for instant cold/warm start)
        val cachedData = if (!forceReload) {
            APIRepository.getCachedMainPage(api.name, 1)
        } else null

        val hasCached = cachedData != null && cachedData.any { it != null }
        if (hasCached) {
            rawHomeData = cachedData
            populateHomeFromData(cachedData!!)
        } else {
            _page.postValue(Resource.Loading())
            _preview.postValue(Resource.Loading())
        }
        // cancel the current preview expand as that is no longer relevant
        addJob?.cancel()

        when (val data = repo?.getMainPage(1, null, forceReload)) {
            is Resource.Success -> {
                val isDataIdentical = hasCached && isHomeDataEqual(cachedData, data.value)
                rawHomeData = data.value
                if (!isDataIdentical) {
                    populateHomeFromData(data.value, isBackgroundUpdate = hasCached)
                }
            }

            is Resource.Failure -> {
                if (!hasCached) {
                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                    _page.postValue(data!!)
                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                    _preview.postValue(data!!)
                }
            }

            else -> Unit
        }
        isCurrentlyLoadingName = null
    }

    fun click(callback: SearchClickCallback) {
        if (callback.action != SEARCH_ACTION_FOCUSED) {
            SearchHelper.handleSearchClickCallback(callback)
        }
    }

    private val _popup = MutableLiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?>(null)
    val popup: LiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?> = _popup

    fun popup(list: ExpandableHomepageList?, deleteCallback: (() -> Unit)? = null) {
        if (list == null)
            _popup.postValue(null)
        else
            _popup.postValue(list to deleteCallback)
    }

    private fun bookmarksUpdated(unused: Boolean) {
        reloadStored()
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        loadAndCancel(DataStoreHelper.currentHomePage, forceReload)
    }

    private fun afterMainPluginsLoaded(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, false)
    }

    private fun reloadHome(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, true)
    }

    private fun reloadAccount(unused: Boolean = false) {
        _currentAccount.postValue(
            getCurrentAccount()
        )
    }

    init {
        MainActivity.bookmarksUpdatedEvent += ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent += ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent += ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent += ::reloadHome
        MainActivity.reloadAccountEvent += ::reloadAccount

        try {
            ioSafe {
                @OptIn(FlowPreview::class)
                NsfwFilterEngine.configVersion
                    .debounce(250L)
                    .collect { version ->
                        if (version > 0) {
                            rescanLoadedSources()
                        }
                    }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCleared() {
        MainActivity.bookmarksUpdatedEvent -= ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent -= ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent -= ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent -= ::reloadHome
        MainActivity.reloadAccountEvent -= ::reloadAccount
        super.onCleared()
    }

    fun queryTextSubmit(query: String) {
        QuickSearchFragment.pushSearch(
            query,
            repo?.name?.let { arrayOf(it) })
    }

    fun queryTextChange(newText: String) {
        // do nothing
    }

    fun loadStoredData() {
        val list = EnumSet.noneOf(WatchType::class.java)
        DataStoreHelper.homeBookmarkedList.map { WatchType.fromInternalId(it) }.let {
            list.addAll(it)
        }
        loadStoredData(list)
    }

    fun reloadStored() {
        loadResumeWatching()
        loadStoredData()
    }

    fun click(load: LoadClickCallback) {
        loadResult(load.response.url, load.response.apiName, load.response.name, load.action)
    }

    // only save the key if it is from UI, as we don't want internal functions changing the setting
    fun loadAndCancel(
        preferredApiName: String?,
        forceReload: Boolean = true,
        fromUI: Boolean = false
    ) =
        ioSafe {
            // Since plugins are loaded in stages this function can get called multiple times.
            // The issue with this is that the homepage may be fetched multiple times while the first request is loading
            val currentPage = page.value

            // if we don't need to reload and we have a valid homepage or currently loading the same thing then return
            val currentLoading = isCurrentlyLoadingName
            if (!forceReload && (currentPage is Resource.Success && currentPage.value.isNotEmpty() || (currentLoading != null && currentLoading == preferredApiName))) {
                return@ioSafe
            }

            val api = getApiFromNameNull(preferredApiName)
            if (preferredApiName == noneApi.name) {
                // just set to random
                if (fromUI) DataStoreHelper.currentHomePage = noneApi.name
                loadAndCancel(noneApi, forceReload)
            } else if (preferredApiName == randomApi.name) {
                // randomize the api, if none exist like if not loaded or not installed
                // then use nothing
                val validAPIs = context?.filterProviderByPreferredMedia()
                if (validAPIs.isNullOrEmpty()) {
                    loadAndCancel(noneApi, forceReload)
                } else {
                    val apiRandom = validAPIs.random()
                    loadAndCancel(apiRandom, forceReload)
                    if (fromUI) DataStoreHelper.currentHomePage = apiRandom.name
                }
            } else if (api == null) {
                // API is not found aka not loaded or removed, post the loading
                // progress if waiting for plugins, otherwise nothing
                if (PluginManager.loadedOnlinePlugins || PluginManager.isSafeMode()) {
                    loadAndCancel(noneApi, forceReload)
                } else {
                    _page.postValue(Resource.Loading())
                    if (preferredApiName != null)
                        _apiName.postValue(preferredApiName)
                }
            } else {
                val nsfwEnabled = context?.isNsfwEnabled() ?: false
                if (!nsfwEnabled && NsfwFilterEngine.isNsfwProvider(api)) {
                    loadAndCancel(noneApi, forceReload)
                } else {
                    // if the api is found, then set it to it and save key
                    if (fromUI) DataStoreHelper.currentHomePage = api.name
                    loadAndCancel(api, forceReload)
                }
            }
            reloadAccount()
        }
}