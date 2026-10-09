package com.lagradost.cloudstream3.utils

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeyClass
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKeyClass
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
import com.lagradost.cloudstream3.ui.result.EpisodeSortType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.serializers.WriteOnlySerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

const val VIDEO_POS_DUR = "video_pos_dur"
const val VIDEO_WATCH_STATE = "video_watch_state"
const val RESULT_WATCH_STATE = "result_watch_state"
const val RESULT_WATCH_STATE_DATA = "result_watch_state_data"
const val RESULT_SUBSCRIBED_STATE_DATA = "result_subscribed_state_data"
const val RESULT_FAVORITES_STATE_DATA = "result_favorites_state_data"
const val RESULT_RESUME_WATCHING = "result_resume_watching_2" // Changed due to id changes
const val RESULT_RESUME_WATCHING_OLD = "result_resume_watching"
const val RESULT_RESUME_WATCHING_HAS_MIGRATED = "result_resume_watching_migrated"
const val RESULT_EPISODE = "result_episode"
const val RESULT_SEASON = "result_season"
const val RESULT_DUB = "result_dub"
const val KEY_RESULT_SORT = "result_sort"
const val USER_PINNED_PROVIDERS = "user_pinned_providers" // Key for pinned user set

class UserPreferenceDelegate<T : Any>(
    private val key: String,
    private val default: T,
) {
    private val klass: KClass<out T> = default::class
    private val realKey get() = "${DataStoreHelper.currentAccount}/$key"
    operator fun getValue(self: Any?, property: KProperty<*>) =
        getKeyClass(realKey, klass.java) ?: default

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?,
    ) {
        if (t == null) {
            removeKey(realKey)
        } else {
            setKeyClass(realKey, t)
        }
    }
}

object DataStoreHelper {
    // be aware, don't change the index of these as Account uses the index for the art
    val profileImages = arrayOf(
        R.drawable.profile_bg_dark_blue,
        R.drawable.profile_bg_blue,
        R.drawable.profile_bg_orange,
        R.drawable.profile_bg_pink,
        R.drawable.profile_bg_purple,
        R.drawable.profile_bg_red,
        R.drawable.profile_bg_teal,
    )

    private var searchPreferenceProvidersStrings: List<String> by UserPreferenceDelegate(
        /** java moment right here, as listOf()::class.java != List(0) { "" }::class.java */
        "search_pref_providers", List(0) { "" }
    )

    private fun serializeTv(data: List<TvType>): List<String> = data.map { it.name }

    private fun deserializeTv(data: List<String>): List<TvType> {
        return data.mapNotNull { listName ->
            TvType.values().firstOrNull { it.name == listName }
        }
    }

    var searchPreferenceProviders: List<String>
        get() {
            val ret = searchPreferenceProvidersStrings
            return ret.ifEmpty {
                context?.filterProviderByPreferredMedia()?.map { it.name } ?: emptyList()
            }
        }
        set(value) {
            searchPreferenceProvidersStrings = value
        }

    private var searchPreferenceTagsStrings: List<String> by UserPreferenceDelegate(
        "search_pref_tags",
        listOf(TvType.Movie, TvType.TvSeries).map { it.name })

    var searchPreferenceTags: List<TvType>
        get() = deserializeTv(searchPreferenceTagsStrings)
        set(value) {
            searchPreferenceTagsStrings = serializeTv(value)
        }

    private var homePreferenceStrings: List<String> by UserPreferenceDelegate(
        "home_pref_homepage",
        listOf(TvType.Movie, TvType.TvSeries).map { it.name })

    var homePreference: List<TvType>
        get() = deserializeTv(homePreferenceStrings)
        set(value) {
            homePreferenceStrings = serializeTv(value)
        }

    var homeBookmarkedList: IntArray by UserPreferenceDelegate(
        "home_bookmarked_last_list",
        IntArray(0)
    )

    var playBackSpeed: Float by UserPreferenceDelegate("playback_speed", 1.0f)
    var resizeMode: Int by UserPreferenceDelegate("resize_mode", 0)
    var librarySortingMode: Int by UserPreferenceDelegate(
        "library_sorting_mode",
        ListSorting.AlphabeticalA.ordinal
    )

    private var _resultsSortingMode: Int by UserPreferenceDelegate(
        "results_sorting_mode",
        EpisodeSortType.NUMBER_ASC.ordinal
    )

