package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppDebug
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ImageLoader.buildImageLoader
import java.io.File
import java.lang.ref.WeakReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ExceptionHandler(
    val appContext: Context,
    val errorFile: File,
    val onError: (() -> Unit)
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        var fullReport: String? = null
        try {
            val threadId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                thread.threadId()
            } else {
                @Suppress("DEPRECATION")
                thread.id
            }

            try {
                fullReport = com.lagradost.cloudstream3.di.ServiceLocator.telemetry.captureCrash(error, errorFile)
            } catch (_: Throwable) {
                // Fallback to basic printstream if telemetry fails
                val sw = java.io.StringWriter()
                val ps = java.io.PrintWriter(sw)
                ps.println("Currently loading extension: ${PluginManager.currentlyLoading ?: "none"}")
                ps.println("Fatal exception on thread ${thread.name} ($threadId)")
                error.printStackTrace(ps)
                fullReport = sw.toString()
                try {
                    errorFile.writeText(fullReport)
                } catch (_: Throwable) {
                }
            }

            // CRITICAL STEP 1: Persist crash tombstone to disk synchronously BEFORE network dispatch
            try {
                com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.markSessionHandledCrash(appContext)
                com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.saveCrashTombstone(
                    context = appContext,
                    error = error,
                    thread = thread,
                    fullLog = fullReport
                )
            } catch (_: Throwable) {
            }

            // CRITICAL STEP 2: Emergency synchronous network dispatch on dedicated worker thread
            // with 3.5s join to prevent NetworkOnMainThreadException and guarantee real-time delivery
            try {
                val sent = com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.dispatchSyncWithTimeout(
                    context = appContext,
                    thread = thread,
                    error = error,
                    fullLog = fullReport,
                    timeoutMs = 3500L
                )
                if (sent) {
                    com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.clearPendingReport(appContext)
                }
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
        try {
            onError()
        } catch (_: Exception) {
        }
        exitProcess(1)
    }
}

class CloudStreamApp : Application(), SingletonImageLoader.Factory {

