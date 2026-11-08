package com.lagradost.cloudstream3.utils.telemetry

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.lagradost.cloudstream3.di.ServiceLocator
import com.lagradost.cloudstream3.utils.DeviceProfile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Enterprise Automatic Logcat Observer.
 *
 * Continuously streams and monitors logcat for WARNING, ERROR, and FATAL entries
 * produced by the CloudStream app process and forwards actionable events to Telemetry.
 *
 * Tier-Adaptive:
 * - On Ultra-Potato devices (<= 1024MB RAM or legacy Linux kernel), skips spawning the
 *   external logcat subprocess to conserve CPU and RAM, relying fully on in-memory error hooks.
 * - On Standard/High-End devices, monitors real-time process logs with strict noise and recursion filtering.
 */
object AutoLogcatMonitor {
    private const val TAG = "AutoLogcatMonitor"
    private val isRunning = AtomicBoolean(false)
    private var monitorThread: Thread? = null

    // Benign or noisy system framework tags that should not trigger remote telemetry alerts
    private val noiseTagDenylist = setOf(
        "Choreographer",
        "OpenGLRenderer",
        "EGL_emulation",
        "ViewRootImpl",
        "InputMethodManager",
        "SurfaceView",
        "SurfaceSyncGroup",
        "GraphicBuffer",
        "CompatibilityChangeReporter",
        "Configuration",
        "InsetsController",
        "AutofillManager",
        "ActivityClient",
        "OplusViewRootImpl",
        "MIUI",
        "SemWifiManager",
        "WindowManager",
        "RenderThread",
        "InputEventSender",
        "PesReader",
        "System.err",
        "SingleLiceFactory",
        "perfctl",
        "MediaHTTPConnection",
        "MediaMetadataRetriever",
        "RecyclerView",
        "TranScrollOptimizer",
        "WindowOnBackDispatcher",
        "AxMediaRouter",
        "Resources",
        "BLASTBufferQueue",
        "DynamiteModule",
        "CastContext",
        "GooglePlayServicesUtil",
        "GoogleApiManager",
        "VideoCapabilities",
        "InteractionJankMonitor",
        "FinalizerDaemon",
        "PanelsChildGestureRegionObserver",
        "RemoteInputConnectionImpl",
        "GraphicsTracker",
        "FrameEvents",
        "InputEventReceiver",
        "CoilImgLoader",
        "ImeBackDispatcher",
        "NavInflater",
        "NavController"
    )

