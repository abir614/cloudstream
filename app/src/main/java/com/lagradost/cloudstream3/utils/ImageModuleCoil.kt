package com.lagradost.cloudstream3.utils

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.disk.DiskCache
import coil3.dispose
import coil3.load
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.util.DebugLogger
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.network.SafeDns
import com.lagradost.cloudstream3.network.addAdGuardDns
import com.lagradost.cloudstream3.network.addCanadianShieldDns
import com.lagradost.cloudstream3.network.addCloudFlareDns
import com.lagradost.cloudstream3.network.addDNSWatchDns
import com.lagradost.cloudstream3.network.addDnsSbDns
import com.lagradost.cloudstream3.network.addGoogleDns
import com.lagradost.cloudstream3.network.addMullvadDns
import com.lagradost.cloudstream3.network.addQuad9Dns
import com.lagradost.cloudstream3.network.addResilientMultiTierDns
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

object ImageLoader {
    private const val TAG = "CoilImgLoader"

    @Volatile
    private var imageHttpClient: OkHttpClient? = null

    internal fun getImageHttpClient(context: PlatformContext): OkHttpClient =
        imageHttpClient ?: synchronized(this) {
            imageHttpClient ?: buildImageHttpClient(context).also { imageHttpClient = it }
        }

    internal fun resetImageClient() {
        imageHttpClient = null
    }

    internal fun evictConnections() {
        try {
            imageHttpClient?.connectionPool?.evictAll()
        } catch (_: Throwable) {
        }
    }

    private fun buildImageHttpClient(context: PlatformContext): OkHttpClient {
        val tier = DeviceProfile.tier(context)
        val dispatcher = Dispatcher().apply {
            when (tier) {
                DeviceTier.LOW -> {
                    maxRequests = 32
                    maxRequestsPerHost = 12
                }
                DeviceTier.MID -> {
                    maxRequests = 64
                    maxRequestsPerHost = 20
                }
                DeviceTier.HIGH -> {
                    maxRequests = 96
                    maxRequestsPerHost = 32
                }
            }
        }

        val connectionPool = ConnectionPool(
            maxIdleConnections = when (tier) {
                DeviceTier.LOW -> 8
                DeviceTier.MID -> 16
                DeviceTier.HIGH -> 32
            },
            keepAliveDuration = 5L,
            timeUnit = TimeUnit.MINUTES
        )

        val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
        val dns = settingsManager.getInt(context.getString(R.string.dns_key), 10)

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(15L, TimeUnit.SECONDS)
            .writeTimeout(15L, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(true)
            .followSslRedirects(true)
            .apply {
                when (dns) {
                    0 -> dns(SafeDns(Dns.SYSTEM))
                    1 -> addGoogleDns()
                    2 -> addCloudFlareDns()
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
            .build()
    }

    internal fun buildImageLoader(context: PlatformContext): ImageLoader {
        val isBrokenHardware = hasPotentialBrokenHardware()
        val tier = DeviceProfile.tier(context)
        val config = DeviceProfile.getCoilConfig(context)
        val httpClient = getImageHttpClient(context)

        return ImageLoader.Builder(context)
            .crossfade(config.crossfadeMs)
            .allowHardware(SDK_INT >= 28 && !isBrokenHardware && config.allowHardwareBitmaps)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, config.memoryCachePercent)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("cs3_image_cache").toOkioPath())
                    .maxSizeBytes(config.diskCacheMaxBytes)
                    .maxSizePercent(if (tier == DeviceTier.LOW) 0.02 else 0.05)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { httpClient }))
                if (isBrokenHardware || tier == DeviceTier.LOW) {
                    add(BitmapFactoryDecoder.Factory())
                }
            }
            .apply {
                if (config.useRgb565) {
                    // 50% RAM savings for ultra-low devices
                    bitmapConfig(Bitmap.Config.RGB_565)
                } else if (isBrokenHardware) {
                    // Pristine 32-bit ARGB_8888 TrueColor for rich OLED/4K displays
                    bitmapConfig(Bitmap.Config.ARGB_8888)
                }
                setupCoilLogger()
            }
            .build()
    }

    /** DebugLogger on debug builds which won't slow down release builds & use EventListener for
    Errors on release builds. **/
    internal fun ImageLoader.Builder.setupCoilLogger() {
        if (BuildConfig.DEBUG) {
            logger(DebugLogger())
        } else {
            eventListener(object : EventListener() {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    super.onError(request, result)
                    Log.e(TAG, "Image load error: ${result.throwable.message ?: result.throwable}")
                    Log.e(TAG, "  URL: ${request.data}")
                    Log.e(TAG, "  allowHardware: ${request.allowHardware}")
                    Log.e(TAG, "  hardware: ${Build.HARDWARE}, board: ${Build.BOARD}")
                }
            })
        }
    }

    /** coil's built in loader attached w/ global synchronized instance **/
    private fun ImageView.loadImageInternal(
        imageData: Any?,
        headers: Map<String, String>? = null,
        builder: ImageRequest.Builder.() -> Unit = {} // for placeholder, error & transformations
    ) {
        // clear image to avoid loading & flickering issue at fast scrolling (~recycler view/lazy column)
        this.dispose()
        if (imageData == null) return
        // setImageResource is better than coil3 on resources due to attr
        if (imageData is Int) {
            this.setImageResource(imageData); return
        }
        // headers can be overridden by extensions.
        this.load(imageData, SingletonImageLoader.get(context)) {
            this.httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
                headerBuilder["User-Agent"] = USER_AGENT
                headers?.forEach { (key, value) ->
                    headerBuilder[key] = value
                }
            }.build())
            builder() // if passed
        }
    }

    private fun hasPotentialBrokenHardware(): Boolean {
        val hardware = Build.HARDWARE?.lowercase() ?: ""
        val board = Build.BOARD?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val soc = if (SDK_INT >= 31) {
            Build.SOC_MODEL?.lowercase() ?: ""
        } else ""
        val allwinnerPatterns = listOf(
            "sun50iw9", "sun50i", "sunxi", "allwinner", "h313", "h616", "h713", "h6", "h3",
            "mali-g31", "dolphin", "petrel"
        )
        val problematicModels = listOf(
            "hy320", "hy300", "a10plus", "magcubic", "sinoy", "android tv box", "x96", "tanix"
        )
        return allwinnerPatterns.any { it in hardware || it in board || it in manufacturer || (soc.isNotEmpty() && it in soc) } ||
                problematicModels.any { it in model }
    }

    /** TYPE_SAFE_LOADERS **/
    fun ImageView.loadImage(
        imageData: UiImage?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = when (imageData) {
        is UiImage.Image -> loadImageInternal(
            imageData = imageData.url,
            headers = imageData.headers,
            builder = builder
        )

        is UiImage.Bitmap -> loadImageInternal(imageData = imageData.bitmap, builder = builder)
        is UiImage.Drawable -> loadImageInternal(imageData = imageData.resId, builder = builder)
        null -> loadImageInternal(null, builder = builder)
    }

    fun ImageView.loadImage(
        imageData: String?,
        headers: Map<String, String>? = null,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, headers = headers, builder = builder)

    fun ImageView.loadImage(
        imageData: Uri?,
        headers: Map<String, String>? = null,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, headers = headers, builder = builder)

    fun ImageView.loadImage(
        imageData: File?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        @DrawableRes imageData: Int?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        imageData: Drawable?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        imageData: Bitmap?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        imageData: ByteArray?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        imageData: ByteBuffer?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)
}
