package com.lagradost.cloudstream3.ui.coordinators

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics

/**
 * Material 3 Window Width Size Class specification.
 */
enum class WindowWidthSizeClass {
    /** Most phones in portrait (< 600dp) */
    COMPACT,
    /** Small tablets, foldables unfolded, large phones in landscape (600dp - 839dp) */
    MEDIUM,
    /** Tablets, desktop environments, TVs (>= 840dp) */
    EXPANDED
}

/**
 * Material 3 Window Height Size Class specification.
 */
enum class WindowHeightSizeClass {
    /** Most phones in landscape (< 480dp) */
    COMPACT,
    /** Most phones in portrait, small tablets (480dp - 899dp) */
    MEDIUM,
    /** Tablets, desktop in portrait (>= 900dp) */
    EXPANDED
}

/**
 * Detected physical device category.
 */
enum class DeviceFormFactor {
    PHONE,
    FOLDABLE_UNFOLDED,
    TABLET,
    TELEVISION
}

/**
 * Decoupled coordinator for dynamic Window Size Classes, responsive layout adaptation,
 * and adaptive grid column scaling across Foldables, Tablets, Phones, and TVs.
 */
object WindowSizeCoordinator {

    /** Breakpoint thresholds in density-independent pixels (dp) based on Material 3 guidelines */
    const val BREAKPOINT_COMPACT_WIDTH_DP = 600f
    const val BREAKPOINT_MEDIUM_WIDTH_DP = 840f
    const val BREAKPOINT_TABLET_SW_DP = 600

    /**
     * Computes the current window width in density-independent pixels (dp).
     * Prioritizes configuration.screenWidthDp to accurately reflect multi-window / split-screen bounds.
     */
    fun computeWindowWidthDp(context: Context): Float {
        val configWidth = try { context.resources.configuration.screenWidthDp.toFloat() } catch (_: Throwable) { 0f }
        if (configWidth > 0f) return configWidth
        val dm = context.resources.displayMetrics
        val widthPx = dm.widthPixels.toFloat()
        val density = if (dm.density > 0) dm.density else 1.0f
        return widthPx / density
    }

    /**
     * Computes the current window height in density-independent pixels (dp).
     * Prioritizes configuration.screenHeightDp to accurately reflect multi-window / split-screen bounds.
     */
    fun computeWindowHeightDp(context: Context): Float {
        val configHeight = try { context.resources.configuration.screenHeightDp.toFloat() } catch (_: Throwable) { 0f }
        if (configHeight > 0f) return configHeight
        val dm = context.resources.displayMetrics
        val heightPx = dm.heightPixels.toFloat()
        val density = if (dm.density > 0) dm.density else 1.0f
        return heightPx / density
    }

    /**
     * Resolves the [WindowWidthSizeClass] based on window width in dp.
     */
    fun resolveWidthSizeClass(widthDp: Float): WindowWidthSizeClass {
        return when {
            widthDp < BREAKPOINT_COMPACT_WIDTH_DP -> WindowWidthSizeClass.COMPACT
            widthDp < BREAKPOINT_MEDIUM_WIDTH_DP -> WindowWidthSizeClass.MEDIUM
            else -> WindowWidthSizeClass.EXPANDED
        }
    }

    /**
     * Resolves the [WindowHeightSizeClass] based on window height in dp.
     */
    fun resolveHeightSizeClass(heightDp: Float): WindowHeightSizeClass {
        return when {
            heightDp < 480f -> WindowHeightSizeClass.COMPACT
            heightDp < 900f -> WindowHeightSizeClass.MEDIUM
            else -> WindowHeightSizeClass.EXPANDED
        }
    }

    /**
     * Identifies the physical device category.
     */
    fun resolveFormFactor(context: Context, isTv: Boolean): DeviceFormFactor {
        if (isTv) return DeviceFormFactor.TELEVISION

        val config = context.resources.configuration
        val swDp = config.smallestScreenWidthDp
        val widthDp = computeWindowWidthDp(context)

        return when {
            swDp >= 720 || widthDp >= 900f -> DeviceFormFactor.TABLET
            swDp >= BREAKPOINT_TABLET_SW_DP -> {
                // If aspect ratio is close to 1:1 or 4:3 (typical for unfolded foldables like Z Fold, Pixel Fold)
                val dm = context.resources.displayMetrics
                val maxDim = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
                val minDim = minOf(dm.widthPixels, dm.heightPixels).toFloat()
                val ratio = if (minDim > 0) maxDim / minDim else 1.77f
                if (ratio <= 1.4f) {
                    DeviceFormFactor.FOLDABLE_UNFOLDED
                } else {
                    DeviceFormFactor.TABLET
                }
            }
            else -> DeviceFormFactor.PHONE
        }
    }