    var resultsSortingMode: EpisodeSortType
        get() = EpisodeSortType.entries.getOrNull(_resultsSortingMode) ?: EpisodeSortType.NUMBER_ASC
        set(value) {
            _resultsSortingMode = value.ordinal
        }

    @Serializable
    data class Account(
        @JsonProperty("keyIndex") @SerialName("keyIndex") val keyIndex: Int,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("customImage") @SerialName("customImage") val customImage: String? = null,
        @JsonProperty("defaultImageIndex") @SerialName("defaultImageIndex") val defaultImageIndex: Int,
        @JsonProperty("lockPin") @SerialName("lockPin") val lockPin: String? = null,
    ) {
        @get:JsonIgnore
        val image get() = customImage?.let { UiImage.Image(it) } ?:
            profileImages.getOrNull(defaultImageIndex)?.let {
                UiImage.Drawable(it)
            } ?: UiImage.Drawable(profileImages.first())
    }

    const val TAG = "data_store_helper"
    private val guestAccount = Account(
        keyIndex = 0,
        name = "Guest",
        defaultImageIndex = 0,
    )
    var accounts: Array<Account>
        get() = arrayOf(guestAccount)
        set(_) {}
    var selectedKeyIndex: Int
        get() = 0
        set(_) {}
    val currentAccount: String get() = "0"

    private val _selectedAccountNumberFlow = MutableStateFlow(0)
    /** What account instance we are on, this number changes whenever anything about local accounts changes */
    val selectedAccountNumberFlow : StateFlow<Int> = _selectedAccountNumberFlow

    /**
     * Get or set the current account homepage.
     * Setting this does not automatically reload the homepage.
     */
    var currentHomePage: String?
        get() = getKey<String>("$currentAccount/$USER_SELECTED_HOMEPAGE_API")
        set(value) {
            val key = "$currentAccount/$USER_SELECTED_HOMEPAGE_API"
            if (value == null) {
                removeKey(key)
            } else {
                setKey(key, value)
            }
        }

    fun setAccount(account: Account) {
        // Clean Core: Multi-account switching disabled (Guest mode only)
    }

    fun getDefaultAccount(context: Context): Account = guestAccount

    fun getAccounts(context: Context): List<Account> = listOf(guestAccount)

    /** Gets the current selected account (or default), may return null if context is null and the user is using the default account */
    fun getCurrentAccount(): Account? = guestAccount

    @Serializable
    data class PosDur(
        @JsonProperty("position") @SerialName("position") val position: Long,
        @JsonProperty("duration") @SerialName("duration") val duration: Long,
    )

    fun PosDur.fixVisual(): PosDur {
        if (duration <= 0) return PosDur(0, duration)
        val percentage = position * 100 / duration
        return when {
            percentage <= 1 -> PosDur(0, duration)
            percentage <= 5 -> PosDur(5 * duration / 100, duration)
            percentage >= 95 -> PosDur(duration, duration)
            else -> this
        }
    }

    fun Int.toYear(): Date =
        GregorianCalendar.getInstance().also { it.set(Calendar.YEAR, this) }.time

    /**
     * Used to display notifications on new episodes and posters in library.
     */
    @Serializable
    abstract class LibrarySearchResponse(
       /**
        * These fields are marked @Transient because this class is only ever serialized through
        * through its subclasses, which redeclare each property with their own @SerialName
        * annotations. Without @Transient here, kotlinx.serialization would try to
        * generate a serializer for the abstract base class itself (or double-serialize
        * these fields), which fails/conflicts since these are meant to be overridden,
        * not serialized directly from the parent.
        */
        @Transient override var id: Int? = null,
        @Transient open val latestUpdatedTime: Long = 0L,
        @Transient override val name: String = "",
        @Transient override val url: String = "",
        @Transient override val apiName: String = "",
        @Transient override var type: TvType? = null,
        @Transient override var posterUrl: String? = null,
        @Transient open val year: Int? = null,
        @Transient open val syncData: Map<String, String>? = null,
        @Transient override var quality: SearchQuality? = null,
        @Transient override var posterHeaders: Map<String, String>? = null,
        @Transient open val plot: String? = null,
        @Transient override var score: Score? = null,
        @Transient open val tags: List<String>? = null,
    ) : SearchResponse {
        @JsonProperty("rating", access = JsonProperty.Access.WRITE_ONLY)
        @SerialName("rating")
        @Deprecated(
            "`rating` is the old scoring system, use score instead",
            replaceWith = ReplaceWith("score"),
            level = DeprecationLevel.ERROR,
        )
        var rating: Int? = null
            set(value) {
                if (value != null) {
                    @Suppress("DEPRECATION_ERROR")
                    score = Score.fromOld(value)
                }
            }
    }

