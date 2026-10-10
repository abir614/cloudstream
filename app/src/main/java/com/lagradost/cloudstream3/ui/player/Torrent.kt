package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Clean Core Stub:
 * TorrServer Go engine and P2P torrent streaming are stripped to eliminate 12.5+ MB native bloat
 * and ensure zero memory leaks on low-end devices.
 */
object Torrent {
    var hasAcceptedTorrentForThisSession: Boolean? = false

    fun deleteAllFiles(): Boolean = true

    @Throws
    suspend fun transformLink(link: ExtractorLink): Nothing {
        throw ErrorLoadingException("P2P Torrent streaming engine is stripped in Clean Core")
    }
}