    private fun isNoiseTag(tag: String): Boolean {
        if (tag in noiseTagDenylist) return true
        for (entry in noiseTagDenylist) {
            if (tag.contains(entry, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun start(context: Context? = null) {
        if (!isRunning.compareAndSet(false, true)) return

        if (context != null && !RemoteCrashReporter.isTelemetryEnabled(context)) {
            isRunning.set(false)
            return
        }

        // Potato-tier optimization: On devices with <= 1024MB RAM, do not spawn an external Unix process.
        // In-memory errorReporter in ArchComponentExt already captures 100% of caught exceptions with zero overhead.
        if (context != null && DeviceProfile.isUltraPotato(context)) {
            try {
                ServiceLocator.telemetry.recordBreadcrumb(
                    tag = TAG,
                    message = "Subprocess disabled on ultra-potato device to conserve RAM/CPU; in-memory exception hook active",
                    level = TelemetryService.Level.INFO
                )
            } catch (_: Throwable) {
            }
            isRunning.set(false)
            return
        }

        val myPid = Process.myPid()

        val thread = thread(start = true, isDaemon = true, name = "AutoLogcatMonitor") {
            var failureCount = 0
            while (isRunning.get() && failureCount < 5) {
                var process: java.lang.Process? = null
                var reader: BufferedReader? = null
                try {
                    // Try process-filtered logcat if supported on Android API 24+
                    val cmd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        arrayOf("logcat", "-v", "brief", "--pid=$myPid", "*:W")
                    } else {
                        arrayOf("logcat", "-v", "brief", "*:W")
                    }

                    process = Runtime.getRuntime().exec(cmd)
                    reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8), 4096)

                    // Line format for "brief": "<priority>/<tag>(<pid>): <message>"
                    // e.g. "W/CloudStream(1234): Video fetch timeout"
                    // e.g. "E/OkHttp(1234): HTTP 502 Bad Gateway"
                    val lineRegex = Regex("""^([VDIWEF])/([^(\s:]+)(?:\(\s*(\d+)\))?:\s*(.*)$""")

                    while (isRunning.get()) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank() || line.startsWith("--------- beginning of")) continue

                        val match = lineRegex.find(line)
                        if (match != null) {
                            val levelChar = match.groupValues.getOrNull(1) ?: "W"
                            val tag = match.groupValues.getOrNull(2)?.trim() ?: "Logcat"
                            val pidStr = match.groupValues.getOrNull(3)
                            val message = match.groupValues.getOrNull(4)?.trim() ?: ""

                            // Verify PID match if logcat was not able to filter by PID on older OS
                            if (pidStr != null && pidStr.toIntOrNull() != myPid) {
                                continue
                            }

                            if (isNoiseTag(tag) || tag == TAG || tag == "RemoteCrashReporter") {
                                continue
                            }

                            // Strict recursion protection: never report network errors or debug logs from the telemetry system itself
                            if (message.contains("app-log-collector", ignoreCase = true) ||
                                message.contains("workers.dev", ignoreCase = true) ||
                                message.contains("AutoLogcatMonitor", ignoreCase = true) ||
                                message.contains("RemoteCrashReporter", ignoreCase = true) ||
                                message.contains("telemetry", ignoreCase = true)
                            ) {
                                continue
                            }

                            // Filter out common benign spam messages
                            if (message.contains("Skipped") && message.contains("frames!")) continue
                            if (message.contains("onAnimationCancel")) continue
                            if (message.contains("Unexpected start code prefix", ignoreCase = true)) continue
                            if (message.contains("Received 'finished' signal", ignoreCase = true)) continue
                            if (message.contains("No adapter attached; skipping layout", ignoreCase = true)) continue
                            if (message.contains("sendCancelIfRunning", ignoreCase = true)) continue
                            if (message.contains("acquireNextBufferLocked", ignoreCase = true)) continue
                            if (message.contains("ClassLoaderContext type mismatch", ignoreCase = true)) continue
                            if (message.contains("unresolved theme attributes", ignoreCase = true)) continue
                            if (message.contains("perfctl", ignoreCase = true)) continue
                            if (message.contains("FPSGO", ignoreCase = true)) continue
                            if (message.contains("legacy_receive_flag", ignoreCase = true)) continue
                            if (message.contains("Cleared Reference was only reachable from finalizer", ignoreCase = true)) continue
                            if (message.contains("Monitor::Inflate", ignoreCase = true)) continue
                            if (message.contains("SingleLiceFactory", ignoreCase = true)) continue
                            if (message.contains("TripleFinger", ignoreCase = true)) continue
                            if (message.contains("ThreeFinger", ignoreCase = true)) continue
                            if (message.contains("avc:  denied") || message.contains("avc: denied")) continue
                            if (message.contains("READ_DEVICE_CONFIG", ignoreCase = true)) continue
                            if (message.contains("resources.arsc in APK", ignoreCase = true)) continue
                            if (message.contains("Accessing hidden method Landroid/view/WindowInsets", ignoreCase = true)) continue
                            if (message.contains("AIBinder_linkToDeath", ignoreCase = true)) continue
                            if (message.contains("Unknown bits set in runtime_flags", ignoreCase = true)) continue
                            if (message.contains("Media Quality Service not found", ignoreCase = true)) continue
                            if (message.contains("Failed to query component interface", ignoreCase = true)) continue
                            if (message.contains("input event receiver has already been disposed", ignoreCase = true)) continue
                            if (message.contains("Missing inline cache", ignoreCase = true)) continue
                            if (message.contains("Suspending all threads took", ignoreCase = true)) continue
                            if (message.contains("Ime callback not found", ignoreCase = true)) continue
                            if (message.contains("Explicit concurrent copying GC", ignoreCase = true)) continue
                            if (message.contains("Background concurrent copying GC", ignoreCase = true)) continue
                            if (message.contains("Image load error", ignoreCase = true)) continue
                            if (message.contains("allowHardware", ignoreCase = true)) continue

                            val level = when (levelChar) {
                                "E" -> TelemetryService.Level.ERROR
                                "F" -> TelemetryService.Level.FATAL
                                else -> TelemetryService.Level.WARN
                            }

                            // Record forensic breadcrumb
                            try {
                                ServiceLocator.telemetry.recordBreadcrumb(
                                    tag = tag,
                                    message = message,
                                    level = level
                                )
                            } catch (_: Throwable) {
                            }

                            // Comprehensive app-wide capture: dispatch every warning (W), error (E), and fatal (F) from any component or function in the app
                            if (level >= TelemetryService.Level.WARN) {
                                RemoteCrashReporter.dispatchDiagnosticLogAsync(
                                    level = level.name,
                                    tag = tag,
                                    message = message,
                                    throwable = null
                                )
                            }
                        }
                    }
                    failureCount = 0
                } catch (t: Throwable) {
                    failureCount++
                    Log.w(TAG, "Logcat monitor error: ${t.message}")
                    try {
                        Thread.sleep(3000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                } finally {
                    try { reader?.close() } catch (_: Throwable) {}
                    try { process?.destroy() } catch (_: Throwable) {}
                }
            }
            isRunning.set(false)
        }
        monitorThread = thread
    }

    fun stop() {
        isRunning.set(false)
        monitorThread?.interrupt()
        monitorThread = null
    }
}
