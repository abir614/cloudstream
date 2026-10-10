package com.lagradost.cloudstream3.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.Levenshtein
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap


data class ExpandableSearchList(
    var list: List<SearchResponse>, var currentPage: Int, var hasNext: Boolean,
)

const val SEARCH_HISTORY_KEY = "search_history"

class SearchViewModel : ViewModel() {
    private val _searchResponse: MutableLiveData<Resource<ExpandableSearchList>> =
        MutableLiveData()
    val searchResponse: LiveData<Resource<ExpandableSearchList>> get() = _searchResponse

    private val _currentSearch: MutableLiveData<Map<String, ExpandableSearchList>> =
        MutableLiveData()
    val currentSearch: LiveData<Map<String, ExpandableSearchList>> get() = _currentSearch

    private val _currentHistory: MutableLiveData<List<SearchHistoryItem>> = MutableLiveData()
    val currentHistory: LiveData<List<SearchHistoryItem>> get() = _currentHistory

    private val _searchSuggestions: MutableLiveData<List<String>> = MutableLiveData()
    val searchSuggestions: LiveData<List<String>> get() = _searchSuggestions

    private var suggestionJob: Job? = null

    private var repos = apis.withLock { apis.map { APIRepository(it) } }

    fun clearSearch() {
        _searchResponse.postValue(Resource.Success(ExpandableSearchList(emptyList(), 0, false)))
        _currentSearch.postValue(emptyMap())
        expandableSearches.clear()
    }

    var lastQuery: String? = null

    /** Save which providers can searched again and which search result page they are on.
     * Maps provider name to search list.
     * @see [HomeViewModel.expandable] */
    private val expandableSearches: MutableMap<String, ExpandableSearchList> = ConcurrentHashMap()

    private var currentSearchIndex = 0
    private var onGoingSearch: Job? = null

    fun reloadRepos() {
        repos = apis.withLock { apis.map { APIRepository(it) } }
    }

    fun searchAndCancel(
        query: String,
        providersActive: Set<String> = setOf(),
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) {
        currentSearchIndex++
        onGoingSearch?.cancel()
        onGoingSearch = search(query, providersActive, ignoreSettings, isQuickSearch)
    }

    fun updateHistory() = ioSafe {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: emptyList()
        _currentHistory.postValue(items)
    }

    /**
     * Fetches search suggestions with debouncing.
     * Waits 300ms before making the API call to avoid too many requests.
     * 
     * @param query The search query to get suggestions for
     */
    fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()
        
        if (query.isBlank() || query.length < 2) {
            _searchSuggestions.postValue(emptyList())
            return
        }
        
