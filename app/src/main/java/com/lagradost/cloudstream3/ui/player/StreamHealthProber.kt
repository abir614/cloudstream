package com.lagradost.cloudstream3.ui.player

import android.util.Log
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fast-Start Mirror Health & Latency Prober.
 * Performs lightweight, zero-download asynchronous pre-flight checks (Range: bytes=0-0 or fast GET)
 * on candidate video links before sending them to ExoPlayer.
 *
 * Instantly eliminates dead host links (403, 404, 502, timeouts) in 150-300ms,
 * preventing ExoPlayer from hanging for 15+ seconds on broken servers.
 * Automatically picks the lowest-latency responsive mirror for instant zero-wait playback.
 */
object StreamHealthProber {
    private const val TAG = "StreamHealthProber"
    private const val PROBE_TIMEOUT_MS = 1500L

    data class ProbeResult(
        val link: VideoLink,
        val isAlive: Boolean,
        val latencyMs: Long,
        val statusCode: Int
    )

    data class ProbedSelection(
        val selected: VideoLink?,
        val deadLinks: List<VideoLink>
    )

    /**
     * Probes a single video link with a zero-download HTTP check.
     */
    suspend fun probe(videoLink: VideoLink, timeoutMs: Long = PROBE_TIMEOUT_MS): ProbeResult =
        withContext(Dispatchers.IO) {
            val extractorLink = videoLink.first
            // If offline, torrent, or local uri, treat as immediately alive
            if (extractorLink == null || videoLink.second != null) {
                return@withContext ProbeResult(videoLink, isAlive = true, latencyMs = 0L, statusCode = 200)
            }
            if (extractorLink.type == ExtractorLinkType.MAGNET || extractorLink.type == ExtractorLinkType.TORRENT) {
                return@withContext ProbeResult(videoLink, isAlive = true, latencyMs = 0L, statusCode = 200)
            }

            val url = extractorLink.url
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return@withContext ProbeResult(videoLink, isAlive = true, latencyMs = 0L, statusCode = 200)
            }

            val startTime = System.currentTimeMillis()
            try {
                withTimeoutOrNull(timeoutMs) {
                    val provider = getApiFromNameNull(extractorLink.source)
                    val interceptor = provider?.getVideoInterceptor(extractorLink)

                    val clientBuilder = if (interceptor != null) {
                        app.baseClient.newBuilder().addInterceptor(interceptor)
                    } else {
                        app.baseClient.newBuilder()
                    }

                    val probeClient = clientBuilder
                        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .build()

                    val requestBuilder = Request.Builder().url(url)

                    // Forward headers and referer from extractor link
                    val headers = extractorLink.getAllHeaders()
                    headers.forEach { (k, v) ->
                        requestBuilder.header(k, v)
                    }
                    if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
                        requestBuilder.header("User-Agent", USER_AGENT)
                    }

                    // For direct video files, request 1 byte (bytes=0-0) to prevent bandwidth usage
                    if (extractorLink.type == ExtractorLinkType.VIDEO) {
                        requestBuilder.header("Range", "bytes=0-0")
                    }

                    val call = probeClient.newCall(requestBuilder.build())
                    val response = call.execute()
                    val latency = System.currentTimeMillis() - startTime
                    val code = response.code

                    // Close immediately without reading body
                    response.close()

                    val isAlive = code in 200..399
                    Log.d(TAG, "Probed ${extractorLink.name} (${extractorLink.url}): code=$code, latency=${latency}ms, alive=$isAlive")
                    ProbeResult(videoLink, isAlive, latency, code)
                } ?: run {
                    val latency = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Probe timed out for ${extractorLink.name} (${extractorLink.url}) after ${latency}ms")
                    ProbeResult(videoLink, isAlive = false, latencyMs = latency, statusCode = 408)
                }
            } catch (t: Throwable) {
                val latency = System.currentTimeMillis() - startTime
                Log.d(TAG, "Probe failed for ${extractorLink.name} (${extractorLink.url}): ${t.message} (${latency}ms)")
                ProbeResult(videoLink, isAlive = false, latencyMs = latency, statusCode = -1)
            }
        }

    /**
     * Probes candidate links in parallel and returns the fastest living link along with a list of dead links.
     */
    suspend fun selectBestLinkWithDeadList(
        candidates: List<VideoLink>,
        maxProbes: Int = 3,
        timeoutMs: Long = PROBE_TIMEOUT_MS
    ): ProbedSelection = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) {
            return@withContext ProbedSelection(null, emptyList())
        }
        if (candidates.size == 1) {
            return@withContext ProbedSelection(candidates.first(), emptyList())
        }

        val toProbe = candidates.take(maxProbes)
        val deferredResults = toProbe.map { link ->
            async { probe(link, timeoutMs) }
        }

        val results = deferredResults.awaitAll()

        // Identify definitively dead links (HTTP 4xx or 5xx error responses)
        val deadLinks = results.filter { !it.isAlive && it.statusCode in 400..599 }.map { it.link }

        // Find best responding link sorted by latency (lowest time-to-first-byte)
        val aliveResults = results.filter { it.isAlive }.sortedBy { it.latencyMs }
        val bestLink = aliveResults.firstOrNull()?.link ?: candidates.firstOrNull { !deadLinks.contains(it) } ?: candidates.first()

        Log.i(TAG, "Probed ${toProbe.size} links in parallel -> Winner: ${bestLink.first?.name ?: bestLink.first?.url}, Dead count: ${deadLinks.size}")
        ProbedSelection(bestLink, deadLinks)
    }
}
