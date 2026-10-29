package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
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
import java.io.FileNotFoundException
import java.io.PrintStream
import java.lang.ref.WeakReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ExceptionHandler(
    val errorFile: File,
    val onError: (() -> Unit)
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val threadId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                thread.threadId()
            } else {
                @Suppress("DEPRECATION")
                thread.id
            }

            try {
                com.lagradost.cloudstream3.di.ServiceLocator.telemetry.captureCrash(error, errorFile)
            } catch (_: Throwable) {
                // Fallback to basic printstream if telemetry fails
                PrintStream(errorFile).use { ps ->
                    ps.println("Currently loading extension: ${PluginManager.currentlyLoading ?: "none"}")
                    ps.println("Fatal exception on thread ${thread.name} ($threadId)")
                    error.printStackTrace(ps)
                }
            }
        } catch (_: FileNotFoundException) {
        }
        try {
            onError()
        } catch (_: Exception) {
        }
        exitProcess(1)
    }
}

class CloudStreamApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()

        ExceptionHandler(filesDir.resolve("last_error")) {
            val intent = this.packageManager.getLaunchIntentForPackage(this.packageName)
            intent?.component?.let { comp ->
                startActivity(Intent.makeRestartActivityTask(comp))
            }
        }.also {
            exceptionHandler = it
            Thread.setDefaultUncaughtExceptionHandler(it)
        }

        AppDebug.isDebug = BuildConfig.DEBUG

        // Warm up isolated ExtensionSandboxService and register core application context & SQLite persistence
        try {
            com.lagradost.cloudstream3.di.ServiceLocator.register<Application>(this)
            val sqliteRepo = com.lagradost.cloudstream3.utils.persistence.SqliteDataRepository(this)
            com.lagradost.cloudstream3.di.ServiceLocator.register<com.lagradost.cloudstream3.utils.persistence.DataRepository>(sqliteRepo)
            com.lagradost.cloudstream3.di.ServiceLocator.register(com.lagradost.cloudstream3.domain.usecase.ResumePlaybackUseCase(sqliteRepo))
            com.lagradost.cloudstream3.services.ExtensionSandboxClient.bind(this)
            com.lagradost.cloudstream3.di.ServiceLocator.telemetry.recordBreadcrumb("App", "Application initialized successfully")
        } catch (_: Throwable) {
        }

        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            val nsfwLevelKey = getString(R.string.nsfw_filter_level_key)
            val enableNsfwKey = getString(R.string.enable_nsfw_on_providers_key)
            val censorPostersKey = getString(R.string.censor_nsfw_posters_key)
            prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == nsfwLevelKey || key == enableNsfwKey || key == censorPostersKey) {
                    com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine.onFilterConfigChanged()
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (_: Throwable) {
        }

        try {
            if (com.lagradost.cloudstream3.utils.DeviceProfile.isLowEnd(this)) {
                thread(start = true, isDaemon = true, name = "StartupMemoryReclaimer") {
                    com.lagradost.cloudstream3.utils.SystemMemoryOptimizer.reclaimBackgroundMemory(this)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private var prefChangeListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Coil module will be initialized globally when first loadImage() is invoked.
        return buildImageLoader(applicationContext)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            com.lagradost.cloudstream3.di.ServiceLocator.telemetry.recordBreadcrumb(
                "Memory",
                "TrimMemory level=$level",
                com.lagradost.cloudstream3.utils.telemetry.TelemetryService.Level.WARN
            )
            val memCache = SingletonImageLoader.get(this).memoryCache
            if (memCache != null) {
                if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_MODERATE) {
                    memCache.clear()
                } else if (level >= TRIM_MEMORY_BACKGROUND) {
                    memCache.trimToSize(memCache.size / 2)
                }
            }
            if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_MODERATE) {
                try {
                    com.lagradost.cloudstream3.app.baseClient.connectionPool.evictAll()
                    com.lagradost.cloudstream3.utils.ImageLoader.evictConnections()
                    com.lagradost.cloudstream3.utils.SystemMemoryOptimizer.reclaimBackgroundMemory(this, force = true)
                } catch (_: Throwable) {
                }
            }
            if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
                System.gc()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            com.lagradost.cloudstream3.di.ServiceLocator.telemetry.recordBreadcrumb(
                "Memory",
                "onLowMemory triggered",
                com.lagradost.cloudstream3.utils.telemetry.TelemetryService.Level.FATAL
            )
            SingletonImageLoader.get(this).memoryCache?.clear()
            try {
                com.lagradost.cloudstream3.app.baseClient.connectionPool.evictAll()
                com.lagradost.cloudstream3.utils.ImageLoader.evictConnections()
                com.lagradost.cloudstream3.utils.SystemMemoryOptimizer.reclaimBackgroundMemory(this, force = true)
            } catch (_: Throwable) {
            }
            System.gc()
        } catch (_: Throwable) {
        }
    }

    companion object {
        var exceptionHandler: ExceptionHandler? = null

        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): Activity? {
            return when (this) {
                is Activity -> this
                is ContextWrapper -> baseContext.getActivity()
                else -> null
            }
        }

        private var _context: WeakReference<Context>? = null
        var context
            get() = _context?.get()
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
