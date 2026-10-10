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
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.network.buildDefaultClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.ByteBuffer

object ImageLoader {
    enum class DeviceTier {
        POTATO,   // <= 768MB RAM or isLowRamDevice: 500MB TV box, low-ram stick
        STANDARD, // 1GB - 2.5GB: standard TV sticks, budget phones
        HIGH_END  // >= 3GB: Shield TV Pro, Fire TV Cube 3, modern phones (4GB - 16GB RAM)
    }

    private fun getDeviceTier(context: PlatformContext): DeviceTier {
        val actManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (actManager?.isLowRamDevice == true) return DeviceTier.POTATO

        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val ram = memInfo.totalMem

        return when {
            ram in 1..805306368L -> DeviceTier.POTATO // <= 768MB
            ram in 805306369L..2684354560L -> DeviceTier.STANDARD // ~1GB - 2.5GB
            else -> DeviceTier.HIGH_END // >= 3GB (Shield TV, modern smartphones, high-end TV boxes)
        }
    }

    internal fun buildImageLoader(context: PlatformContext): ImageLoader {
        val isBrokenHardware = hasPotentialBrokenHardware()
        val tier = getDeviceTier(context)
        return ImageLoader.Builder(context)
            .crossfade(
                when (tier) {
                    DeviceTier.POTATO -> 0
                    DeviceTier.STANDARD -> 150
                    DeviceTier.HIGH_END -> 250
                }
            )
            .allowHardware(SDK_INT >= 28 && !isBrokenHardware && tier != DeviceTier.POTATO)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(
                        context,
                        when (tier) {
                            DeviceTier.POTATO -> 0.05
                            DeviceTier.STANDARD -> 0.12
                            DeviceTier.HIGH_END -> 0.22
                        }
                    )
                    .strongReferencesEnabled(tier == DeviceTier.HIGH_END)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("cs3_image_cache").toOkioPath())
                    .maxSizeBytes(
                        when (tier) {
                            DeviceTier.POTATO -> 128L * 1024 * 1024 // 128 MB
                            DeviceTier.STANDARD -> 512L * 1024 * 1024 // 512 MB
                            DeviceTier.HIGH_END -> 1024L * 1024 * 1024 // 1 GB on fast UFS/NVMe
                        }
                    )
                    .maxSizePercent(if (tier == DeviceTier.POTATO) 0.02 else 0.05)
                    .build()
            }
            /** Pass interceptors with care, unnecessary passing tokens to servers
            or image hosting services causes unauthorized exceptions **/
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { buildDefaultClient(context) }))
                if (isBrokenHardware || tier == DeviceTier.POTATO) {
                    add(BitmapFactoryDecoder.Factory())
                } // sw decoder
            }
            .apply {
                when (tier) {
                    DeviceTier.POTATO -> {
                        // 50% RAM savings for ultra-low devices
                        bitmapConfig(Bitmap.Config.RGB_565)
                    }
                    DeviceTier.STANDARD, DeviceTier.HIGH_END -> {
                        // Pristine 32-bit ARGB_8888 TrueColor for rich OLED/4K displays
                        if (isBrokenHardware) {
                            bitmapConfig(Bitmap.Config.ARGB_8888)
                        }
                    }
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
        val allwinnerPatterns = listOf("sun50iw9", "h713", "allwinner", "sunxi")
        val problematicModels =
            listOf("hy320", "hy300", "a10plus", "magcubic", "sinoy", "android tv box")
        return allwinnerPatterns.any { it in hardware || it in board || it in manufacturer } ||
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
