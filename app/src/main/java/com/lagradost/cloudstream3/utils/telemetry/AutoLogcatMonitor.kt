package com.lagradost.cloudstream3.utils.telemetry

import android.os.Build
import android.os.Process
import android.util.Log
import com.lagradost.cloudstream3.di.ServiceLocator
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Enterprise Automatic Logcat Observer.
 *
 * Continuously streams and monitors logcat for WARNING, ERROR, and FATAL entries
 * produced by the CloudStream app process and forwards actionable events to Telemetry.
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
        "RenderThread"
    )

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

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

                            if (tag in noiseTagDenylist || tag == TAG || tag == "RemoteCrashReporter") {
                                continue
                            }

                            // Filter out common benign spam messages
                            if (message.contains("Skipped") && message.contains("frames!")) continue
                            if (message.contains("onAnimationCancel")) continue

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

                            // For errors and relevant CloudStream/Network/Player warnings, dispatch to remote reporter
                            val isCloudStreamTag = tag.contains("CloudStream", ignoreCase = true) ||
                                    tag.contains("Player", ignoreCase = true) ||
                                    tag.contains("Plugin", ignoreCase = true) ||
                                    tag.contains("Api", ignoreCase = true) ||
                                    tag.contains("ExoPlayer", ignoreCase = true) ||
                                    tag.contains("OkHttp", ignoreCase = true) ||
                                    tag.contains("Download", ignoreCase = true) ||
                                    tag.contains("Extractor", ignoreCase = true) ||
                                    tag.contains("Media", ignoreCase = true)

                            if (level >= TelemetryService.Level.ERROR || isCloudStreamTag) {
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
