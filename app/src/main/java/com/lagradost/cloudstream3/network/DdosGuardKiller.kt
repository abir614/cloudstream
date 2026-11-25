package com.lagradost.cloudstream3.network

import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * @param alwaysBypass will pre-emptively fetch ddos guard cookies if true.
 * If false it will only try to get cookies when a request returns 403
 * */
// As seen in https://github.com/anime-dl/anime-downloader/blob/master/anime_downloader/sites/erairaws.py
@AnyThread
class DdosGuardKiller(private val alwaysBypass: Boolean) : Interceptor {
    val savedCookiesMap: MutableMap<String, Map<String, String>> = ConcurrentHashMap()

    private var ddosBypassPath: String? = null

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        if (alwaysBypass) return@runBlocking bypassDdosGuard(request)

        val response = chain.proceed(request)
        return@runBlocking if (response.code == 403) {
            response.close()
            bypassDdosGuard(request)
        } else response
    }

    private suspend fun bypassDdosGuard(request: Request): Response {
        ddosBypassPath = ddosBypassPath ?: Regex("'(.*?)'").find(
            app.get(
                "https://check.ddos-guard.net/check.js"
            ).text
        )?.groupValues?.getOrNull(1)?.takeIf { candidate ->
            // Prevent authority/userinfo injection (e.g. "//evil.com", "@evil.com")
            candidate.startsWith("/") && !candidate.startsWith("//") &&
                !candidate.contains("@") && !candidate.contains("..") &&
                candidate.none { it.isWhitespace() }
        }

        val safeBypassPath = ddosBypassPath
            ?: throw java.io.IOException("Blocked unsafe or missing DDoS-Guard bypass path")

        val cookies =
            savedCookiesMap[request.url.host]
                // If no cookies are found fetch and save em.
                ?: (request.url.scheme + "://" + request.url.host + safeBypassPath).let { bypassUrl ->
                    // Use main app client (SafeDns-protected) instead of raw Requests() to prevent SSRF
                    app.get(bypassUrl).cookies.also { cookies ->
                        savedCookiesMap[request.url.host] = cookies
                    }
                }

        val headers = getHeaders(request.headers.toMap(), cookies + request.cookies)
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).execute()
    }
}
