package com.lagradost.cloudstream3.domain.usecase

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

/**
 * Domain UseCase that handles stream link prioritization, mirror resolution, and failover routing.
 * Decouples stream selection algorithms and quality ranking from player controllers.
 */
class StreamResolutionUseCase(
    private val securityUseCase: StreamSecurityUseCase = StreamSecurityUseCase()
) {

    /**
     * Filters out stream URLs that violate security policies (private IPs, loopbacks, SSRF risks).
     */
    fun filterSafeStreams(links: List<ExtractorLink>): List<ExtractorLink> {
        return links.filter { link ->
            securityUseCase.isSafeFallback(link.url)
        }
    }

    /**
     * Prioritizes and sorts stream links according to preferred resolution, format, and source diversity.
     *
     * Sorting criteria:
     * 1. Closeness to preferred resolution
     * 2. Highest absolute resolution
     * 3. M3U8/HLS preference if specified
     */
    fun prioritizeStreams(
        links: List<ExtractorLink>,
        preferredQuality: Int = Qualities.P1080.value,
        preferHls: Boolean = true
    ): List<ExtractorLink> {
        val safeLinks = filterSafeStreams(links)
        if (safeLinks.isEmpty()) return emptyList()

        return safeLinks.distinctBy { it.url }.sortedWith { a, b ->
            // Quality closeness score (lower difference is better)
            val diffA = kotlin.math.abs(a.quality - preferredQuality)
            val diffB = kotlin.math.abs(b.quality - preferredQuality)

            if (diffA != diffB) {
                diffA.compareTo(diffB)
            } else if (preferHls && a.isM3u8 != b.isM3u8) {
                // Prefer M3U8/HLS adaptive streams if requested
                if (a.isM3u8) -1 else 1
            } else {
                // Secondary sort: higher resolution first
                b.quality.compareTo(a.quality)
            }
        }
    }

    /**
     * Selects the single best mirror candidate from the available links.
     */
    fun selectBestMirror(
        links: List<ExtractorLink>,
        preferredQuality: Int = Qualities.P1080.value,
        preferHls: Boolean = true
    ): ExtractorLink? {
        return prioritizeStreams(links, preferredQuality, preferHls).firstOrNull()
    }

    /**
     * Finds the next best failover candidate when a currently playing stream URL fails.
     */
    fun resolveFailoverCandidate(
        links: List<ExtractorLink>,
        failedUrl: String,
        preferredQuality: Int = Qualities.P1080.value
    ): ExtractorLink? {
        val remaining = links.filter { it.url != failedUrl }
        return prioritizeStreams(remaining, preferredQuality).firstOrNull()
    }
}
