package com.lagradost.cloudstream3.ui.coordinators

import android.net.Uri
import android.os.Bundle
import java.net.URI
import java.net.URLDecoder

sealed class DeepLinkAction {
    data class LoadRepository(val url: String) : DeepLinkAction()
    data class AccountAuth(val url: String) : DeepLinkAction()
    object HotReload : DeepLinkAction()
    data class SearchQuery(val query: String) : DeepLinkAction()
    data class PlayStream(val url: String, val name: String?) : DeepLinkAction()
    data class RawProviderMedia(val url: String, val extraArgs: Bundle?) : DeepLinkAction()
    object None : DeepLinkAction()
}

/**
 * Decoupled coordinator for evaluating and routing inbound deep links and application URIs.
 * Isolates URI parsing logic from MainActivity for high-speed, isolated unit testing.
 */
class DeepLinkCoordinator(
    private val appString: String = "cloudstream",
    private val appStringRepo: String = "cloudstreamrepo",
    private val appStringSearch: String = "cloudstreamsearch",
    private val appStringPlayer: String = "cloudstreamplayer"
) {

    fun parseIntentUrl(rawUrl: String?, extraArgs: Bundle? = null): DeepLinkAction {
        if (rawUrl.isNullOrBlank()) return DeepLinkAction.None

        val trimmed = rawUrl.trim()

        if (trimmed.startsWith("https://cs.repo")) {
            val realUrl = "https://" + trimmed.substringAfter("?")
            return DeepLinkAction.LoadRepository(realUrl)
        }

        if (trimmed == "$appString:") {
            return DeepLinkAction.HotReload
        }

        val scheme = try {
            URI(trimmed).scheme?.lowercase()
        } catch (_: Throwable) {
            null
        }

        return when {
            scheme == appStringRepo -> {
                val url = trimmed.replaceFirst(appStringRepo, "https", ignoreCase = true)
                DeepLinkAction.LoadRepository(url)
            }
            scheme == appStringSearch -> {
                val query = trimmed.substringAfter("$appStringSearch://")
                val decoded = try {
                    URLDecoder.decode(query, "UTF-8")
                } catch (_: Throwable) {
                    query
                }
                DeepLinkAction.SearchQuery(decoded)
            }
            scheme == appStringPlayer -> {
                try {
                    val uri = Uri.parse(trimmed)
                    val name = uri.getQueryParameter("name")
                    val authority = uri.authority ?: ""
                    val url = try {
                        URLDecoder.decode(authority, "UTF-8")
                    } catch (_: Throwable) {
                        authority
                    }
                    DeepLinkAction.PlayStream(url, name)
                } catch (_: Throwable) {
                    DeepLinkAction.None
                }
            }
            trimmed.contains(appString) -> {
                DeepLinkAction.AccountAuth(trimmed)
            }
            scheme == "http" || scheme == "https" -> {
                DeepLinkAction.RawProviderMedia(trimmed, extraArgs)
            }
            else -> DeepLinkAction.None
        }
    }
}
