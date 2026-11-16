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
        "NavController",
        "IInputConnectionWrapper",
        "NotificationManager",
        "ActivityThread",
        "androidtc",
        "VelocityTracker",
        "ColorUtils",
        "ExoPlayerImplInternal",
        "SandboxClient",
        "Gralloc3",
        "Gralloc4",
        "Gralloc",
        "AudioCapabilities"
    )

    private fun isNoiseMessage(message: String): Boolean {
        return message.contains("Skipped") && message.contains("frames!") ||
            message.contains("onAnimationCancel") ||
            message.contains("Unexpected start code prefix", ignoreCase = true) ||
            message.contains("Received 'finished' signal", ignoreCase = true) ||
            message.contains("No adapter attached; skipping layout", ignoreCase = true) ||
            message.contains("sendCancelIfRunning", ignoreCase = true) ||
            message.contains("acquireNextBufferLocked", ignoreCase = true) ||
            message.contains("ClassLoaderContext type mismatch", ignoreCase = true) ||
            message.contains("unresolved theme attributes", ignoreCase = true) ||
            message.contains("perfctl", ignoreCase = true) ||
            message.contains("FPSGO", ignoreCase = true) ||
            message.contains("legacy_receive_flag", ignoreCase = true) ||
            message.contains("Cleared Reference was only reachable from finalizer", ignoreCase = true) ||
            message.contains("Monitor::Inflate", ignoreCase = true) ||
            message.contains("SingleLiceFactory", ignoreCase = true) ||
            message.contains("TripleFinger", ignoreCase = true) ||
            message.contains("ThreeFinger", ignoreCase = true) ||
            message.contains("avc:  denied") || message.contains("avc: denied") ||
            message.contains("READ_DEVICE_CONFIG", ignoreCase = true) ||
            message.contains("resources.arsc in APK", ignoreCase = true) ||
            message.contains("Accessing hidden method Landroid/view/WindowInsets", ignoreCase = true) ||
            message.contains("AIBinder_linkToDeath", ignoreCase = true) ||
            message.contains("Unknown bits set in runtime_flags", ignoreCase = true) ||
            message.contains("Media Quality Service not found", ignoreCase = true) ||
            message.contains("Failed to query component interface", ignoreCase = true) ||
            message.contains("input event receiver has already been disposed", ignoreCase = true) ||
            message.contains("Missing inline cache", ignoreCase = true) ||
            message.contains("Suspending all threads took", ignoreCase = true) ||
            message.contains("Ime callback not found", ignoreCase = true) ||
            message.contains("Explicit concurrent copying GC", ignoreCase = true) ||
            message.contains("Background concurrent copying GC", ignoreCase = true) ||
            message.contains("Image load error", ignoreCase = true) ||
            message.contains("allowHardware", ignoreCase = true) ||
            message.contains("Not starting debugger since process cannot load the jdwp agent", ignoreCase = true) ||
            message.contains("PostNotification rate is", ignoreCase = true) ||
            message.contains("inactive InputConnection", ignoreCase = true) ||
            message.contains("handleWindowVisibility", ignoreCase = true) ||
            message.contains("Ignoring messages sent after release", ignoreCase = true) ||
            message.contains("Disconnected from ExtensionSandboxService", ignoreCase = true) ||
            message.contains("expected specified color aspects", ignoreCase = true) ||
            message.contains("No movement data for pointerId", ignoreCase = true) ||
            message.contains("TextClassifier called on main thread", ignoreCase = true) ||
            message.contains("primary.prof", ignoreCase = true) ||
            message.contains("Duplicate interns in the .data.img.rel.ro", ignoreCase = true) ||
            message.contains("Weak pointer dereference blocked", ignoreCase = true) ||
            message.contains("variations_seed_loader", ignoreCase = true) ||
            message.contains("Unsupported mime audio/", ignoreCase = true)
    }

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

        // Potato-tier & low-end device optimization: On devices with <= 2048MB RAM or potato devices, do not spawn an external Unix process.
        // In-memory errorReporter in ArchComponentExt already captures 100% of caught exceptions with zero overhead.
        if (context != null && (DeviceProfile.isUltraPotato(context) || DeviceProfile.isLowEnd(context))) {
            try {
                ServiceLocator.telemetry.recordBreadcrumb(
                    tag = TAG,
                    message = "Subprocess disabled on potato/low-end device to conserve RAM/CPU; in-memory exception hook active",
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

                    // Robust logcat header pattern supporting:
                    // 1. Brief: "W/CloudStream(1234): message"
                    // 2. Tag with spaces: "W/AudioTrack Shared(1234): message"
                    // 3. Tag without PID: "W/ActivityManager: message"
                    // 4. Timestamped: "05-16 10:20:30.123 W/CloudStream(1234): message"
                    val lineRegex = Regex(
                        """^(?:(?:\d{2}-\d{2}\s+)?\d{2}:\d{2}:\d{2}\.\d{3}\s+(?:\d+\s+\d+\s+)?)?([VDIWEF])/(.+?)(?:\(\s*(\d+)\s*\))?:\s*(.*)$"""
                    )
                    // Threadtime format: "05-16 10:20:30.123 1234 5678 W CloudStream: message"
                    val threadtimeRegex = Regex(
                        """^(?:\d{2}-\d{2}\s+)?\d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+\d+\s+([VDIWEF])\s+([^:]+?):\s*(.*)$"""
                    )

                    var currentLevel: TelemetryService.Level = TelemetryService.Level.WARN
                    var currentTag: String = "Logcat"
                    val currentMessage = StringBuilder()
                    var currentLineCount = 0
                    val maxContinuationLines = 40

                    fun flushCurrentEntry() {
                        if (currentMessage.isBlank()) return
                        val fullMessage = currentMessage.toString().trim()
                        currentMessage.clear()
                        currentLineCount = 0

                        if (isNoiseTag(currentTag) || currentTag == TAG || currentTag == "RemoteCrashReporter") {
                            return
                        }

                        // Strict recursion protection: never report network errors or debug logs from the telemetry system itself
                        if (fullMessage.contains("app-log-collector", ignoreCase = true) ||
                            fullMessage.contains("workers.dev", ignoreCase = true) ||
                            fullMessage.contains("AutoLogcatMonitor", ignoreCase = true) ||
                            fullMessage.contains("RemoteCrashReporter", ignoreCase = true) ||
                            fullMessage.contains("telemetry", ignoreCase = true)
                        ) {
                            return
                        }

                        // Filter out common benign spam messages
                        if (isNoiseMessage(fullMessage)) return

                        // Record forensic breadcrumb
                        try {
                            ServiceLocator.telemetry.recordBreadcrumb(
                                tag = currentTag,
                                message = fullMessage,
                                level = currentLevel
                            )
                        } catch (_: Throwable) {
                        }

                        // Comprehensive app-wide capture: dispatch warnings, errors, and fatals
                        if (currentLevel >= TelemetryService.Level.WARN) {
                            RemoteCrashReporter.dispatchDiagnosticLogAsync(
                                level = currentLevel.name,
                                tag = currentTag,
                                message = fullMessage,
                                throwable = null
                            )
                        }
                    }

                    while (isRunning.get()) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank() || line.startsWith("--------- beginning of")) continue

                        val match = lineRegex.find(line)
                        val matchThreadtime = if (match == null) threadtimeRegex.find(line) else null

                        if (match != null || matchThreadtime != null) {
                            // Flush any previous buffered multi-line log before starting a new one
                            flushCurrentEntry()

                            val levelChar: String
                            val parsedTag: String
                            val pidStr: String?
                            val initialMessage: String

                            if (match != null) {
                                levelChar = match.groupValues.getOrNull(1) ?: "W"
                                parsedTag = match.groupValues.getOrNull(2)?.trim() ?: "Logcat"
                                pidStr = match.groupValues.getOrNull(3)
                                initialMessage = match.groupValues.getOrNull(4) ?: ""
                            } else {
                                val m = matchThreadtime!!
                                levelChar = m.groupValues.getOrNull(2) ?: "W"
                                parsedTag = m.groupValues.getOrNull(3)?.trim() ?: "Logcat"
                                pidStr = m.groupValues.getOrNull(1)
                                initialMessage = m.groupValues.getOrNull(4) ?: ""
                            }

                            // Verify PID match if logcat was not able to filter by PID on older OS
                            if (pidStr != null && pidStr.toIntOrNull() != myPid) {
                                continue
                            }

                            currentLevel = when (levelChar) {
                                "E" -> TelemetryService.Level.ERROR
                                "F" -> TelemetryService.Level.FATAL
                                else -> TelemetryService.Level.WARN
                            }
                            currentTag = parsedTag
                            currentMessage.append(initialMessage)
                            currentLineCount = 1
                        } else {
                            // Continuation line (e.g. stack trace: " at ...", "Caused by: ...", etc.)
                            if (currentMessage.isNotEmpty() && currentLineCount < maxContinuationLines) {
                                currentMessage.append('\n').append(line.trimEnd())
                                currentLineCount++
                            }
                        }
                    }
                    flushCurrentEntry()
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
