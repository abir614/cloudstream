package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

open class Vixeo : Vidsonic() {
    override val name: String = "Vixeo"
    override val mainUrl: String = "https://vixeo.io"

    @Prerelease
    override fun extractEncodedVideoUrl(response: NiceResponse): String {
        // Vixeo encodes the stream URL as Base64 instead of inlining it into JavaScript
        val dataConfigBase64 = response.document.select("#streamsonic-player-root").attr("data-config")
        val dataConfigRaw = base64Decode(dataConfigBase64)
        val dataConfig = AppUtils.parseJson<JsonObject>(dataConfigRaw)
        return dataConfig["source"]!!.jsonPrimitive.content
    }
}

@Prerelease
open class Vidsonic : ExtractorApi() {
    override val name: String = "Vidsonic"
    override val mainUrl: String = "https://vidsonic.net"
    override val requiresReferer: Boolean = false

    @Prerelease
    open fun extractEncodedVideoUrl(response: NiceResponse): String {
        return response.text
            .substringAfter("const _0x1 = ")
            .substringBefore(";")
            .replace("'", "")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Decodes hex pairs and reverses the result to extract the stream URL
        val response = app.get(url)
        val encodedStreamUrl = extractEncodedVideoUrl(response)

        // (improved) Kotlin implementation of the JavaScript code from above
        val streamUrl = encodedStreamUrl
            .replace("|", "")
            // always two base16 digits together build one ASCII char
            .chunked(2)
            .map {
                it.toInt(16).toChar()
            }
            .joinToString("")
            .reversed()

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            )
        )
    }
}