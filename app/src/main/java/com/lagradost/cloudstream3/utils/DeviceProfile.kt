package com.lagradost.cloudstream3.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import java.io.File

enum class DeviceTier {
    LOW,
    MID,
    HIGH
}

data class RamCapability(
    val totalMemMb: Long,
    val availMemMb: Long,
    val isLowRamDevice: Boolean,
    val memoryClass: Int,
    val largeMemoryClass: Int,
    val swapMb: Long
)

data class CpuCapability(
    val supportedAbis: List<String>,
    val primaryAbi: String,
    val coreCount: Int,
    val is64Bit: Boolean,
    val chipIdentifier: String
)

data class DisplayCapability(
    val isTv: Boolean,
    val isTablet: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float
)

data class CodecCapabilities(
    val hasHardwareAvc: Boolean,
    val hasHardwareHevc: Boolean,
    val maxHevcHeight: Int,
    val hasHardwareVp9: Boolean,
    val hasHardwareAv1: Boolean,
    val hardwareDecoders: List<String>,
    val softwareDecoders: List<String>
)

data class StorageCapability(
    val freeSpaceMb: Long,
    val totalSpaceMb: Long
)

data class OsCapability(
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val kernelVersion: String
)

data class DeviceCapabilities(
    val tier: DeviceTier,
    val isUltraPotato: Boolean,
    val ram: RamCapability,
    val cpu: CpuCapability,
    val display: DisplayCapability,
    val codecs: CodecCapabilities,
    val storage: StorageCapability,
    val os: OsCapability
)