    private fun isMainAppProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (Process.isIsolated()) return false
            return getProcessName() == packageName
        }
        return try {
            val cmdline = File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n', '\r')
            cmdline.isEmpty() || cmdline == packageName
        } catch (_: Throwable) {
            true
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        // Never run host UI, SQLite, preferences, telemetry, or sandbox-client init inside :plugin_sandbox or isolated processes
        if (!isMainAppProcess()) {
            return
        }
        com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.init(this)

        // Bridge all caught exceptions from library & extractors into Telemetry
        com.lagradost.cloudstream3.mvvm.errorReporter = { tag, message, throwable ->
            com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.dispatchDiagnosticLogAsync(
                level = "ERROR",
                tag = tag,
                message = message,
                throwable = throwable
            )
        }

        // Start background auto-logcat monitor for real-time warning/error detection
        try {
            if (com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.isTelemetryEnabled(this)) {
                com.lagradost.cloudstream3.utils.telemetry.AutoLogcatMonitor.start(this)
            }
        } catch (_: Throwable) {
        }

        ExceptionHandler(this, filesDir.resolve("last_error")) {
            val intent = this.packageManager.getLaunchIntentForPackage(this.packageName)
            intent?.component?.let { comp ->
                startActivity(Intent.makeRestartActivityTask(comp))
            }
        }.also {
            exceptionHandler = it
            Thread.setDefaultUncaughtExceptionHandler(it)
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) {
                    com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.recordSessionHeartbeat(
                        activity,
                        stage = "FOREGROUND: ${activity.javaClass.simpleName}",
                        isForeground = true
                    )
                }
            }

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.recordSessionHeartbeat(
                        activity,
                        stage = "BACKGROUND",
                        isForeground = false
                    )
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity && activity.isFinishing) {
                    com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.markSessionGracefulExit(activity)
                }
            }
        })

        // Flush any unsent crash dumps from previous sessions if telemetry is enabled
        if (com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.isTelemetryEnabled(this)) {
            com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter.dispatchPendingOnStartup(this)
        }

        AppDebug.isDebug = BuildConfig.DEBUG

        // Warm up isolated ExtensionSandboxService and register core application context & SQLite persistence
        try {
            com.lagradost.cloudstream3.di.ServiceLocator.register<Application>(this)
            val sqliteRepo = com.lagradost.cloudstream3.utils.persistence.SqliteDataRepository(this)
            com.lagradost.cloudstream3.di.ServiceLocator.register<com.lagradost.cloudstream3.utils.persistence.DataRepository>(sqliteRepo)
            com.lagradost.cloudstream3.di.ServiceLocator.register(com.lagradost.cloudstream3.domain.usecase.ResumePlaybackUseCase(sqliteRepo))
            // Defer sandbox process spawn off critical cold-start path and skip eager bind on LOW/Potato tiers

            // Wire native high-performance accelerators into core library hooks
            com.lagradost.cloudstream3.utils.Levenshtein.nativeFuzzyRatioProvider = { s1: String, s2: String ->
                com.lagradost.cloudstream3.services.NativeCoreBridge.fuzzyRatio(s1, s2)
            }
            com.lagradost.cloudstream3.utils.JsUnpacker.nativeUnpacker = { script ->
                com.lagradost.cloudstream3.services.NativeCoreBridge.unpackJs(script)
            }
            com.lagradost.cloudstream3.utils.M3u8Helper2.nativeM3u8Parser = { content, baseUrl ->
                com.lagradost.cloudstream3.services.NativeCoreBridge.parseM3u8(content, baseUrl)
            }

            com.lagradost.cloudstream3.di.ServiceLocator.telemetry.recordBreadcrumb("App", "Application initialized successfully")
        } catch (_: Throwable) {
        }

        // Initialize real-time hardware display & HDMI topology monitoring
        try {
            com.lagradost.cloudstream3.utils.display.DisplayTopologyManager.init(this)
        } catch (_: Throwable) {
        }

        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            val nsfwLevelKey = getString(R.string.nsfw_filter_level_key)
            val enableNsfwKey = getString(R.string.enable_nsfw_on_providers_key)
            val censorPostersKey = getString(R.string.censor_nsfw_posters_key)
            val threshExtremeKey = getString(R.string.nsfw_visual_threshold_extreme_key)
            val threshModKey = getString(R.string.nsfw_visual_threshold_moderate_key)
            val threshLowKey = getString(R.string.nsfw_visual_threshold_low_key)
            prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == nsfwLevelKey || key == enableNsfwKey || key == censorPostersKey ||
                    key == threshExtremeKey || key == threshModKey || key == threshLowKey
                ) {
                    com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine.onFilterConfigChanged()
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (_: Throwable) {
        }

        try {
            thread(start = true, isDaemon = true, name = "StartupHardwareProfile") {
                val caps = com.lagradost.cloudstream3.utils.DeviceProfile.getDeviceCapabilities(this)
                if (!caps.isUltraPotato && caps.tier != com.lagradost.cloudstream3.utils.DeviceTier.LOW) {
                    com.lagradost.cloudstream3.services.ExtensionSandboxClient.bind(this)
                }
                // Warm Jackson DeserializerCache sequentially once to avoid multi-worker lock contention
                try {
                    mapper.readerFor(com.lagradost.cloudstream3.plugins.Repository::class.java)
                    mapper.readerFor(Array<com.lagradost.cloudstream3.plugins.SitePlugin>::class.java)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        try {
            thread(start = true, isDaemon = true, name = "StartupScoreCacheWarmup") {
                com.lagradost.cloudstream3.syncproviders.SecureAuthStore.wipeAllCredentials(this)
                removeKeys("auth_tokens")
                removeKeys("auth_ids")
                removeKey("accounts_key")
                com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.ensureScoresLoaded(this)
            }
        } catch (_: Throwable) {
        }
    }

    private var prefChangeListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        appContext = base
        context = base
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Coil module will be initialized globally when first loadImage() is invoked.
        return buildImageLoader(applicationContext)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isMainAppProcess()) return
        try {
            com.lagradost.cloudstream3.di.ServiceLocator.telemetry.recordBreadcrumb(
                "Memory",
                "TrimMemory level=$level",
                com.lagradost.cloudstream3.utils.telemetry.TelemetryService.Level.WARN
            )
            val memCache = SingletonImageLoader.get(this).memoryCache
            when {
                // Background LRU levels (ordered highest -> lowest; background values are >= 20, running levels < 20)
                level >= TRIM_MEMORY_COMPLETE -> {
                    memCache?.clear()
                    thread(start = true, isDaemon = true, name = "TrimMemoryCritical") {
                        com.lagradost.cloudstream3.utils.SystemMemoryOptimizer.reclaimBackgroundMemory(
                            this@CloudStreamApp,
                            force = true,
                            trimImageCache = true
                        )
                    }
                }
                level >= TRIM_MEMORY_MODERATE -> {
                    memCache?.trimToSize((memCache.size / 2).coerceAtLeast(0L))
                    thread(start = true, isDaemon = true, name = "TrimMemoryModerate") {
                        com.lagradost.cloudstream3.utils.SystemMemoryOptimizer.reclaimBackgroundMemory(
                            this@CloudStreamApp,
                            force = false,
                            trimImageCache = false
                        )
                    }
                }
                level >= TRIM_MEMORY_BACKGROUND -> {
                    thread(start = true, isDaemon = true, name = "BackgroundScoreFlush") {
                        com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.flushScoresToDisk(this)
                    }
                }
                level >= TRIM_MEMORY_UI_HIDDEN -> {
                    thread(start = true, isDaemon = true, name = "BackgroundScoreFlush") {
                        com.lagradost.cloudstream3.domain.engine.OnDeviceNsfwClassifier.flushScoresToDisk(this)
                    }
                }
                // Foreground running levels (values 5..15): keep cached posters intact during normal scrolling!
                level >= TRIM_MEMORY_RUNNING_CRITICAL -> {
                    memCache?.trimToSize((memCache.size / 2).coerceAtLeast(0L))
                    thread(start = true, isDaemon = true, name = "TrimMemoryRunningCritical") {
                        com.lagradost.cloudstream3.utils.SystemMemoryOptimizer.reclaimBackgroundMemory(
                            this@CloudStreamApp,
                            force = false,
                            trimImageCache = false
                        )
                    }
                }
                level >= TRIM_MEMORY_RUNNING_LOW -> {
                    thread(start = true, isDaemon = true, name = "TrimMemoryRunningLow") {
                        com.lagradost.cloudstream3.utils.SystemMemoryOptimizer.reclaimBackgroundMemory(
                            this@CloudStreamApp,
                            force = false,
                            trimImageCache = false
                        )
                    }
                }
                level >= TRIM_MEMORY_RUNNING_MODERATE -> {
                    // Preserve active poster memory cache while scrolling in foreground
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!isMainAppProcess()) return
        try {
            com.lagradost.cloudstream3.di.ServiceLocator.telemetry.recordBreadcrumb(
                "Memory",
                "onLowMemory triggered",
                com.lagradost.cloudstream3.utils.telemetry.TelemetryService.Level.FATAL
            )
            SingletonImageLoader.get(this).memoryCache?.clear()
            thread(start = true, isDaemon = true, name = "LowMemoryCleanup") {
                com.lagradost.cloudstream3.utils.SystemMemoryOptimizer.reclaimBackgroundMemory(
                    this@CloudStreamApp,
                    force = true,
                    trimImageCache = true
                )
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        var exceptionHandler: ExceptionHandler? = null

        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): Activity? {
            return when (this) {
                is Activity -> this
                is ContextWrapper -> {
                    val base = baseContext
                    if (base === this || base == null) null else base.getActivity()
                }
                else -> null
            }
        }

        @Volatile
        var appContext: Context? = null
            internal set

        private var _context: WeakReference<Context>? = null
        var context: Context?
            get() {
                val raw = _context?.get() ?: appContext ?: return null
                // Sandbox Protection: If called from a thread running a third-party plugin ClassLoader,
                // wrap the context in a SandboxedContextWrapper so plugins cannot bypass sandbox boundaries.
                val callerLoader = Thread.currentThread().contextClassLoader
                if (callerLoader is com.lagradost.cloudstream3.plugins.ShieldedClassLoader) {
                    val pluginName = com.lagradost.cloudstream3.plugins.PluginManager.currentlyLoading ?: "extension_runtime"
                    return com.lagradost.cloudstream3.plugins.SandboxedContextWrapper(raw, pluginName)
                }
                return raw
            }
            private set(value) {
                _context = WeakReference(value)
                setContext(value)
            }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.getKey(path, valueType)
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        /** If fallbackWebView is true and a fragment is supplied then it will open a WebView with the URL if the browser fails. */
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
            context?.openBrowser(url, fallbackWebView, fragment)
        }

        /** Will fall back to WebView if in TV or emulator layout. */
        fun openBrowser(url: String, activity: Activity?) {
            openBrowser(
                url,
                isLayout(TV or EMULATOR),
                (activity as? FragmentActivity)?.supportFragmentManager?.fragments?.lastOrNull()
            )
        }
    }
}