    @OptIn(ExperimentalSerializationApi::class) // KeepGeneratedSerializer is an experimental annotation for now
    @KeepGeneratedSerializer
    @Serializable(with = SubscribedData.Serializer::class)
    data class SubscribedData(
        @JsonProperty("subscribedTime") @SerialName("subscribedTime") val subscribedTime: Long,
        @JsonProperty("lastSeenEpisodeCount") @SerialName("lastSeenEpisodeCount") val lastSeenEpisodeCount: Map<DubStatus, Int?>,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id,
        latestUpdatedTime,
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        syncData,
        quality,
        posterHeaders,
        plot,
        score,
        tags,
    ) {
        object Serializer : WriteOnlySerializer<SubscribedData>(
            SubscribedData.generatedSerializer(),
            setOf("rating"),
        )

        fun toLibraryItem(): SyncAPI.LibraryItem? {
            return SyncAPI.LibraryItem(
                name,
                url,
                id?.toString() ?: return null,
                null,
                null,
                null,
                latestUpdatedTime,
                apiName,
                type,
                posterUrl,
                posterHeaders,
                quality,
                year?.toYear(),
                this.id,
                plot = this.plot,
                score = this.score,
                tags = this.tags,
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class) // KeepGeneratedSerializer is an experimental annotation for now
    @KeepGeneratedSerializer
    @Serializable(with = BookmarkedData.Serializer::class)
    data class BookmarkedData(
        @JsonProperty("bookmarkedTime") @SerialName("bookmarkedTime") val bookmarkedTime: Long,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id,
        latestUpdatedTime,
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        syncData,
        quality,
        posterHeaders,
        plot,
    ) {
        object Serializer : WriteOnlySerializer<BookmarkedData>(
            BookmarkedData.generatedSerializer(),
            setOf("rating"),
        )

        fun toLibraryItem(id: String): SyncAPI.LibraryItem {
            return SyncAPI.LibraryItem(
                name,
                url,
                id,
                null,
                null,
                null,
                latestUpdatedTime,
                apiName,
                type,
                posterUrl,
                posterHeaders,
                quality,
                year?.toYear(),
                this.id,
                plot = this.plot,
                score = this.score,
                tags = this.tags,
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class) // KeepGeneratedSerializer is an experimental annotation for now
    @KeepGeneratedSerializer
    @Serializable(with = FavoritesData.Serializer::class)
    data class FavoritesData(
        @JsonProperty("favoritesTime") @SerialName("favoritesTime") val favoritesTime: Long,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id,
        latestUpdatedTime,
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        syncData,
        quality,
        posterHeaders,
        plot,
    ) {
        object Serializer : WriteOnlySerializer<FavoritesData>(
            FavoritesData.generatedSerializer(),
            setOf("rating"),
        )

        fun toLibraryItem(): SyncAPI.LibraryItem? {
            return SyncAPI.LibraryItem(
                name,
                url,
                id?.toString() ?: return null,
                null,
                null,
                null,
                latestUpdatedTime,
                apiName,
                type,
                posterUrl,
                posterHeaders,
                quality,
                year?.toYear(),
                this.id,
                plot = this.plot,
                score = this.score,
                tags = this.tags,
            )
        }
    }

    @Serializable
    data class ResumeWatchingResult(
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType? = null,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("watchPos") @SerialName("watchPos") val watchPos: PosDur?,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("parentId") @SerialName("parentId") val parentId: Int?,
        @JsonProperty("episode") @SerialName("episode") val episode: Int?,
        @JsonProperty("season") @SerialName("season") val season: Int?,
        @JsonProperty("isFromDownload") @SerialName("isFromDownload") val isFromDownload: Boolean,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
    ) : SearchResponse

    /**
     * A datastore wide account for future implementations of a multiple account system
     */

    fun getAllWatchStateIds(): List<Int>? {
        val folder = "$currentAccount/$RESULT_WATCH_STATE"
        return getKeys(folder)?.mapNotNull {
            it.removePrefix("$folder/").toIntOrNull()
        }
    }

    // Ephemeral in-memory session caches for active playback (zero disk writes, zero storage bloat)
    private val sessionPosDur = java.util.concurrent.ConcurrentHashMap<Int, PosDur>()
    private val sessionWatchState = java.util.concurrent.ConcurrentHashMap<Int, VideoWatchState>()

    fun getAllWatchStateIds(): List<Int>? = emptyList()

    fun deleteAllResumeStateIds() {
        sessionPosDur.clear()
        sessionWatchState.clear()
    }

    fun deleteBookmarkedData(id: Int?) {}

    fun getAllResumeStateIds(): List<Int>? = emptyList()

    private fun getAllResumeStateIdsOld(): List<Int>? = emptyList()

    fun migrateResumeWatching() {}

    fun setLastWatched(
        parentId: Int?,
        episodeId: Int?,
        episode: Int?,
        season: Int?,
        isFromDownload: Boolean = false,
        updateTime: Long? = null,
    ) {
        // Clean Core: Persistent watch history disabled
    }

    private fun removeLastWatchedOld(parentId: Int?) {}

    fun removeLastWatched(parentId: Int?) {}

    fun getLastWatched(id: Int?): DownloadObjects.ResumeWatching? = null

    private fun getLastWatchedOld(id: Int?): DownloadObjects.ResumeWatching? = null

    fun setBookmarkedData(id: Int?, data: BookmarkedData) {}

    fun getBookmarkedData(id: Int?): BookmarkedData? = null

    fun getAllBookmarkedData(): List<BookmarkedData> = emptyList()

    fun getAllSubscriptions(): List<SubscribedData> = emptyList()

    fun removeSubscribedData(id: Int?) {}

    /**
     * Set new seen episodes and update time
     */
    fun updateSubscribedData(id: Int?, data: SubscribedData?, episodeResponse: EpisodeResponse?) {}

    fun setSubscribedData(id: Int?, data: SubscribedData) {}

    fun getSubscribedData(id: Int?): SubscribedData? = null

    fun getAllFavorites(): List<FavoritesData> = emptyList()

    fun removeFavoritesData(id: Int?) {}

    fun setFavoritesData(id: Int?, data: FavoritesData) {}

    fun getFavoritesData(id: Int?): FavoritesData? = null

    fun setViewPos(id: Int?, pos: Long, dur: Long) {
        if (id == null || dur < 30_000) return
        sessionPosDur[id] = PosDur(pos, dur)
    }

    /**
     * Sets the position, duration, and resume data of an episode/movie in memory only
     */
    fun setViewPosAndResume(id: Int?, position: Long, duration: Long, currentEpisode: Any?, nextEpisode: Any?) {
        setViewPos(id, position, duration)
        if (id != null) {
            when (val meta = currentEpisode) {
                is ResultEpisode -> {
                    if (meta.videoWatchState == VideoWatchState.Watched) {
                        setVideoWatchState(id, VideoWatchState.None)
                    }
                }
            }
        }
    }

    fun getViewPos(id: Int?): PosDur? {
        if (id == null) return null
        return sessionPosDur[id]
    }

    fun getVideoWatchState(id: Int?): VideoWatchState? {
        if (id == null) return null
        return sessionWatchState[id]
    }

    fun setVideoWatchState(id: Int?, watchState: VideoWatchState) {
        if (id == null) return
        if (watchState == VideoWatchState.None) {
            sessionWatchState.remove(id)
        } else {
            sessionWatchState[id] = watchState
        }
    }

    fun getDub(id: Int): DubStatus? = null

    fun setDub(id: Int, status: DubStatus) {}

    fun setResultWatchState(id: Int?, status: Int) {}

    fun getResultWatchState(id: Int): WatchType = WatchType.NONE

    fun getResultSeason(id: Int): Int? = null

    fun setResultSeason(id: Int, value: Int?) {}

    fun getResultEpisode(id: Int): Int? = null

    fun setResultEpisode(id: Int, value: Int?) {}

    fun addSync(id: Int, idPrefix: String, url: String) {}

    fun getSync(id: Int, idPrefixes: List<String>): List<String?> = emptyList()

    var pinnedProviders: Array<String>
        get() = getKey<Array<String>>(USER_PINNED_PROVIDERS) ?: emptyArray<String>()
        set(value) = setKey(USER_PINNED_PROVIDERS, value)
}
