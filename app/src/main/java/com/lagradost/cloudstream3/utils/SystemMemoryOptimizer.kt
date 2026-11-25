package com.lagradost.cloudstream3.utils

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier

/**
 * Hardware-Adaptive Internal Memory Optimizer.
 *
 * Reclaims heap & native caches within CloudStream itself (image memory cache,
 * idle HTTP connection pools, skip/classifier caches) without abusing
 * `killBackgroundProcesses` or triggering stop-the-world `System.gc()` stalls on UI/playback threads.
 */
object SystemMemoryOptimizer {
    private const val TAG = "SystemMemoryOptimizer"
    @Volatile
    private var lastOptimizationTimestamp = 0L
    private const val COOLDOWN_MS = 30_000L

    /**
     * Trims internal CloudStream caches safely when under low-end constraints or memory pressure.
     */
    fun reclaimBackgroundMemory(context: Context, force: Boolean = false, trimImageCache: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastOptimizationTimestamp < COOLDOWN_MS) {
            return
        }
        lastOptimizationTimestamp = now

        try {
            val appCtx = context.applicationContext ?: context
            if (force || trimImageCache) {
                try {
                    val memCache = SingletonImageLoader.get(appCtx).memoryCache
                    if (force) {
                        memCache?.clear()
                    } else {
                        memCache?.let { cache ->
                            cache.trimToSize(cache.size / 2)
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            try {
                app.baseClient.connectionPool.evictAll()
            } catch (_: Throwable) {
            }

            try {
                ImageLoader.evictConnections()
            } catch (_: Throwable) {
            }

            if (force) {
                try {
                    OnDeviceNsfwClassifier.release()
                } catch (_: Throwable) {
                }
                try {
                    com.lagradost.cloudstream3.utils.videoskip.SkipAPI.clearCache()
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Internal cache trim warning: ${t.message}")
        }
    }
}