    /**
     * Dynamically calculates optimal grid span counts (columns) for content items.
     * Prevents image stretching on Foldables, Tablets, and TVs.
     *
     * @param context Android context
     * @param isHorizontalItem true for 16:9 banner cards; false for standard 2:3 vertical posters
     * @param isTv whether TV 10-foot UI is active
     */
    fun calculateGridSpanCount(
        context: Context,
        isHorizontalItem: Boolean = false,
        isTv: Boolean = false
    ): Int {
        if (isTv) {
            return if (isHorizontalItem) 4 else 7
        }

        val widthDp = computeWindowWidthDp(context)
        val widthClass = resolveWidthSizeClass(widthDp)
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        return when (widthClass) {
            WindowWidthSizeClass.COMPACT -> {
                if (isHorizontalItem) {
                    if (isLandscape) 3 else 2
                } else {
                    if (isLandscape) 5 else 3
                }
            }
            WindowWidthSizeClass.MEDIUM -> {
                // Foldable unfolded / Mini-tablet
                if (isHorizontalItem) {
                    if (isLandscape) 4 else 3
                } else {
                    if (isLandscape) 6 else 4
                }
            }
            WindowWidthSizeClass.EXPANDED -> {
                // Large Tablet / Desktop window
                if (isHorizontalItem) {
                    if (isLandscape) 5 else 4
                } else {
                    if (isLandscape) 8 else 6
                }
            }
        }
    }

    /**
     * Determines whether the layout should show a side navigation rail/drawer
     * instead of a bottom navigation bar.
     */
    fun shouldShowNavigationRail(context: Context, isTv: Boolean): Boolean {
        if (isTv) return false
        val widthDp = computeWindowWidthDp(context)
        return resolveWidthSizeClass(widthDp) != WindowWidthSizeClass.COMPACT
    }

    /**
     * Determines whether the layout should use a dual-pane master-detail presentation
     * (e.g. details + episode list side-by-side on Tablets & Foldables).
     */
    fun shouldUseDualPane(context: Context, isTv: Boolean): Boolean {
        if (isTv) return false
        val widthDp = computeWindowWidthDp(context)
        val widthClass = resolveWidthSizeClass(widthDp)
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return widthClass == WindowWidthSizeClass.EXPANDED || (widthClass == WindowWidthSizeClass.MEDIUM && isLandscape)
    }

    /**
     * Checks if the activity is currently running in Android Multi-Window / Split-Screen mode.
     */
    fun isMultiWindow(activity: android.app.Activity?): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity?.isInMultiWindowMode == true
        } else {
            false
        }
    }

    /**
     * Detects desktop-like environments (ChromeOS ARC container, Samsung DeX, PC form factors).
     */
    fun isChromeOsOrDesktop(context: Context): Boolean {
        val pm = context.packageManager
        val isArc = pm.hasSystemFeature("org.chromium.arc")
        val isPc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PC)
        val isSamsungDeX = try {
            val config = context.resources.configuration
            val semDesktopModeEnabled = config.javaClass.getField("semDesktopModeEnabled").getInt(config)
            semDesktopModeEnabled == 1
        } catch (_: Throwable) {
            false
        }
        return isArc || isPc || isSamsungDeX
    }

    /**
     * Calculates the optimal responsive dialog width percentage based on screen size class.
     */
    fun getResponsiveDialogWidthPercent(context: Context, isTv: Boolean): Float {
        if (isTv) return 0.60f
        val widthDp = computeWindowWidthDp(context)
        return when (resolveWidthSizeClass(widthDp)) {
            WindowWidthSizeClass.COMPACT -> 0.92f
            WindowWidthSizeClass.MEDIUM -> 0.70f
            WindowWidthSizeClass.EXPANDED -> 0.50f
        }
    }
}
