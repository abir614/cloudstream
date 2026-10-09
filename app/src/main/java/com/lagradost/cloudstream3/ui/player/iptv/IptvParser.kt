package com.lagradost.cloudstream3.ui.player.iptv

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

data class IptvChannel(
    val name: String,
    val streamUrl: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
) {
    fun toExtractorLink(sourceName: String = "IPTV"): ExtractorLink {
        val lower = streamUrl.lowercase()
        val isM3u8 = lower.contains(".m3u8")
        val isDash = lower.contains(".mpd")
        val linkType = when {
            isM3u8 -> ExtractorLinkType.M3U8
            isDash -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        return ExtractorLink(
            source = sourceName,
            name = name,
            url = streamUrl,
            referer = "",
            quality = Qualities.Unknown.value,
            type = linkType
        )
    }
}

/**
 * Ultra-fast, zero-overhead IPTV M3U/M3U8 parser native to Kotlin.
 * Seamlessly blends IPTV streams with CloudStream's minimal core video player.
 */
object IptvParser {
    fun isIptvPlaylist(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#EXTINF")
    }

    fun isIptvStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".m3u8") ||
               lower.endsWith(".ts") ||
               lower.contains(".m3u8?") ||
               lower.contains(".ts?") ||
               lower.startsWith("rtsp://") ||
               lower.startsWith("rtmp://")
    }

    fun parse(content: String): List<IptvChannel> {
        val channels = ArrayList<IptvChannel>()
        var currentName: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var currentTvgId: String? = null

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val infoPart = line.substring(8)
                val commaIdx = infoPart.lastIndexOf(',')
                if (commaIdx != -1) {
                    currentName = infoPart.substring(commaIdx + 1).trim()
                    val attrsPart = infoPart.substring(0, commaIdx)
                    currentLogo = extractAttribute(attrsPart, "tvg-logo")
                    currentGroup = extractAttribute(attrsPart, "group-title")
                    currentTvgId = extractAttribute(attrsPart, "tvg-id")
                } else {
                    currentName = infoPart.trim()
                }
            } else if (line.startsWith('#')) {
                // Ignore metadata headers like #EXTM3U, #EXTVLCOPT
            } else if (line.startsWith("http://", ignoreCase = true) ||
                       line.startsWith("https://", ignoreCase = true) ||
                       line.startsWith("rtmp://", ignoreCase = true) ||
                       line.startsWith("rtsp://", ignoreCase = true)) {
                val name = currentName ?: "Channel ${channels.size + 1}"
                channels.add(
                    IptvChannel(
                        name = name,
                        streamUrl = line,
                        logo = currentLogo,
                        group = currentGroup,
                        tvgId = currentTvgId,
                    )
                )
                currentName = null
                currentLogo = null
                currentGroup = null
                currentTvgId = null
            }
        }

        return channels
    }

    private fun extractAttribute(source: String, key: String): String? {
        val pattern = "$key=\""
        val start = source.indexOf(pattern, ignoreCase = true)
        if (start != -1) {
            val valStart = start + pattern.length
            val end = source.indexOf('"', valStart)
            if (end != -1) {
                return source.substring(valStart, end)
            }
        }
        return null
    }
}
