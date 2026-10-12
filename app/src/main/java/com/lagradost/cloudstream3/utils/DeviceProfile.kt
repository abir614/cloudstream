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
                maxRequests = 24,
                maxRequestsPerHost = 4,
                maxIdleConnections = 3,
                keepAliveDurationMinutes = 1L,
                connectTimeoutSeconds = 15L,
                readTimeoutSeconds = 25L
            )
            DeviceTier.MID -> NetworkTierConfig(
                maxRequests = 48,
                maxRequestsPerHost = 5,
                maxIdleConnections = 5,
                keepAliveDurationMinutes = 3L,
                connectTimeoutSeconds = 12L,
                readTimeoutSeconds = 20L
            )
            DeviceTier.HIGH -> NetworkTierConfig(
                maxRequests = 64,
                maxRequestsPerHost = 6,
                maxIdleConnections = 8,
                keepAliveDurationMinutes = 5L,
                connectTimeoutSeconds = 10L,
                readTimeoutSeconds = 20L
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
