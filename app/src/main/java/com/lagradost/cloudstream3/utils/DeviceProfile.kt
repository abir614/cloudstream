package com.lagradost.cloudstream3.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import java.io.File

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
 * Dynamically computes device capability tier (LOW, MID, HIGH) based on SoC chipset architecture,
 * total system RAM, Android Go low-RAM status, 64-bit support, and battery optimization state.
 * Drives network concurrency, player buffers, image caching, and UI animations.
 */
object DeviceProfile {
    @Volatile
    private var cachedTier: DeviceTier? = null
    @Volatile
    private var cachedOverride: String? = null
    @Volatile
    private var cachedBatteryRestricted: Boolean? = null

    fun tier(ctx: Context): DeviceTier {
        val override = try {
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString("performance_profile_override_key", "auto") ?: "auto"
        } catch (_: Throwable) {
            "auto"
        }
        val isRestricted = BatteryOptimizationChecker.isAppRestricted(ctx)

        val cached = cachedTier
        if (cached != null && cachedOverride == override && cachedBatteryRestricted == isRestricted) {
            return cached
        }

        return synchronized(this) {
            val doubleCheck = cachedTier
            if (doubleCheck != null && cachedOverride == override && cachedBatteryRestricted == isRestricted) {
                doubleCheck
            } else {
                val computed = detect(ctx, override, isRestricted)
                cachedOverride = override
                cachedBatteryRestricted = isRestricted
                cachedTier = computed
                computed
            }
        }
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
                crossfadeMs = 150,
                allowHardwareBitmaps = true,
                useRgb565 = false
            )
        }
    }

    private val KNOWN_LOW_CHIPS = listOf(
        // Allwinner
        "sun50iw9", "sun50iw6", "sun50iw3", "sun50iw1", "sun50i", "sunxi", "sun8i", "sun4i", "sun7i", "allwinner",
        "h313", "h616", "h618", "h700", "h713", "h728", "h6", "h3", "h5", "h2", "h2+",
        "a133", "a133p", "a100", "a50", "a33", "a23", "a64", "a20", "a10",
        "dolphin", "petrel", "cheetah", "eagle", "tulip",
        // Rockchip budget / TV
        "rk3128", "rk3229", "rk3328", "rk3528", "rk3026", "rk3066", "rk3188", "rk3368", "rk30board", "rk312x",
        // Amlogic budget / TV
        "s805", "s805x", "s905w", "s905w2", "s905l", "s905d", "s905y2", "s905y4", "meson-gxl", "meson-gxm", "meson-gxbb", "meson64", "gxl", "gxm", "gxbb",
        // Unisoc / Spreadtrum
        "sc7731", "sc9832", "sc9863", "sc9863a", "sc9850", "t310", "t606", "sprd", "sharkl", "sp9832", "sp7731",
        // MediaTek entry-level / legacy
        "mt6580", "mt6582", "mt6592", "mt6735", "mt6737", "mt6739", "mt6750", "mt6752", "mt6753", "mt6755", "mt6757",
        "mt6761", "mt6762", "mt6765", "mt8167", "mt8127", "mt8163", "mt8168",
        "helio a20", "helio a22", "helio g25", "helio g35", "helio p22", "helio p35",
        // Qualcomm entry-level / legacy
        "msm8909", "msm8916", "msm8917", "msm8920", "msm8937", "msm8940", "msm8952", "msm8953",
        "qm215", "sdm429", "sdm439", "sdm450", "sm4250",
        // Known problematic GPUs & Budget TV box models
        "mali-g31", "mali-400", "mali-450", "mali-t720", "mali-t820", "ge8300", "ge8320", "ge8100",
        "hy320", "hy300", "a10plus", "magcubic", "sinoy", "android tv box",
        "x96", "tanix", "t95", "hk1", "h96", "mxq", "v88", "tx3", "tx6", "a95x", "transpeed", "dq03"
    )

    private val KNOWN_FLAGSHIP_CHIPS = listOf(
        // Qualcomm Flagships (800 series, 8 Gen 1/2/3/4/Elite, 7+ Gen 2/3)
        "sm8750", "sm8650", "sm8550", "sm8475", "sm8450", "sm8350", "sm8250", "sm8150", "sdm845",
        "sm7675", "sm7475",
        "sun", "pineapple", "kalama", "cape", "taro", "lahaina", "kona",
        "snapdragon 8 elite", "snapdragon 8 gen 4", "snapdragon 8 gen 3", "snapdragon 8 gen 2",
        "snapdragon 8+ gen 1", "snapdragon 8 gen 1", "snapdragon 888", "snapdragon 870", "snapdragon 865", "snapdragon 855",
        "snapdragon 7+ gen 3", "snapdragon 7+ gen 2", "snapdragon 7+",
        // MediaTek Dimensity Flagships & Upper-tier
        "dimensity 9400", "dimensity 9300", "dimensity 9200", "dimensity 9000",
        "dimensity 8300", "dimensity 8200", "dimensity 8100", "dimensity 8050", "dimensity 8020", "dimensity 8000",
        "dimensity 1300", "dimensity 1200", "dimensity 1100", "dimensity 1000",
        "mt6991", "mt6989", "mt6985", "mt6983", "mt6895", "mt6893", "mt6891", "mt6889", "mt6897",
        // Google Tensor
        "tensor g4", "tensor g3", "tensor g2", "tensor", "gs101", "gs201", "zuma", "zumapro", "whitechapel", "cloudripper", "caiman", "komodo", "tokay",
        // Samsung Exynos Flagships
        "exynos 2500", "exynos 2400", "exynos 2200", "exynos 2100", "exynos 990", "exynos 9825", "exynos 9820",
        "s5e9945", "s5e9925", "s5e9815", "s5e9830", "s5e9820",
        // Nvidia Tegra (Shield TV / Pro)
        "tegra", "tegra210", "foster", "darcy", "mdarcy",
        // PC / Laptop / Desktop Processor
        "intel", "amd", "ryzen", "core(tm)", "apple"
    )

    private val KNOWN_MID_CHIPS = listOf(
        // MediaTek Helio G series (including Helio G100 Ultimate!)
        "g100 ultimate", "g100-ultimate", "helio g100", "helio-g100", "g100",
        "g99 ultimate", "g99-ultimate", "g99 ultra", "helio g99", "helio-g99", "g99",
        "helio g96", "helio g95", "helio g90t", "helio g90", "helio g88", "helio g85", "helio g80", "helio g70",
        "helio p95", "helio p90", "helio p70", "helio p65", "helio p60",
        "mt6789", "mt6789v", "mt6785", "mt6769", "mt6769t", "mt6769z", "mt6768", "mt6779", "mt6771",
        // MediaTek Dimensity Midrange
        "dimensity 7300", "dimensity 7200", "dimensity 7050", "dimensity 7025", "dimensity 7020", "dimensity 7000",
        "dimensity 6300", "dimensity 6100", "dimensity 6080", "dimensity 6020",
        "dimensity 1080", "dimensity 930", "dimensity 920", "dimensity 900", "dimensity 810", "dimensity 800", "dimensity 720", "dimensity 700",
        "mt6878", "mt6886", "mt6877", "mt6877v", "mt6855", "mt6833", "mt6835", "mt6853", "mt6873",
        // MediaTek Tablets / Kompanio
        "kompanio", "mt8788", "mt8781", "mt8183", "mt8188", "mt8768",
        // Qualcomm Snapdragon 7 series
        "snapdragon 7 gen 3", "snapdragon 7s gen 3", "snapdragon 7s gen 2", "snapdragon 7 gen 1",
        "snapdragon 782g", "snapdragon 780g", "snapdragon 778g", "snapdragon 768g", "snapdragon 765g", "snapdragon 750g", "snapdragon 732g", "snapdragon 730g", "snapdragon 720g",
        "sm7550", "sm7635", "sm7435", "sm7450", "sm7325", "sm7350", "sm7250", "sm7225", "sm7150", "sm7125", "sdm710",
        // Qualcomm Snapdragon 6 series
        "snapdragon 6 gen 3", "snapdragon 6s gen 3", "snapdragon 6 gen 1",
        "snapdragon 695", "snapdragon 685", "snapdragon 680", "snapdragon 678", "snapdragon 675", "snapdragon 670", "snapdragon 665", "snapdragon 662", "snapdragon 660",
        "sm6475", "sm6450", "sm6375", "sm6225", "sm6150", "sm6125", "sm6115", "sdm660", "sdm636",
        // Qualcomm Snapdragon 4 series (Modern 5G)
        "snapdragon 4 gen 2", "snapdragon 4s gen 2", "snapdragon 4 gen 1", "snapdragon 480",
        "sm4450", "sm4375", "sm4350",
        // Samsung Exynos Midrange
        "exynos 1580", "exynos 1480", "exynos 1380", "exynos 1330", "exynos 1280", "exynos 1080", "exynos 980", "exynos 880",
        "s5e8845", "s5e8835", "s5e8825", "s5e8535", "s5e8830", "s5e9630",
        // Unisoc Modern Midrange
        "unisoc t820", "unisoc t770", "unisoc t760", "unisoc t619", "unisoc t618", "unisoc t616", "unisoc t612", "unisoc t610",
        "t820", "t770", "t760", "t619", "t618", "t616", "t612", "t610", "ums9620", "ums9230", "ums512",
        // Rockchip Midrange
        "rk3588", "rk3588s", "rk3576", "rk3568", "rk3566", "rk3399", "rk3399pro",
        // Amlogic Midrange / High TV
        "s922x", "a311d", "s905x4", "s905x3", "s905x2", "t962e", "t968"
    )

    private fun getCpuInfoString(): String {
        return try {
            val file = File("/proc/cpuinfo")
            if (file.exists() && file.canRead()) {
                file.useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("Hardware", ignoreCase = true) || line.startsWith("model name", ignoreCase = true)) {
                            val parts = line.split(":")
                            if (parts.size >= 2) {
                                val candidate = parts[1].trim()
                                if (candidate.isNotEmpty()) return candidate
                            }
                        }
                    }
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun hasBrokenHardwareGpu(): Boolean {
        val hardware = Build.HARDWARE?.lowercase() ?: ""
        val board = Build.BOARD?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MODEL?.lowercase() ?: ""
        } else ""

        val brokenGpuPatterns = listOf(
            "mali-g31", "mali-400", "mali-450", "mali-t720", "ge8300", "ge8320", "ge8100",
            "sun50iw9", "sun50iw6", "sun50i", "sunxi", "sun8i", "allwinner", "h313", "h616", "h713", "h6", "h3",
            "rk3128", "rk3229", "rk3328", "rk3528", "rk30board",
            "s805", "s905w", "s905w2", "s905l", "meson-gxl",
            "hy320", "hy300", "a10plus", "magcubic", "sinoy", "android tv box",
            "x96", "tanix", "t95", "hk1", "h96", "mxq", "v88", "tx3", "tx6", "a95x"
        )
        return brokenGpuPatterns.any {
            it in hardware || it in board || it in manufacturer || (soc.isNotEmpty() && it in soc) || it in model
        }
    }

    fun resetCache() {
        synchronized(this) {
            cachedTier = null
            cachedOverride = null
            cachedBatteryRestricted = null
        }
    }

    fun getProfileBadge(ctx: Context): String {
        return when (tier(ctx)) {
            DeviceTier.HIGH -> "⚡ PERFORMANCE"
            DeviceTier.MID -> "⚙️ BALANCED"
            DeviceTier.LOW -> "🔋 POWER SAFE"
        }
    }

    fun getProfileColorHex(ctx: Context): String {
        return when (tier(ctx)) {
            DeviceTier.HIGH -> "#00E676" // Vibrant Emerald Green
            DeviceTier.MID -> "#00E5FF"  // Electric Cyan
            DeviceTier.LOW -> "#FF9100"  // Sunset Warm Amber
        }
    }

    fun getProfileColorInt(ctx: Context): Int {
        return try {
            android.graphics.Color.parseColor(getProfileColorHex(ctx))
        } catch (_: Throwable) {
            android.graphics.Color.CYAN
        }
    }

    fun getSearchConcurrency(ctx: Context?): Int {
        if (ctx == null) return 8
        return when (tier(ctx)) {
            DeviceTier.HIGH -> 16
            DeviceTier.MID -> 8
            DeviceTier.LOW -> 3
        }
    }

    fun getDownloadBufferLimit(ctx: Context): Long {
        return when (tier(ctx)) {
            DeviceTier.HIGH -> 80_000_000L  // 80 MB
            DeviceTier.MID -> 35_000_000L   // 35 MB
            DeviceTier.LOW -> 10_000_000L   // 10 MB
        }
    }

    fun getTotalRamMb(ctx: Context): Long {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            if (am != null) {
                am.getMemoryInfo(mi)
                mi.totalMem / (1024 * 1024)
            } else {
                Runtime.getRuntime().maxMemory() / (1024 * 1024)
            }
        } catch (_: Throwable) {
            1024L
        }
    }

    fun getHardwareSummary(ctx: Context): String {
        return try {
            val currentTier = tier(ctx)
            val totalMb = getTotalRamMb(ctx)
            val cores = Runtime.getRuntime().availableProcessors()
            val has64Bit = try { Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() ?: true } catch (_: Throwable) { true }
            val arch = if (has64Bit) "64-bit" else "32-bit"
            val badge = getProfileBadge(ctx)
            val isBatteryUnrestricted = !BatteryOptimizationChecker.isAppRestricted(ctx)
            val batteryLabel = if (isBatteryUnrestricted) "Unrestricted" else "Optimized"
            val hwMode = when (currentTier) {
                DeviceTier.HIGH -> "Performance • 128MB Buffers • Max Concurrency"
                DeviceTier.MID -> "Balanced • 48MB Buffers • Standard Acceleration"
                DeviceTier.LOW -> "Power Safe • RGB_565 (50% RAM Save) • 12MB Buffers"
            }

            "[$badge] ${totalMb} MB RAM • $cores Cores ($arch)\nBattery: $batteryLabel • $hwMode"
        } catch (_: Throwable) {
            "[⚙️ BALANCED] Standard Profile"
        }
    }

    private fun detect(ctx: Context, override: String, isRestricted: Boolean): DeviceTier {
        when (override) {
            "low" -> return DeviceTier.LOW
            "mid" -> return DeviceTier.MID
            "high" -> return DeviceTier.HIGH
        }

        return try {
            val hardware = Build.HARDWARE?.lowercase() ?: ""
            val board = Build.BOARD?.lowercase() ?: ""
            val model = Build.MODEL?.lowercase() ?: ""
            val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
            val product = Build.PRODUCT?.lowercase() ?: ""
            val device = Build.DEVICE?.lowercase() ?: ""
            val soc = if (Build.VERSION.SDK_INT >= 31) {
                "${Build.SOC_MODEL?.lowercase() ?: ""} ${Build.SOC_MANUFACTURER?.lowercase() ?: ""}"
            } else ""
            val cpuInfo = getCpuInfoString().lowercase()

            val socIdentifier = "$hardware $board $model $manufacturer $product $device $soc $cpuInfo"

            val isLowChip = KNOWN_LOW_CHIPS.any { it in socIdentifier }
            val isFlagshipChip = KNOWN_FLAGSHIP_CHIPS.any { it in socIdentifier }
            val isMidChip = KNOWN_MID_CHIPS.any { it in socIdentifier }

            val totalMb = getTotalRamMb(ctx)
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val isLowRam = am?.isLowRamDevice ?: false
            val has64Bit = try { Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() ?: true } catch (_: Throwable) { true }
            val cores = Runtime.getRuntime().availableProcessors()
            val isBrokenGpu = hasBrokenHardwareGpu()

            // 1. Weak / Low-memory / Potato hardware check:
            if (isLowChip || isBrokenGpu || isLowRam || totalMb <= 2048 || (!has64Bit && totalMb <= 3072) || cores < 4) {
                return DeviceTier.LOW
            }

            // 2. Battery optimization state:
            val isBatteryOptimizationDisabled = !isRestricted

            // 3. Performance tier evaluation (SoC + RAM capabilities):
            val isShieldTv = socIdentifier.contains("tegra") || socIdentifier.contains("foster") || socIdentifier.contains("darcy")
            val isDesktopOrEmulated = socIdentifier.contains("intel") || socIdentifier.contains("amd") || socIdentifier.contains("ryzen") || socIdentifier.contains("apple")

            val qualifiesForPerformance = when {
                // High RAM modern devices (>= 7.5GB RAM and 8+ cores)
                totalMb >= 7500 && cores >= 8 -> true
                // Flagship SoCs with sufficient RAM (>= 3.5GB RAM, or Shield TV / PC)
                isFlagshipChip && (totalMb >= 3500 || isShieldTv || isDesktopOrEmulated) -> true
                // Capable Mid-range SoCs with >= 3.5GB RAM when user enables Unrestricted Battery
                isMidChip && totalMb >= 3500 && isBatteryOptimizationDisabled -> true
                else -> false
            }

            if (qualifiesForPerformance) {
                return DeviceTier.HIGH
            }

            // 4. Default capable modern devices -> Balanced tier:
            DeviceTier.MID
        } catch (_: Throwable) {
            DeviceTier.MID
        }
    }
}
