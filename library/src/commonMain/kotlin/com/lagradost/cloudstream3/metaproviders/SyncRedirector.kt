package com.lagradost.cloudstream3.metaproviders

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.syncproviders.SyncIdName

object SyncRedirector {
    private val syncIds =
        listOf(
            SyncIdName.MyAnimeList to Regex("""myanimelist\.net/anime/(\d+)"""),
            SyncIdName.Anilist to Regex("""anilist\.co/anime/(\d+)""")
        )

    suspend fun redirect(
        url: String,
        providerApi: MainAPI
    ): String {
        // Tries provider solution
        // This goes through all sync ids and finds supported id by said provider
        return syncIds.firstNotNullOfOrNull { (syncName, syncRegex) ->
            if (providerApi.supportedSyncNames.contains(syncName)) {
                syncRegex.find(url)?.value?.let {
                    safeAsync {
                        providerApi.getLoadUrl(syncName, it)
                    }
                }
            } else null
        } ?: url
    }
}