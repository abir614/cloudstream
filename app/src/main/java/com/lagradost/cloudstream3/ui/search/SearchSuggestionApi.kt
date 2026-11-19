package com.lagradost.cloudstream3.ui.search

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppContextUtils.isNsfwGloballyEnabled
import com.lagradost.nicehttp.NiceResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API for fetching search suggestions from external sources.
 * Uses TheMovieDB API to provide movie/show/anime related suggestions.
 */
object SearchSuggestionApi {
    private const val TMDB_API_URL = "https://api.themoviedb.org/3/search/multi"
    private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    
    @Serializable
    data class TmdbSearchResult(
        @JsonProperty("results") @SerialName("results") val results: List<TmdbSearchItem>?,
    )
    
    @Serializable
    data class TmdbSearchItem(
        @JsonProperty("media_type") @SerialName("media_type") val mediaType: String?,
        @JsonProperty("title") @SerialName("title") val title: String?,
        @JsonProperty("name") @SerialName("name") val name: String?,
        @JsonProperty("original_title") @SerialName("original_title") val originalTitle: String?,
        @JsonProperty("original_name") @SerialName("original_name") val originalName: String?,
    )
    
    /**
     * Fetches search suggestions from TheMovieDB multi search API.
     * Returns suggestions for movies, TV series, and anime.
     * 
     * @param query The search query to get suggestions for
     * @return List of suggestion strings, empty list on failure
     */
    suspend fun getSuggestions(query: String): List<String> {
        if (query.isBlank() || query.length < 2) return emptyList()
        val nsfwEnabled = isNsfwGloballyEnabled()
        
        return try {
            val response = app.get(
                TMDB_API_URL,
                params = mapOf(
                    "api_key" to TMDB_API_KEY,
                    "query" to query,
                    "language" to "en-US",
                    "include_adult" to if (nsfwEnabled) "true" else "false"
                ),
                cacheTime = 60 * 24  // Cache for 1 day (cacheUnit default is Minutes)
            )
            
            parseSuggestions(response, nsfwEnabled)
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }
    
    /**
     * Parses the TMDB search response and extracts movie/TV show titles.
     * Filters to only include movies, TV shows, and anime, screening against adult terms.
     */
    private suspend fun parseSuggestions(response: NiceResponse, nsfwEnabled: Boolean = isNsfwGloballyEnabled()): List<String> {
        return try {
            val filterLevel = NsfwFilterEngine.getFilterLevel(CloudStreamApp.context)
            val parsed = response.parsed<TmdbSearchResult>()
            parsed.results
                ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
                ?.mapNotNull { it.title ?: it.name }
                ?.filter { title -> nsfwEnabled || !NsfwFilterEngine.isNsfwText(title, level = filterLevel) }
                ?.distinct()
                ?.take(10)
                ?: emptyList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }
}
