package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

open class Maxstream : ExtractorApi() {
    override var name = "Maxstream"
    override var mainUrl = "https://maxstream.video/"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val extractedLinksList: MutableList<ExtractorLink> = mutableListOf()
        val response = app.get(url).text
        val jstounpack = Regex("cript\">eval((.|\\n)*?)</script>").find(response)?.groups?.get(1)?.value ?: return null
        val unpacjed = JsUnpacker(jstounpack).unpack() ?: return null
        val extractedUrl = Regex("""src:"((.|\n)*?)",type""").find(unpacjed)?.groups?.get(1)?.value ?: return null

        M3u8Helper.generateM3u8(
            name,
            extractedUrl,
            url,
            headers = mapOf("referer" to url)
        ).forEach { link ->
            extractedLinksList.add(link)
        }

        return extractedLinksList
    }
}