package com.lagradost.cloudstream3.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R

enum class DeviceTier {
    LOW,
    MID,
    HIGH
}

data class NetworkTierConfig(
    val maxRequests: Int,
    val maxRequestsPerHost: Int,
    val maxIdleConnections: Int,
    val keepAliveDurationMinutes: Long,
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long
)

data class PlayerBufferConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int
)

data class CoilTierConfig(
    val memoryCachePercent: Double,
    val diskCacheMaxBytes: Long,
    val crossfadeMs: Int,
    val allowHardwareBitmaps: Boolean,
    val useRgb565: Boolean
)

/**
 * Centralized Hardware-Adaptive Profile Engine.
 * Dynamically computes device capability tier (LOW, MID, HIGH) based on total system RAM,
 * Android Go low-RAM device status, and 64-bit architecture support.
 * Drives network concurrency, player buffers, image caching, and UI animations.
 */
object DeviceProfile {
    @Volatile
    private var cachedTier: DeviceTier? = null

    fun tier(ctx: Context): DeviceTier = cachedTier ?: synchronized(this) {
        cachedTier ?: detect(ctx).also { cachedTier = it }
    }

    fun isLowEnd(ctx: Context): Boolean = tier(ctx) == DeviceTier.LOW

    fun getNetworkConfig(ctx: Context): NetworkTierConfig {
        return when (tier(ctx)) {
            DeviceTier.LOW -> NetworkTierConfig(
                maxRequests = 32,
                maxRequestsPerHost = 10,
                maxIdleConnections = 6,
                keepAliveDurationMinutes = 2L,
                connectTimeoutSeconds = 15L,
                readTimeoutSeconds = 25L
            )
            DeviceTier.MID -> NetworkTierConfig(
                maxRequests = 64,
                maxRequestsPerHost = 16,
                maxIdleConnections = 12,
                keepAliveDurationMinutes = 4L,
                connectTimeoutSeconds = 12L,
                readTimeoutSeconds = 20L
            )
            DeviceTier.HIGH -> NetworkTierConfig(
                maxRequests = 96,
                maxRequestsPerHost = 24,
                maxIdleConnections = 20,
                keepAliveDurationMinutes = 5L,
                connectTimeoutSeconds = 10L,
                readTimeoutSeconds = 20L
            )
        }
    }

    fun getPlayerBufferConfig(ctx: Context): PlayerBufferConfig {
        return when (tier(ctx)) {
            DeviceTier.LOW -> PlayerBufferConfig(
                minBufferMs = 10_000,
                maxBufferMs = 25_000,
                bufferForPlaybackMs = 1_500,
                bufferForPlaybackAfterRebufferMs = 3_000,
                targetBufferBytes = 12 * 1024 * 1024, // 12 MB
                backBufferMs = 5_000
            )
            DeviceTier.MID -> PlayerBufferConfig(
                minBufferMs = 20_000,
                maxBufferMs = 45_000,
                bufferForPlaybackMs = 2_000,
                bufferForPlaybackAfterRebufferMs = 4_000,
                targetBufferBytes = 48 * 1024 * 1024, // 48 MB
                backBufferMs = 15_000
            )
            DeviceTier.HIGH -> PlayerBufferConfig(
                minBufferMs = 30_000,
                maxBufferMs = 60_000,
                bufferForPlaybackMs = 2_500,
                bufferForPlaybackAfterRebufferMs = 5_000,
                targetBufferBytes = 128 * 1024 * 1024, // 128 MB
                backBufferMs = 30_000
            )
        }
    }

    fun getCoilConfig(ctx: Context): CoilTierConfig {
        return when (tier(ctx)) {
            DeviceTier.LOW -> CoilTierConfig(
                memoryCachePercent = 0.10, // ~10% of app memory
                diskCacheMaxBytes = 128L * 1024 * 1024,
                crossfadeMs = 0, // crossfade off on LOW
                allowHardwareBitmaps = false,
                useRgb565 = true // RGB_565 saves 50% bitmap memory on LOW devices
            )
            DeviceTier.MID -> CoilTierConfig(
                memoryCachePercent = 0.20, // ~20% of app memory
                diskCacheMaxBytes = 512L * 1024 * 1024,
                crossfadeMs = 150,
                allowHardwareBitmaps = true,
                useRgb565 = false
            )
            DeviceTier.HIGH -> CoilTierConfig(
                memoryCachePercent = 0.25, // ~25% of app memory
                diskCacheMaxBytes = 1024L * 1024 * 1024,
                crossfadeMs = 250,
                allowHardwareBitmaps = true,
                useRgb565 = false
            )
        }
    }

