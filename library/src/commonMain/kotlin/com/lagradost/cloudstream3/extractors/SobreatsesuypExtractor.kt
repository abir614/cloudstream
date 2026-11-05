// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

open class Sobreatsesuyp : ExtractorApi() {
    override val name = "Sobreatsesuyp"
    override val mainUrl = "https://sobreatsesuyp.com"
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
        val postJson: List<SobreatsesuypVideoData> = tryParseJson<List<SobreatsesuypVideoData>>(postText)?.drop(1)
            ?: (tryParseJson<List<Map<String, String>>>(postText)?.drop(1)?.mapNotNull { mapItem ->
                SobreatsesuypVideoData(
                    title = mapItem["title"],
                    file = mapItem["file"],
                )
            } ?: return)

        for (item in postJson) {
            if (item.file == null || item.title == null) continue
            val videoData = app.post("$mainUrl/playlist/${item.file.substring(1)}.txt", referer = extRef).text
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${item.title}",
                    url = videoData,
                ) { this.referer = extRef }
            )
        }
    }

    @Serializable
    data class SobreatsesuypVideoData(
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("file") @SerialName("file") val file: String? = null,
    )
}
