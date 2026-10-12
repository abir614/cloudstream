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
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            if (am != null) {
                am.getMemoryInfo(mi)
                val totalMb = mi.totalMem / (1024 * 1024)
                val isLowRam = am.isLowRamDevice
                val has64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

                when {
                    isLowRam || totalMb <= 2048 || !has64Bit -> DeviceTier.LOW
                    totalMb <= 4096 -> DeviceTier.MID
                    else -> DeviceTier.HIGH
                }
            } else {
                val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
                if (maxHeapMb <= 192) DeviceTier.LOW else DeviceTier.MID
            }
        } catch (_: Throwable) {
            DeviceTier.MID
        }
    }
}
