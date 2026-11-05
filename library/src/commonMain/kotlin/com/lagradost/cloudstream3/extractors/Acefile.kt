package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

open class Acefile : ExtractorApi() {
    override val name = "Acefile"
    override val mainUrl = "https://acefile.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = "/(?:d|download|player|f|file)/(\\w+)".toRegex().find(url)?.groupValues?.getOrNull(1) ?: return
        val script = getAndUnpack(app.get("$mainUrl/player/$id").text)
        val service = """service\s*=\s*['"]([^'"]+)""".toRegex().find(script)?.groupValues?.getOrNull(1) ?: return
        val serverUrl = """['"](\S+check&id\S+?)['"]""".toRegex().find(script)?.groupValues?.getOrNull(1)
            ?.replace("\"+service+\"", service) ?: return

        val video = app.get(serverUrl, referer = "$mainUrl/").parsedSafe<Source>()?.data

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                video ?: return,
            )
        )

    }

    @Serializable
    private data class Source(
        @JsonProperty("data") @SerialName("data") val data: String? = null,
    )
}
