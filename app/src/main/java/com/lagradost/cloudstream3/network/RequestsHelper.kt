package com.lagradost.cloudstream3.network

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit

// Backwards compatible constructor, mark as deprecated later
fun Requests.initClient(context: Context) {
    this.baseClient = buildDefaultClient(context)
}

/** Only use ignoreSSL if you know what you are doing. Shares connection pool and dispatcher via newBuilder(). */
fun Requests.initClient(context: Context, ignoreSSL: Boolean = false) {
    this.baseClient = if (ignoreSSL) {
        try {
            com.lagradost.cloudstream3.app.baseClient.newBuilder().apply {
                ignoreAllSSLErrors()
            }.build()
        } catch (_: Throwable) {
            buildDefaultClient(context, true)
        }
    } else {
        buildDefaultClient(context, false)
    }
}



// Backwards compatible constructor, mark as deprecated later
fun buildDefaultClient(context: Context): OkHttpClient {
    return buildDefaultClient(context, false)
}

/** Only use ignoreSSL if you know what you are doing*/
fun buildDefaultClient(context: Context, ignoreSSL: Boolean = false): OkHttpClient {
    safe { Security.insertProviderAt(Conscrypt.newProvider(), 1) }

    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_key), 10)

    val netConfig = com.lagradost.cloudstream3.utils.DeviceProfile.getNetworkConfig(context)

    val dispatcher = Dispatcher().apply {
        maxRequests = netConfig.maxRequests
        maxRequestsPerHost = netConfig.maxRequestsPerHost
    }

    val connectionPool = ConnectionPool(
        maxIdleConnections = netConfig.maxIdleConnections,
        keepAliveDuration = netConfig.keepAliveDurationMinutes,
        timeUnit = TimeUnit.MINUTES
    )

    val baseClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
        .connectTimeout(netConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(netConfig.readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(netConfig.readTimeoutSeconds, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .followRedirects(true)
        .followSslRedirects(true)
        .apply {
            if (ignoreSSL) {
                ignoreAllSSLErrors()
            }
        }
        .cache(
            // Note that you need to add a ResponseInterceptor to make this 100% active.
            // The server response dictates if and when stuff should be cached.
            Cache(
                directory = File(context.cacheDir, "http_cache"),
                maxSize = 50L * 1024L * 1024L // 50 MiB
            )
        ).apply {
            when (dns) {
                0 -> dns(SafeDns(Dns.SYSTEM))
                1 -> addGoogleDns()
                2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                4 -> addAdGuardDns()
                5 -> addDNSWatchDns()
                6 -> addQuad9Dns()
                7 -> addDnsSbDns()
                8 -> addCanadianShieldDns()
                9 -> addMullvadDns()
                10 -> addResilientMultiTierDns()
                else -> dns(SafeDns(Dns.SYSTEM))
            }
        }

        // Needs to be build as otherwise the other builders will change this object
        .build()
    return baseClient
}

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/**
 * Set headers > Set cookies > Default headers > Default Cookies
 * TODO REMOVE AND REPLACE WITH NICEHTTP
 */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
    return tempHeaders.toHeaders()
}