data class NetworkTierConfig(
    val maxRequests: Int,
    val maxRequestsPerHost: Int,
    val maxIdleConnections: Int,
    val keepAliveDurationMinutes: Long,
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val diskCacheMaxBytes: Long = 50L * 1024 * 1024
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

data class DynamicResourceBudget(
    val availRamMb: Long,
    val totalRamMb: Long,
    val freeHeapMb: Long,
    val cpuCores: Int,
    val isLowMemory: Boolean,
    val isLowEnd: Boolean,
    val viewportPreloadCount: Int,
    val preloaderConcurrency: Int,
    val pacingDelayMs: Long,
    val useRgb565: Boolean,
    val allowHardwareBitmaps: Boolean
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
    @Volatile
    private var cachedCpuInfo: String? = null
    @Volatile
    private var cachedIsLegacyKernel: Boolean? = null
    @Volatile
    private var cachedKernelVersion: String? = null
    @Volatile
    private var cachedZramSwapMb: Long? = null
    @Volatile
    private var cachedCapabilities: DeviceCapabilities? = null

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
        val isPotato = isUltraPotato(ctx)
        return when (tier(ctx)) {
            DeviceTier.LOW -> if (isPotato) {
                NetworkTierConfig(
                    maxRequests = 16,
                    maxRequestsPerHost = 4,
                    maxIdleConnections = 2,
                    keepAliveDurationMinutes = 1L,
                    connectTimeoutSeconds = 15L,
                    readTimeoutSeconds = 25L,
                    diskCacheMaxBytes = 15L * 1024 * 1024 // 15 MiB for ultra-potato storage
                )
            } else {
                NetworkTierConfig(
                    maxRequests = 32,
                    maxRequestsPerHost = 10,
                    maxIdleConnections = 6,
                    keepAliveDurationMinutes = 2L,
                    connectTimeoutSeconds = 15L,
                    readTimeoutSeconds = 25L,
                    diskCacheMaxBytes = 30L * 1024 * 1024 // 30 MiB
                )
            }
            DeviceTier.MID -> NetworkTierConfig(
                maxRequests = 64,
                maxRequestsPerHost = 16,
                maxIdleConnections = 12,
                keepAliveDurationMinutes = 4L,
                connectTimeoutSeconds = 12L,
                readTimeoutSeconds = 20L,
                diskCacheMaxBytes = 50L * 1024 * 1024 // 50 MiB
            )
            DeviceTier.HIGH -> NetworkTierConfig(
                maxRequests = 96,
                maxRequestsPerHost = 24,
                maxIdleConnections = 20,
                keepAliveDurationMinutes = 5L,
                connectTimeoutSeconds = 10L,
                readTimeoutSeconds = 20L,
                diskCacheMaxBytes = 100L * 1024 * 1024 // 100 MiB
            )
        }
    }

    fun getPlayerBufferConfig(ctx: Context): PlayerBufferConfig {
        val isPotato = isUltraPotato(ctx)
        return when (tier(ctx)) {
            DeviceTier.LOW -> if (isPotato) {
                PlayerBufferConfig(
                    minBufferMs = 6_000,
                    maxBufferMs = 15_000,
                    bufferForPlaybackMs = 1_000,
                    bufferForPlaybackAfterRebufferMs = 2_000,
                    targetBufferBytes = 6 * 1024 * 1024, // 6 MB for ultra-low 500MB devices
                    backBufferMs = 2_000
                )
            } else {
                PlayerBufferConfig(
                    minBufferMs = 10_000,
                    maxBufferMs = 25_000,
                    bufferForPlaybackMs = 1_500,
                    bufferForPlaybackAfterRebufferMs = 3_000,
                    targetBufferBytes = 12 * 1024 * 1024, // 12 MB
                    backBufferMs = 5_000
                )
            }
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
        val isPotato = isUltraPotato(ctx)
        return when (tier(ctx)) {
            DeviceTier.LOW -> CoilTierConfig(
                memoryCachePercent = if (isPotato) 0.05 else 0.10, // 5% RAM for 500MB devices
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

    /**
     * Real-time hardware and resource profiler.
     * Continuously tracks available physical RAM, active JVM heap headroom, CPU core availability,
     * and low-memory pressure to dynamically budget image preloading, worker thread concurrency,
     * and viewport caching limits.
     */
    fun getDynamicResourceBudget(ctx: Context): DynamicResourceBudget {
        val appContext = ctx.applicationContext ?: ctx
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val availMb = if (mi.availMem > 0) mi.availMem / (1024 * 1024) else 512L
        val totalMb = getTotalRamMb(appContext)
        val maxHeapMb = rt.maxMemory() / (1024 * 1024)
        val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val freeHeapMb = (maxHeapMb - usedHeapMb).coerceAtLeast(0L)
        val cores = rt.availableProcessors()
        val isPotato = isUltraPotato(appContext)
        val isLowEnd = isPotato || tier(appContext) == DeviceTier.LOW || (am?.isLowRamDevice == true) || totalMb <= 2500L
        val isLowMem = mi.lowMemory || availMb < 150L || freeHeapMb < 18L
        val hasBrokenGpu = hasBrokenHardwareGpu()

        return when {
            // Tier 1: Severe resource starvation (<150MB free RAM, <18MB free JVM heap, or Android lowMemory signal)
            isLowMem -> DynamicResourceBudget(
                availRamMb = availMb,
                totalRamMb = totalMb,
                freeHeapMb = freeHeapMb,
                cpuCores = cores,
                isLowMemory = true,
                isLowEnd = true,
                viewportPreloadCount = 4,
                preloaderConcurrency = 1,
                pacingDelayMs = 30L,
                useRgb565 = true,
                allowHardwareBitmaps = false
            )
            // Tier 2: Low-end / potato hardware (e.g. Allwinner H313 512MB-1GB RAM, Android TV sticks)
            isLowEnd -> DynamicResourceBudget(
                availRamMb = availMb,
                totalRamMb = totalMb,
                freeHeapMb = freeHeapMb,
                cpuCores = cores,
                isLowMemory = false,
                isLowEnd = true,
                viewportPreloadCount = if (isPotato) 4 else 6,
                preloaderConcurrency = 2,
                pacingDelayMs = if (isPotato) 20L else 10L,
                useRgb565 = true,
                allowHardwareBitmaps = false
            )
            // Tier 3: Mid-tier devices (e.g. 4-6GB RAM)
            totalMb <= 6000L -> DynamicResourceBudget(
                availRamMb = availMb,
                totalRamMb = totalMb,
                freeHeapMb = freeHeapMb,
                cpuCores = cores,
                isLowMemory = false,
                isLowEnd = false,
                viewportPreloadCount = 12,
                preloaderConcurrency = cores.coerceIn(8, 12),
                pacingDelayMs = 0L,
                useRgb565 = false,
                allowHardwareBitmaps = !hasBrokenGpu
            )
            // Tier 4: High-end flagship devices (8GB+ RAM, multi-core flagship SoC)
            else -> DynamicResourceBudget(
                availRamMb = availMb,
                totalRamMb = totalMb,
                freeHeapMb = freeHeapMb,
                cpuCores = cores,
                isLowMemory = false,
                isLowEnd = false,
                viewportPreloadCount = 16,
                preloaderConcurrency = cores.coerceIn(12, 20),
                pacingDelayMs = 0L,
                useRgb565 = false,
                allowHardwareBitmaps = !hasBrokenGpu
            )
        }
    }

    private val ALLWINNER_A_CHIPS = setOf("a10", "a20", "a23", "a31", "a33", "a50", "a64")
    private val ALLWINNER_H_CHIPS = setOf("h2", "h2+", "h3", "h5", "h6")

    private val SHORT_EXACT_CHIPS = setOf(
        "sun", "cape", "taro", "kona", "sunxi", "sun8i", "sun4i", "sun7i", "sun50i",
        "g99", "g100", "s805", "s905", "s905x", "s912", "gxl", "gxm", "gxbb",
        "t606", "t610", "t612", "t616", "t618", "t619", "t750", "t760", "t770", "t820", "t310",
        "intel", "amd", "apple", "ryzen", "tegra"
    )

    internal fun matchesChip(pattern: String, socIdentifier: String, socTokens: Set<String>, isAllwinnerPlatform: Boolean): Boolean {
        if (pattern in ALLWINNER_A_CHIPS || pattern in ALLWINNER_H_CHIPS) {
            return socIdentifier.contains("allwinner $pattern") || (isAllwinnerPlatform && pattern in socTokens)
        }
        if (pattern.contains(' ') || pattern.contains('-')) {
            return socIdentifier.contains(pattern) ||
                socIdentifier.contains(pattern.replace('-', ' ')) ||
                socIdentifier.contains(pattern.replace("-", ""))
        }
        if (pattern.length <= 4 || pattern in SHORT_EXACT_CHIPS) {
            return pattern in socTokens
        }
        return socIdentifier.contains(pattern) || pattern in socTokens
    }

    internal val KNOWN_LOW_CHIPS = listOf(
        // Allwinner Budget / TV / Tablets
        "sun50iw9", "sun50iw6", "sun50iw3", "sun50iw1", "sun50iw10", "sun50i", "sunxi", "sun8i", "sun4i", "sun5i", "sun7i", "allwinner",
        "h313", "h616", "h618", "h700", "h713", "h728", "h6", "h3", "h5", "h2", "h2+",
        "a133", "a133p", "a100", "a50", "a33", "a23", "a64", "a31", "a20", "a10",
        "t507", "t527",
        "dolphin", "petrel", "cheetah", "eagle", "tulip",
        // Rockchip Budget / TV / Tablets
        "rk3528", "rk3328", "rk3229", "rk3228a", "rk3228b", "rk3128", "rk3188", "rk3066", "rk3026", "rk3368", "rk30board", "rk312x",
        // Amlogic Budget / TV / Sticks
        "s805", "s805x", "s805x2", "s805y", "s812", "s802",
        "s905", "s905x", "s905w", "s905w2", "s905w4", "s905l", "s905l2", "s905l3", "s905l3b",
        "s905d", "s905d2", "s905d3", "s905y2", "s905y4", "s912",
        "t950d", "t950d4", "t962", "t962e", "t962x",
        "meson-gxbb", "meson-gxl", "meson-gxm", "meson-g12a", "meson64", "gxl", "gxm", "gxbb",
        // Unisoc / Spreadtrum Budget
        "sc7731", "sc9832", "sc9863", "sc9863a", "sc9850", "t310", "sprd", "sharkl", "sp9832", "sp7731",
        // MediaTek Budget / Legacy / TV Sticks
        "mt8695", "mt8693", "mt8516", "mt8167", "mt8127", "mt8163", "mt8168",
        "mt6580", "mt6582", "mt6592", "mt6735", "mt6737", "mt6739", "mt6750", "mt6752", "mt6753", "mt6755", "mt6757",
        "mt6761", "mt6762", "mt6765", "mt6765v",
        "helio a20", "helio a22", "helio g25", "helio g35", "helio g36", "helio g37", "helio p22", "helio p35",
        // Qualcomm Entry-level / Legacy
        "msm8909", "msm8916", "msm8917", "msm8920", "msm8937", "msm8940", "msm8952", "msm8953",
        "qm215", "sdm429", "sdm439", "sdm450", "sm4250",
        // Samsung Exynos Budget / Legacy
        "s5e3830", "exynos 850", "exynos 7904", "exynos 7884", "exynos 7870", "universal7870", "universal7884",
        // HiSilicon Kirin Budget / Legacy
        "kirin 710", "kirin 710f", "kirin 710a", "kirin 659", "kirin 650", "hi6260", "hi6250",
        // Realtek Budget / Legacy
        "rtd1195", "rtd1295", "rtd1296", "rtd1395",
        // Broadcom TV / IPTV
        "bcm7252", "bcm7252s", "bcm7260", "bcm7268", "bcm7271", "bcm72180", "bcm7445",
        // Synaptics TV
        "vs680", "vs640", "bg4ct", "bg5ct", "berlin",
        // Known Problematic GPUs & Budget TV Box Clones / Brands
        "mali-g31", "mali-400", "mali-450", "mali-t720", "mali-t820", "ge8300", "ge8320", "ge8100",
        "hy320", "hy300", "hy350", "a10plus", "magcubic", "sinoy", "android tv box",
        "x96", "x96q", "x96mini", "x96air", "x96max",
        "tanix", "tx3", "tx6", "tx6s", "tx9", "tx1",
        "t95", "t95max", "t95z", "t95plus",
        "hk1", "hk1box", "hk1max", "hk1mini",
        "h96", "h96max", "h96mini",
        "mxq", "mxqpro", "mxq-4k", "mx10",
        "v88", "v88pro",
        "a95x", "a95x-f3", "a95x-f4",
        "transpeed", "dq03", "dq08", "onten", "vontar", "lemfo"
    )

    internal val KNOWN_FLAGSHIP_CHIPS = listOf(
        // Qualcomm Flagships (800 series, 8 Gen 1/2/3/4/Elite, 7+ Gen 2/3, Handhelds, XR)
        "sm8750", "sm8650", "sm8550", "sm8475", "sm8450", "sm8350", "sm8250", "sm8150", "sdm845",
        "sm7675", "sm7475",
        "qcs8550", "sg4150p", "sm8350au", "xr2",
        "sun", "pineapple", "kalama", "cape", "taro", "lahaina", "kona", "msmnile",
        "snapdragon 8 elite", "snapdragon 8 gen 4", "snapdragon 8 gen 3", "snapdragon 8 gen 2",
        "snapdragon 8+ gen 1", "snapdragon 8 gen 1", "snapdragon 888", "snapdragon 870", "snapdragon 865", "snapdragon 855",
        "snapdragon 7+ gen 3", "snapdragon 7+ gen 2", "snapdragon 7+",
        // MediaTek Dimensity Flagships & Upper-tier
        "dimensity 9400", "dimensity 9300", "dimensity 9200", "dimensity 9000",
        "dimensity 8300", "dimensity 8200", "dimensity 8100", "dimensity 8050", "dimensity 8020", "dimensity 8000",
        "dimensity 1300", "dimensity 1200", "dimensity 1100", "dimensity 1000",
        "mt6991", "mt6989", "mt6985", "mt6983", "mt6897", "mt6896", "mt6895", "mt6893", "mt6891", "mt6889",
        // Google Tensor (Pixel 6 through Pixel 9 Pro Fold)
        "tensor g4", "tensor g3", "tensor g2", "tensor",
        "gs101", "gs201", "zuma", "zumapro", "whitechapel", "cloudripper",
        "caiman", "komodo", "tokay", "shiba", "husky", "akita",
        "cheetah", "panther", "lynx", "felix", "tangorpro",
        "oriole", "raven", "bluejay",
        // Samsung Exynos Flagships
        "exynos 2500", "exynos 2400", "exynos 2200", "exynos 2100", "exynos 990", "exynos 9825", "exynos 9820", "exynos 9810",
        "s5e9945", "s5e9925", "s5e9840", "s5e9830", "s5e9825", "s5e9820", "s5e9810",
        // HiSilicon Kirin Flagships
        "kirin 9010", "kirin 9000s", "kirin 9000", "kirin 9000e", "kirin 990", "kirin 985", "kirin 980",
        "hi36a0", "hi3690", "hi3680",
        // Nvidia Tegra (Shield TV / Shield TV Pro)
        "tegra", "tegra210", "tegra210b01", "foster", "darcy", "mdarcy",
        // PC / Laptop / Desktop / Emulator Processors
        "intel", "amd", "ryzen", "core(tm)", "apple", "x86_64"
    )

    internal val KNOWN_MID_CHIPS = listOf(
        // MediaTek Helio G series (including Helio G100 Ultimate)
        "g100 ultimate", "g100-ultimate", "helio g100", "helio-g100", "g100",
        "g99 ultimate", "g99-ultimate", "g99 ultra", "helio g99", "helio-g99", "g99",
        "helio g96", "helio g95", "helio g90t", "helio g90", "helio g88", "helio g85", "helio g80", "helio g70",
        "helio p95", "helio p90", "helio p70", "helio p65", "helio p60",
        "mt6789", "mt6789v", "mt6785", "mt6781", "mt6769", "mt6769t", "mt6769z", "mt6768", "mt6779", "mt6771",
        // MediaTek Dimensity Midrange
        "dimensity 7300", "dimensity 7200", "dimensity 7050", "dimensity 7025", "dimensity 7020", "dimensity 7000",
        "dimensity 6300", "dimensity 6100", "dimensity 6080", "dimensity 6020",
        "dimensity 1080", "dimensity 930", "dimensity 920", "dimensity 900", "dimensity 810", "dimensity 800", "dimensity 720", "dimensity 700",
        "mt6878", "mt6886", "mt6877", "mt6877v", "mt6855", "mt6835", "mt6833", "mt6833p", "mt6853", "mt6873",
        // MediaTek Tablets / Kompanio / Smart TV
        "kompanio", "mt8788", "mt8781", "mt8183", "mt8188", "mt8768",
        "mt8696", "mt8696t", "pentonic", "mt9972", "mt9638", "mt9653", "mt5895", "mt5896",
        // Qualcomm Snapdragon 7 series
        "snapdragon 7 gen 3", "snapdragon 7s gen 3", "snapdragon 7s gen 2", "snapdragon 7 gen 1",
        "snapdragon 782g", "snapdragon 780g", "snapdragon 778g", "snapdragon 768g", "snapdragon 765g", "snapdragon 750g", "snapdragon 732g", "snapdragon 730g", "snapdragon 720g",
        "sm7550", "sm7635", "sm7435", "sm7450", "sm7325", "sm7350", "sm7250", "sm7225", "sm7150", "sm7125", "sdm710",
        // Qualcomm Snapdragon 6 series
        "snapdragon 6 gen 3", "snapdragon 6s gen 3", "snapdragon 6 gen 1",
        "snapdragon 695", "snapdragon 685", "snapdragon 680", "snapdragon 678", "snapdragon 675", "snapdragon 670", "snapdragon 665", "snapdragon 662", "snapdragon 660",
        "sm6650", "volcano", "sm6475", "sm6450", "sm6375", "sm6225", "sm6150", "sm6125", "sm6115", "sdm660", "sdm636", "bengal", "yupik", "lito",
        // Qualcomm Snapdragon 4 series (Modern 5G)
        "snapdragon 4 gen 2", "snapdragon 4s gen 2", "snapdragon 4 gen 1", "snapdragon 480",
        "sm4450", "sm4375", "sm4350", "holi",
        // Samsung Exynos Midrange
        "exynos 1580", "exynos 1480", "exynos 1380", "exynos 1330", "exynos 1280", "exynos 1080", "exynos 980", "exynos 880",
        "s5e8855", "s5e8845", "s5e8835", "s5e8825", "s5e8535", "s5e8830", "s5e9630", "s5e8805",
        // Unisoc Modern Midrange
        "unisoc t820", "unisoc t770", "unisoc t760", "unisoc t750",
        "unisoc t619", "unisoc t618", "unisoc t616", "unisoc t612", "unisoc t610", "unisoc t606",
        "t820", "t770", "t760", "t750", "t619", "t618", "t616", "t612", "t610", "t606",
        "ums9620", "ums9230", "ums512",
        // HiSilicon Kirin Midrange
        "kirin 820", "kirin 810", "hi6280",
        // Rockchip Midrange / Upper TV
        "rk3588", "rk3588s", "rk3576", "rk3568", "rk3566", "rk3399", "rk3399pro",
        // Amlogic Midrange / Modern 4K/8K TV
        "s928x", "s928x-j", "s922x", "s922x-j", "s922x-h", "a311d", "a311d2",
        "s905x5", "s905x5m", "s905x4", "s905x3", "s905x2",
        "meson-sm1", "meson-g12b", "meson-tm2",
        "t968", "t972", "t982",
        // Realtek Midrange
        "rtd1619", "rtd1619b", "rtd1619dr", "rtd1319", "rtd1319d"
    )

    fun getCpuInfoString(): String {
        cachedCpuInfo?.let { return it }
        val info = try {
            val file = File("/proc/cpuinfo")
            if (file.exists() && file.canRead()) {
                var res = ""
                file.useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("Hardware", ignoreCase = true) || line.startsWith("model name", ignoreCase = true)) {
                            val parts = line.split(":")
                            if (parts.size >= 2) {
                                val candidate = parts[1].trim()
                                if (candidate.isNotEmpty()) {
                                    res = candidate
                                    break
                                }
                            }
                        }
                    }
                }
                res
            } else {
                ""
            }
        } catch (_: Throwable) {
            ""
        }
        cachedCpuInfo = info
        return info
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
            "x96", "tanix", "t95", "hk1", "h96", "mxq", "v88", "tx3", "tx6", "tx20", "a95x"
        )
        val target = "$hardware $board $manufacturer $soc $model"
        val tokens = target.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        return brokenGpuPatterns.any { pattern ->
            if (pattern.length <= 3) {
                pattern in tokens
            } else {
                pattern in target
            }
        }
    }

    fun resetCache() {
        synchronized(this) {
            cachedTier = null
            cachedOverride = null
            cachedBatteryRestricted = null
            cachedCapabilities = null
        }
    }

    /**
     * Probes dynamic hardware decoding capabilities querying MediaCodecList.
     * Wrapped in a failsafe boundary to guarantee zero crashes on non-standard Android TV firmware.
     */
    fun probeCodecs(): CodecCapabilities {
        return try {
            val codecList = if (Build.VERSION.SDK_INT >= 21) {
                android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            } else null

            var hasAvc = false
            var hasHevc = false
            var maxHevcH = 1080
            var hasVp9 = false
            var hasAv1 = false
            val hwList = mutableListOf<String>()
            val swList = mutableListOf<String>()

            codecList?.codecInfos?.forEach { info ->
                if (!info.isEncoder) {
                    val isHw = if (Build.VERSION.SDK_INT >= 29) {
                        info.isHardwareAccelerated
                    } else {
                        val name = info.name.lowercase()
                        !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.startsWith("omx.ffmpeg.")
                    }
                    if (isHw) {
                        hwList.add(info.name)
                    } else {
                        swList.add(info.name)
                    }

                    val types = try { info.supportedTypes } catch (_: Throwable) { emptyArray() }
                    for (type in types) {
                        when (type.lowercase()) {
                            "video/avc" -> if (isHw) hasAvc = true
                            "video/hevc" -> {
                                if (isHw) {
                                    hasHevc = true
                                    try {
                                        val caps = info.getCapabilitiesForType(type)?.videoCapabilities
                                        if (caps != null) {
                                            val maxH = caps.supportedHeights.upper
                                            if (maxH > maxHevcH) {
                                                maxHevcH = maxH
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                            "video/x-vnd.on2.vp9" -> if (isHw) hasVp9 = true
                            "video/av01" -> if (isHw) hasAv1 = true
                        }
                    }
                }
            }

            CodecCapabilities(
                hasHardwareAvc = hasAvc,
                hasHardwareHevc = hasHevc,
                maxHevcHeight = maxHevcH,
                hasHardwareVp9 = hasVp9,
                hasHardwareAv1 = hasAv1,
                hardwareDecoders = hwList.distinct().take(15),
                softwareDecoders = swList.distinct().take(15)
            )
        } catch (_: Throwable) {
            CodecCapabilities(
                hasHardwareAvc = true,
                hasHardwareHevc = false,
                maxHevcHeight = 1080,
                hasHardwareVp9 = false,
                hasHardwareAv1 = false,
                hardwareDecoders = emptyList(),
                softwareDecoders = emptyList()
            )
        }
    }

    /**
     * Computes the complete single source-of-truth hardware profile asynchronously or cached.
     */
    fun getDeviceCapabilities(ctx: Context): DeviceCapabilities {
        cachedCapabilities?.let { return it }
        val appContext = ctx.applicationContext ?: ctx

        val tier = tier(appContext)
        val potato = isUltraPotato(appContext)

        val ram = try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            RamCapability(
                totalMemMb = (mi.totalMem / (1024 * 1024)),
                availMemMb = (mi.availMem / (1024 * 1024)),
                isLowRamDevice = am?.isLowRamDevice ?: false,
                memoryClass = am?.memoryClass ?: 128,
                largeMemoryClass = am?.largeMemoryClass ?: 256,
                swapMb = getZramSwapMb()
            )
        } catch (_: Throwable) {
            RamCapability(1024, 512, false, 128, 256, 0)
        }

        val cpu = try {
            val abis = Build.SUPPORTED_ABIS?.toList() ?: listOf(Build.CPU_ABI ?: "armeabi-v7a")
            val primary = abis.firstOrNull() ?: "armeabi-v7a"
            val cores = Runtime.getRuntime().availableProcessors()
            val is64 = Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() ?: false
            CpuCapability(
                supportedAbis = abis,
                primaryAbi = primary,
                coreCount = cores,
                is64Bit = is64,
                chipIdentifier = getCpuInfoString()
            )
        } catch (_: Throwable) {
            CpuCapability(listOf("armeabi-v7a"), "armeabi-v7a", 4, false, "")
        }

        val display = try {
            val dm = appContext.resources.displayMetrics
            val isTv = try {
                val uiMode = appContext.resources.configuration.uiMode
                (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            } catch (_: Throwable) { false }
            val isTablet = try {
                appContext.resources.configuration.smallestScreenWidthDp >= 600
            } catch (_: Throwable) { false }

            val externalState = try {
                com.lagradost.cloudstream3.utils.display.DisplayTopologyManager.displayState.value
            } catch (_: Throwable) { null }
            val activeMon = externalState?.activeMonitor
            val effectiveWidth = activeMon?.physicalWidth ?: dm.widthPixels
            val effectiveHeight = activeMon?.physicalHeight ?: dm.heightPixels
            val effectiveRefresh = activeMon?.currentRefreshRate ?: 60.0f

            DisplayCapability(
                isTv = isTv,
                isTablet = isTablet,
                widthPx = effectiveWidth,
                heightPx = effectiveHeight,
                densityDpi = dm.densityDpi,
                refreshRateHz = effectiveRefresh
            )
        } catch (_: Throwable) {
            DisplayCapability(false, false, 1280, 720, 160, 60.0f)
        }

        val codecs = probeCodecs()

        val storage = try {
            val stat = android.os.StatFs(appContext.filesDir.absolutePath)
            val free = stat.availableBytes / (1024 * 1024)
            val total = stat.totalBytes / (1024 * 1024)
            StorageCapability(free, total)
        } catch (_: Throwable) {
            StorageCapability(512, 4096)
        }

        val os = OsCapability(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            model = Build.MODEL ?: "Unknown",
            kernelVersion = getKernelVersion()
        )

        val caps = DeviceCapabilities(
            tier = tier,
            isUltraPotato = potato,
            ram = ram,
            cpu = cpu,
            display = display,
            codecs = codecs,
            storage = storage,
            os = os
        )
        cachedCapabilities = caps
        return caps
    }

    /**
     * Determines maximum safe video playback resolution for the current hardware.
     * Prevents low-memory SoCs (e.g. Allwinner H313) from crash-looping on 4K streams.
     */
    fun getMaxSupportedVideoHeight(ctx: Context): Int {
        return try {
            if (isUltraPotato(ctx) || getDeviceCapabilities(ctx).codecs.maxHevcHeight <= 1080) {
                1080
            } else {
                2160
            }
        } catch (_: Throwable) {
            1080
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

    fun getDownloadBufferLimit(ctx: Context): Long {
        val isPotato = isUltraPotato(ctx)
        return when (tier(ctx)) {
            DeviceTier.HIGH -> 80_000_000L  // 80 MB
            DeviceTier.MID -> 35_000_000L   // 35 MB
            DeviceTier.LOW -> if (isPotato) 5_000_000L else 10_000_000L   // 5 MB on potato, 10 MB on low
        }
    }

    fun isLegacyLinuxKernel(): Boolean {
        cachedIsLegacyKernel?.let { return it }
        val kernel = getKernelVersion()
        val match = Regex("""(\d+)\.(\d+)""").find(kernel)
        val legacy = if (match != null) {
            val major = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 4
            val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 14
            major < 4 || (major == 4 && minor < 9)
        } else false
        cachedIsLegacyKernel = legacy
        return legacy
    }

    fun isUltraPotato(ctx: Context?): Boolean {
        if (ctx == null) return false
        val totalMb = getTotalRamMb(ctx)

        // 1. Extreme physical memory starvation: <= 1024MB RAM is always ultra-potato
        if (totalMb <= 1024) return true

        val swapMb = getZramSwapMb()
        val isLowRam = try {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        } catch (_: Throwable) {
            false
        }

        // 2. Hardware capability check: A device with capable hardware (flagship, mid-tier SoC,
        // or 64-bit multi-core CPU with >= 1.8GB RAM) is NEVER an ultra-potato, regardless of kernel age.
        // An older kernel alone does not justify demoting capable hardware.
        val cores = Runtime.getRuntime().availableProcessors()
        val has64Bit = try { Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() ?: true } catch (_: Throwable) { true }

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
        val socTokens = socIdentifier.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        val isAllwinnerPlatform = socTokens.contains("allwinner") || socTokens.contains("sunxi") || socTokens.any { it.startsWith("sun") && it.length > 3 }

        val isCapableSoC = KNOWN_FLAGSHIP_CHIPS.any { pattern ->
            matchesChip(pattern, socIdentifier, socTokens, isAllwinnerPlatform)
        } || KNOWN_MID_CHIPS.any { pattern ->
            matchesChip(pattern, socIdentifier, socTokens, isAllwinnerPlatform)
        }

        if (isCapableSoC || (totalMb >= 1800 && has64Bit && cores >= 4)) {
            return false
        }

        // 3. Hardware + Kernel combination: An older kernel (< 4.9) only triggers ultra-potato
        // when the hardware itself is also constrained (e.g. <= 1536MB RAM, 32-bit only, or < 4 cores).
        val isLegacyKernel = isLegacyLinuxKernel()
        val isConstrainedRam = (isLowRam && totalMb <= 1536) || (totalMb <= 1536 && swapMb == 0L)
        val isLegacyWeakHardware = isLegacyKernel && (totalMb <= 1536 || !has64Bit || cores < 4)

        return isConstrainedRam || isLegacyWeakHardware
    }

    fun getSearchConcurrency(ctx: Context?): Int {
        if (ctx == null) return 8
        val isPotato = isUltraPotato(ctx)
        return when (tier(ctx)) {
            DeviceTier.HIGH -> 16
            DeviceTier.MID -> 8
            DeviceTier.LOW -> if (isPotato) 2 else 3
        }
    }

    fun getItemViewCacheSize(ctx: Context): Int {
        val isPotato = isUltraPotato(ctx)
        return when (tier(ctx)) {
            DeviceTier.HIGH -> 8
            DeviceTier.MID -> 4
            DeviceTier.LOW -> if (isPotato) 2 else 3
        }
    }

    fun getInitialPrefetchItemCount(ctx: Context?): Int {
        if (ctx == null) return 4
        return when {
            isUltraPotato(ctx) -> 2
            tier(ctx) == DeviceTier.HIGH -> 6
            else -> 4
        }
    }

    fun getKernelVersion(): String {
        cachedKernelVersion?.let { return it }
        // Query os.version property first - compliant with Android SELinux and avoids proc_version AVC denials
        val osVersion = try {
            System.getProperty("os.version")?.trim()
        } catch (_: Throwable) {
            null
        }
        if (!osVersion.isNullOrBlank()) {
            val match = Regex("""(\d+\.\d+\.\d+)""").find(osVersion)
            val parsed = match?.groupValues?.getOrNull(1) ?: osVersion.take(30)
            cachedKernelVersion = parsed
            return parsed
        }

        val version = try {
            val file = File("/proc/version")
            if (file.exists() && file.canRead()) {
                val line = file.readText().trim()
                val match = Regex("""Linux version (\d+\.\d+\.\d+)""").find(line)
                match?.groupValues?.getOrNull(1) ?: line.take(30)
            } else {
                "Linux"
            }
        } catch (_: Throwable) {
            "Linux"
        }
        cachedKernelVersion = version
        return version
    }

    fun getZramSwapMb(): Long {
        cachedZramSwapMb?.let { return it }
        val swap = try {
            val file = File("/proc/meminfo")
            if (file.exists() && file.canRead()) {
                var kb = 0L
                file.useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("SwapTotal:", ignoreCase = true)) {
                            val parts = line.split("\\s+".toRegex())
                            if (parts.size >= 2) {
                                kb = parts[1].toLongOrNull() ?: 0L
                                break
                            }
                        }
                    }
                }
                kb / 1024L
            } else {
                0L
            }
        } catch (_: Throwable) {
            0L
        }
        cachedZramSwapMb = swap
        return swap
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
            val kernel = getKernelVersion()
            val swapMb = getZramSwapMb()
            val swapInfo = if (swapMb > 0) " • ${swapMb}MB zRAM" else ""
            val isPotato = isUltraPotato(ctx)
            val hwMode = when (currentTier) {
                DeviceTier.HIGH -> "Performance • 128MB Buffers • Max Concurrency"
                DeviceTier.MID -> "Balanced • 48MB Buffers • Standard Acceleration"
                DeviceTier.LOW -> if (isPotato) "Potato Safe • RGB_565 • 6MB Buffers" else "Power Safe • RGB_565 (50% RAM Save) • 12MB Buffers"
            }

            "[$badge] ${totalMb} MB RAM$swapInfo • $cores Cores ($arch)\nKernel: $kernel • Battery: $batteryLabel • $hwMode"
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
            val socTokens = socIdentifier.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()

            val isAllwinnerPlatform = socTokens.contains("allwinner") || socTokens.contains("sunxi") || socTokens.any { it.startsWith("sun") && it.length > 3 }

            val isLowChip = KNOWN_LOW_CHIPS.any { pattern ->
                matchesChip(pattern, socIdentifier, socTokens, isAllwinnerPlatform)
            }
            val isFlagshipChip = KNOWN_FLAGSHIP_CHIPS.any { pattern ->
                matchesChip(pattern, socIdentifier, socTokens, isAllwinnerPlatform)
            }
            val isMidChip = KNOWN_MID_CHIPS.any { pattern ->
                matchesChip(pattern, socIdentifier, socTokens, isAllwinnerPlatform)
            }

            val totalMb = getTotalRamMb(ctx)
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val isLowRam = am?.isLowRamDevice ?: false
            val has64Bit = try { Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() ?: true } catch (_: Throwable) { true }
            val cores = Runtime.getRuntime().availableProcessors()
            val isBrokenGpu = hasBrokenHardwareGpu()

            val isPotato = isUltraPotato(ctx)

            // 1. Weak / Low-memory / Potato hardware check:
            if (isLowChip || isBrokenGpu || isLowRam || totalMb <= 2048 || (!has64Bit && totalMb <= 3072) || cores < 4 || isPotato) {
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
