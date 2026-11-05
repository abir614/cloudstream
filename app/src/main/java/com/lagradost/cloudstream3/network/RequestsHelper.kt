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
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.GzipSource
import okio.InflaterSource
import okio.buffer
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

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
        .addInterceptor(NetworkCompressionInterceptor())
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
                maxSize = netConfig.diskCacheMaxBytes
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

/**
 * High-performance, zero-allocation network compression interceptor.
 * Automatically negotiates and transparently decompresses gzip and deflate content-encodings for
 * scraper manifests, repository indexes, JSON APIs, and subtitle payloads.
 * Strictly avoids touching media streams (.ts, .m4s, .mp4, images) and range requests to conserve CPU.
 */
class NetworkCompressionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath.lowercase()
        val isMediaStream = isMediaBitstream(path) || request.header("Range") != null

        val outgoingRequest = if (!isMediaStream && request.header("Accept-Encoding") == null) {
            request.newBuilder()
                .header("Accept-Encoding", "gzip, deflate")
                .build()
        } else {
            request
        }

        val response = chain.proceed(outgoingRequest)
        val body = response.body ?: return response

        if (isMediaStream || response.code == 204 || response.code == 304 || request.method.equals("HEAD", ignoreCase = true)) {
            return response
        }

        val contentEncoding = response.header("Content-Encoding")?.lowercase()?.trim() ?: return response

        val decompressedSource: BufferedSource = when {
            contentEncoding.equals("gzip", ignoreCase = true) -> {
                try {
                    GzipSource(body.source()).buffer()
                } catch (_: Throwable) {
                    return response
                }
            }
            contentEncoding.equals("deflate", ignoreCase = true) -> {
                try {
                    val rawSource = body.source()
                    val isZlib = try {
                        if (rawSource.request(2)) {
                            val b0 = rawSource.buffer[0].toInt() and 0xFF
                            val b1 = rawSource.buffer[1].toInt() and 0xFF
                            (b0 and 0x0F == 8) && ((b0 * 256 + b1) % 31 == 0)
                        } else false
                    } catch (_: Throwable) {
                        false
                    }
                    val inflater = Inflater(!isZlib)
                    object : InflaterSource(rawSource, inflater) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                inflater.end()
                            }
                        }
                    }.buffer()
                } catch (_: Throwable) {
                    return response
                }
            }
            else -> return response
        }

        val strippedHeaders = response.headers.newBuilder()
            .removeAll("Content-Encoding")
            .removeAll("Content-Length")
            .build()

        val uncompressedBody = object : ResponseBody() {
            override fun contentType(): MediaType? = body.contentType()
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = decompressedSource
        }

        return response.newBuilder()
            .headers(strippedHeaders)
            .body(uncompressedBody)
            .build()
    }

    private fun isMediaBitstream(path: String): Boolean {
        return path.endsWith(".ts") || path.endsWith(".m4s") ||
                path.endsWith(".mp4") || path.endsWith(".mkv") ||
                path.endsWith(".webm") || path.endsWith(".avi") ||
                path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".png") || path.endsWith(".webp")
    }
}