// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

open class TRsTX : ExtractorApi() {
    override val name = "TRsTX"
    override val mainUrl = "https://trstx.org"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val extRef = referer ?: ""
        val videoReq = app.get(url, referer = extRef).text
        val file = Regex("""file\":\"([^\"]+)""").find(videoReq)?.groupValues?.getOrNull(1) ?: return
        val postLink = "$mainUrl/" + file.replace("\\", "")
        val postText = app.post(postLink, referer = extRef).text
        val postJson: List<TrstxVideoData> = tryParseJson<List<TrstxVideoData>>(postText)?.drop(1)
            ?: (tryParseJson<List<Map<String, String>>>(postText)?.drop(1)?.mapNotNull { mapItem ->
                TrstxVideoData(
                    title = mapItem["title"],
                    file = mapItem["file"],
                )
            } ?: return)

        val vidLinks = mutableSetOf<String>()
        val vidMap = mutableListOf<Map<String, String>>()
        for (item in postJson) {
            if (item.file == null || item.title == null) continue
            val fileUrl = "$mainUrl/playlist/" + item.file.substring(1) + ".txt"
            val videoData = app.post(fileUrl, referer = extRef).text
            if (videoData in vidLinks) continue
            vidLinks.add(videoData)
            vidMap.add(mapOf(
                "title" to item.title,
                "videoData" to videoData,
            ))
        }

        for (mapEntry in vidMap) {
            val title = mapEntry["title"] ?: continue
            val m3uLink = mapEntry["videoData"] ?: continue
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - $title",
                    url = m3uLink,
                ) { this.referer = extRef }
            )
        }
    }

    @Serializable
    data class TrstxVideoData(
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("file") @SerialName("file") val file: String? = null,
    )
}
