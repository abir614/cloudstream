// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class SibNet : ExtractorApi() {
    override val name            = "SibNet"
    override val mainUrl         = "https://video.sibnet.ru"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef  = referer ?: ""
        val iSource = app.get(url, referer=extRef).text
        val m3uRaw = Regex("""player.src\(\[\{src: \"([^\"]+)""").find(iSource)?.groupValues?.getOrNull(1) ?: return
        val m3uLink = "${mainUrl}${m3uRaw}"

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3uLink,
            ) {
                this.referer = url
            }
        )
    }
}