        suggestionJob = ioSafe {
            delay(300) // Debounce
            val suggestions = SearchSuggestionApi.getSuggestions(query)
            _searchSuggestions.postValue(suggestions)
        }
    }

    /**
     * Clears the current search suggestions.
     */
    fun clearSuggestions() {
        suggestionJob?.cancel()
        _searchSuggestions.postValue(emptyList())
    }

    private val lock: MutableSet<String> = mutableSetOf()

    // ExpandableHomepageList because the home adapter is reused in the search fragment
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? {
        if (lock.contains(name)) return null
        val query = lastQuery ?: return null
        val repo = repos.find { it.name == name } ?: return null

        lock += name

        expandableSearches[name]?.let { current ->
            debugAssert({ !current.hasNext }) {
                "Expand called when not needed"
            }

            val nextPage = current.currentPage + 1
            val next = repo.search(query, nextPage)
            if (next is Resource.Success) {
                val nextValue = next.value
                expandableSearches[name]?.apply {
                    this.hasNext = nextValue.hasNext
                    this.currentPage = nextPage

                    debugWarning({ nextValue.items.any { outer -> this.list.any { it.url == outer.url } } }) {
                        "Expanded search contained an item that was previously already in the list.\nQuery = $query, ${nextValue.items} = ${this.list}"
                    }

                    // just to be sure we are not adding the same shit for some reason
                    // Avoids weird behavior in the recyclerview by recreating the list
                    this.list = (this.list + nextValue.items).distinctBy { it.url }
                } ?: debugWarning {
                    "Expanded an item not in search load named $name, current list is ${expandableSearches.keys}"
                }
            } else {
                current.hasNext = false
            }

            _searchResponse.postValue(Resource.Success(bundleSearch(expandableSearches.toMap(), query)))
            _currentSearch.postValue(expandableSearches.toMap())
        }

        lock -= name

        val item = expandableSearches[name] ?: return null
        return HomeViewModel.ExpandableHomepageList(
            HomePageList(name, item.list),
            item.currentPage,
            item.hasNext
        )
    }

    private fun calculateRelevance(title: String, query: String): Int {
        val cleanTitle = title.trim().lowercase()
        val cleanQuery = query.trim().lowercase()
        if (cleanTitle == cleanQuery) return 1000

        val normTitle = cleanTitle.filter { it.isLetterOrDigit() }
        val normQuery = cleanQuery.filter { it.isLetterOrDigit() }
        if (normTitle.isNotEmpty() && normTitle == normQuery) return 950

        if (cleanTitle.startsWith(cleanQuery)) {
            val lengthRatio = (cleanQuery.length.toFloat() / cleanTitle.length.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            return 800 + (lengthRatio * 100).toInt()
        }

        if (cleanTitle.contains(cleanQuery)) {
            val lengthRatio = (cleanQuery.length.toFloat() / cleanTitle.length.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            return 600 + (lengthRatio * 100).toInt()
        }

        val queryWords = cleanQuery.split(" ").filter { it.isNotBlank() }
        if (queryWords.size > 1 && queryWords.all { cleanTitle.contains(it) }) {
            return 500
        }

        // Fuzzy similarity via Levenshtein (accelerated by native Rust core if loaded)
        val fuzzyScore = Levenshtein.ratio(cleanQuery, cleanTitle)
        return (fuzzyScore * 4).coerceAtMost(400)
    }

    private fun bundleSearch(
        lists: Map<String, ExpandableSearchList>,
        query: String? = lastQuery
    ): ExpandableSearchList {
        if (lists.isEmpty()) {
            return ExpandableSearchList(emptyList(), 1, false)
        }
        if (lists.size == 1 && query.isNullOrBlank()) {
            return lists.values.first()
        }

        val list = ArrayList<SearchResponse>()
        val nestedList = lists.values.map { it.list }

        // Interleave across providers to ensure fair provider distribution
        var index = 0
        while (true) {
            var added = 0
            for (sublist in nestedList) {
                if (sublist.size > index) {
                    list.add(sublist[index])
                    added++
                }
            }
            if (added == 0) break
            index++
        }

        if (query.isNullOrBlank()) {
            return ExpandableSearchList(list, 1, false)
        }

        // Rank by multi-tier relevance score descending, breaking ties by shorter title
        val sortedList = list.sortedWith(
            compareByDescending<SearchResponse> { calculateRelevance(it.name, query) }
                .thenBy { it.name.length }
        )

        return ExpandableSearchList(sortedList, 1, false)
    }

    private fun search(
        query: String,
        providersActive: Set<String>,
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) =
        viewModelScope.launchSafe {
            val currentIndex = currentSearchIndex
            if (query.length <= 1) {
                clearSearch()
                return@launchSafe
            }

            if (!isQuickSearch) {
                val key = query.hashCode().toString()
                setKey(
                    "$currentAccount/$SEARCH_HISTORY_KEY",
                    key,
                    SearchHistoryItem(
                        searchedAt = System.currentTimeMillis(),
                        searchText = query,
                        type = emptyList(), // TODO implement tv type
                        key = key,
                    )
                )
            }

            _searchResponse.postValue(Resource.Loading())
            _currentSearch.postValue(emptyMap())
            expandableSearches.clear()

            lastQuery = query

            withContext(Dispatchers.IO) { // This interrupts UI otherwise
                // Adaptive hardware concurrency gate based on available device JVM heap RAM
                val maxConcurrent = when {
                    Runtime.getRuntime().maxMemory() <= 192 * 1024 * 1024L -> 4 // <= 192MB JVM heap (1GB RAM potato devices)
                    Runtime.getRuntime().maxMemory() <= 384 * 1024 * 1024L -> 6 // mid-tier TV sticks / phones
                    else -> 10 // modern high-end devices
                }
                val semaphore = Semaphore(maxConcurrent)

                val targets = repos.filter { a ->
                    (ignoreSettings || (providersActive.isEmpty() || providersActive.contains(a.name))) && (!isQuickSearch || a.hasQuickSearch)
                }

                var lastProgressivePostMs = 0L

                coroutineScope {
                    targets.map { a ->
                        async {
                            semaphore.withPermit {
                                if (currentSearchIndex != currentIndex || !isActive) return@async
                                val search = if (isQuickSearch) a.quickSearch(query) else a.search(query, 1)
                                if (currentSearchIndex != currentIndex || !isActive) return@async
                                if (search is Resource.Success) {
                                    val searchValue = search.value
                                    expandableSearches[a.name] =
                                        ExpandableSearchList(searchValue.items, 1, searchValue.hasNext)

                                    val snapshot = expandableSearches.toMap()
                                    _currentSearch.postValue(snapshot)

                                    // Progressive batch update for search grid (debounced every 350ms)
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressivePostMs >= 350L) {
                                        lastProgressivePostMs = now
                                        val partialList = bundleSearch(snapshot, query)
                                        _searchResponse.postValue(Resource.Success(partialList))
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }

                if (currentSearchIndex != currentIndex) return@withContext // this should prevent rewrite of existing data bug

                val finalSnapshot = expandableSearches.toMap()
                _currentSearch.postValue(finalSnapshot)
                val finalList = bundleSearch(finalSnapshot, query)

                _searchResponse.postValue(Resource.Success(finalList))
            }
        }
}
