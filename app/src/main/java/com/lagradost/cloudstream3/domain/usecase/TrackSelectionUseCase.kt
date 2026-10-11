package com.lagradost.cloudstream3.domain.usecase

import com.lagradost.cloudstream3.ui.player.AudioTrack
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin

/**
 * Domain UseCase that orchestrates audio and subtitle track selection.
 * Decouples selection rules, language matching, and priority weighting from ExoPlayer and UI state.
 */
class TrackSelectionUseCase {

    /**
     * Selects the optimal subtitle from available subtitle tracks based on user preferences.
     *
     * Priority Hierarchy:
     * 1. Preferred languages (in declared user preference order)
     * 2. Origin priority: DOWNLOADED_FILE > URL > EMBEDDED_IN_VIDEO
     * 3. Optional fallback to English ("en") if allowed and no preferred language matched
     */
    fun selectBestSubtitle(
        availableSubtitles: Collection<SubtitleData>,
        preferredLanguages: List<String>,
        allowEnglishFallback: Boolean = false
    ): SubtitleData? {
        if (availableSubtitles.isEmpty()) return null

        // 1. Iterate through user preferences in priority order
        for (prefLang in preferredLanguages) {
            val cleanPref = prefLang.trim().lowercase()
            if (cleanPref.isBlank()) continue

            val candidates = availableSubtitles.filter { sub ->
                matchesLanguage(sub, cleanPref)
            }

            if (candidates.isNotEmpty()) {
                return rankSubtitleCandidates(candidates)
            }
        }

        // 2. English fallback if user has not explicitly configured it but enabled fallback
        if (allowEnglishFallback && !preferredLanguages.any { it.trim().lowercase().startsWith("en") }) {
            val englishCandidates = availableSubtitles.filter { sub ->
                matchesLanguage(sub, "en")
            }
            if (englishCandidates.isNotEmpty()) {
                return rankSubtitleCandidates(englishCandidates)
            }
        }

        return null
    }

    /**
     * Checks if a SubtitleData item matches a given language query (tag, code, or name).
     */
    fun matchesLanguage(subtitle: SubtitleData, targetLang: String): Boolean {
        val target = targetLang.trim().lowercase()
        val ietf = subtitle.getIETF_tag()?.lowercase()
        if (ietf != null) {
            if (ietf == target || ietf.startsWith("$target-") || target.startsWith("$ietf-")) {
                return true
            }
        }

        val langCode = subtitle.languageCode?.lowercase()
        if (langCode != null && (langCode == target || langCode.startsWith(target))) {
            return true
        }

        val origName = subtitle.originalName.lowercase()
        return origName.contains(target)
    }

    /**
     * Ranks candidates of the same language by subtitle origin.
     * Local downloaded files take precedence over remote URLs, which take precedence over embedded tracks.
     */
    fun rankSubtitleCandidates(candidates: List<SubtitleData>): SubtitleData {
        return candidates.minByOrNull { sub ->
            when (sub.origin) {
                SubtitleOrigin.DOWNLOADED_FILE -> 0
                SubtitleOrigin.URL -> 1
                SubtitleOrigin.EMBEDDED_IN_VIDEO -> 2
            }
        } ?: candidates.first()
    }

    /**
     * Filters available subtitles matching a user text query (for player search UI).
     */
    fun filterSubtitles(subtitles: Collection<SubtitleData>, query: String): List<SubtitleData> {
        if (query.isBlank()) return subtitles.toList()
        val cleanQuery = query.trim().lowercase()
        return subtitles.filter { sub ->
            sub.name.lowercase().contains(cleanQuery) ||
                    (sub.languageCode?.lowercase()?.contains(cleanQuery) == true) ||
                    (sub.getIETF_tag()?.lowercase()?.contains(cleanQuery) == true)
        }
    }

    /**
     * Selects the best audio track based on user's preferred audio language and channel quality.
     */
    fun selectBestAudioTrack(
        availableAudioTracks: List<AudioTrack>,
        preferredLanguages: List<String>
    ): AudioTrack? {
        if (availableAudioTracks.isEmpty()) return null

        for (prefLang in preferredLanguages) {
            val clean = prefLang.trim().lowercase()
            if (clean.isBlank()) continue

            val matching = availableAudioTracks.filter { track ->
                val lang = track.language?.trim()?.lowercase()
                lang != null && (lang == clean || lang.startsWith("$clean-") || clean.startsWith("$lang-"))
            }

            if (matching.isNotEmpty()) {
                // Prefer higher channel count (e.g. 5.1/7.1 over stereo)
                return matching.maxByOrNull { it.channelCount ?: 2 } ?: matching.first()
            }
        }

        return availableAudioTracks.firstOrNull()
    }
}