    private val KNOWN_LOW_CHIPS = listOf(
        // Allwinner
        "sun50iw9", "sun50i", "sunxi", "sun8i", "sun4i", "allwinner", "h313", "h616", "h713", "h6", "h3", "a133", "a50", "dolphin", "petrel",
        // Rockchip budget / TV
        "rk3128", "rk3229", "rk3328", "rk3528", "rk3026", "rk3066", "rk3188", "rk3368", "rk30board",
        // Amlogic budget / TV
        "s805", "s905w", "s905l", "s905d", "s905y2", "meson", "gxl", "gxm",
        // Unisoc / Spreadtrum
        "sc7731", "sc9832", "sc9863", "sc9863a", "t310", "t606", "sprd", "sharkl", "ums512",
        // MediaTek entry-level
        "mt6580", "mt6735", "mt6737", "mt6739", "mt6761", "mt6762", "mt6765", "mt8167", "mt8127", "mt8163", "helio a20", "helio a22", "helio g25", "helio g35",
        // Qualcomm entry-level
        "msm8909", "msm8916", "msm8917", "msm8920", "msm8937", "msm8940", "msm8953", "qm215", "sdm429", "sdm439", "sdm450", "sm4250",
        // Known problematic GPUs & Budget TV box models
        "mali-g31", "mali-400", "mali-450", "ge8300", "hy320", "hy300", "a10plus", "magcubic", "sinoy", "android tv box",
        "x96", "tanix", "t95", "hk1", "h96", "mxq", "v88", "tx3", "tx6", "a95x"
    )

    private val KNOWN_FLAGSHIP_CHIPS = listOf(
        // Qualcomm Flagships (800 series, 8 Gen 1/2/3/4/Elite, 7+ Gen 2/3)
        "sm8750", "sm8650", "sm8550", "sm8475", "sm8450", "sm8350", "sm8250", "sm8150",
        "sm7475", "sm7675", "kona", "lahaina", "taro", "kalama", "pineapple", "sun",
        "snapdragon 8", "snapdragon 7+",
        // MediaTek Dimensity Flagships
        "dimensity 9400", "dimensity 9300", "dimensity 9200", "dimensity 9000",
        "dimensity 8300", "dimensity 8200", "dimensity 8100",
        "mt6989", "mt6985", "mt6983", "mt6895",
        // Google Tensor
        "tensor", "gs101", "gs201", "zuma", "zumapro",
        // Samsung Exynos Flagships
        "exynos 2400", "exynos 2200", "exynos 2100", "exynos 990", "s5e9945", "s5e9925", "s5e9815",
        // Nvidia Tegra (Shield TV Pro)
        "tegra", "foster", "darcy", "mdarcy"
    )

    fun hasBrokenHardwareGpu(): Boolean {
        val hardware = Build.HARDWARE?.lowercase() ?: ""
        val board = Build.BOARD?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MODEL?.lowercase() ?: ""
        } else ""

        val brokenGpuPatterns = listOf(
            "mali-g31", "mali-400", "mali-450", "ge8300",
            "sun50iw9", "sun50i", "sunxi", "sun8i", "allwinner", "h313", "h616", "h713", "h6", "h3",
            "rk3128", "rk3229", "rk3328", "rk3528", "rk30board",
            "s805", "s905w", "s905l", "meson-gxl",
            "hy320", "hy300", "a10plus", "magcubic", "sinoy", "android tv box",
            "x96", "tanix", "t95", "hk1", "h96", "mxq", "v88", "tx3", "tx6", "a95x"
        )
        return brokenGpuPatterns.any {
            it in hardware || it in board || it in manufacturer || (soc.isNotEmpty() && it in soc) || it in model
        }
    }

    fun resetCache() {
        cachedTier = null
    }

    private fun detect(ctx: Context): DeviceTier {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val override = prefs.getString("performance_profile_override_key", "auto")
            when (override) {
                "low" -> return DeviceTier.LOW
                "mid" -> return DeviceTier.MID
                "high" -> return DeviceTier.HIGH
            }
        } catch (_: Throwable) {
        }

        return try {
            val hardware = Build.HARDWARE?.lowercase() ?: ""
            val board = Build.BOARD?.lowercase() ?: ""
            val model = Build.MODEL?.lowercase() ?: ""
            val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
            val soc = if (Build.VERSION.SDK_INT >= 31) {
                Build.SOC_MODEL?.lowercase() ?: ""
            } else ""

            val matchesLowChip = KNOWN_LOW_CHIPS.any {
                it in hardware || it in board || it in manufacturer || it in model || (soc.isNotEmpty() && it in soc)
            }
            if (matchesLowChip) {
                return DeviceTier.LOW
            }

            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            val totalMb = if (am != null) {
                am.getMemoryInfo(mi)
                mi.totalMem / (1024 * 1024)
            } else {
                Runtime.getRuntime().maxMemory() / (1024 * 1024)
            }
            val isLowRam = am?.isLowRamDevice ?: false
            val has64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
            val cores = Runtime.getRuntime().availableProcessors()

            val matchesFlagshipChip = KNOWN_FLAGSHIP_CHIPS.any {
                it in hardware || it in board || it in manufacturer || it in model || (soc.isNotEmpty() && it in soc)
            }
            if (matchesFlagshipChip && totalMb >= 4096) {
                return DeviceTier.HIGH
            }

            when {
                isLowRam || totalMb <= 2048 || !has64Bit || cores <= 4 -> DeviceTier.LOW
                totalMb <= 4096 || cores <= 6 -> DeviceTier.MID
                else -> DeviceTier.HIGH
            }
        } catch (_: Throwable) {
            DeviceTier.MID
        }
    }
}
