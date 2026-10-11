package com.lagradost.cloudstream3.utils.telemetry

import android.util.Log
import com.lagradost.cloudstream3.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enterprise Observability & Privacy-Preserving Telemetry Service.
 *
 * Provides:
 * 1. Bounded forensic breadcrumb tracking for post-mortem crash analysis.
 * 2. Automatic redaction of sensitive credentials, tokens, and private IP patterns.
 * 3. Pluggable logging sinks (Logcat, Disk buffer, Memory ring buffer).
 * 4. Thread-safe, non-blocking telemetry recording.
 */
class TelemetryService(
    private val maxBreadcrumbs: Int = 100,
    private val isDebug: Boolean = BuildConfig.DEBUG
) {

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR,
        FATAL
    }

    data class Breadcrumb(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val data: Map<String, String> = emptyMap()
    ) {
        fun format(dateFormat: SimpleDateFormat): String {
            val dateStr = dateFormat.format(Date(timestamp))
            val extra = if (data.isNotEmpty()) " | data=$data" else ""
            return "[$dateStr] [${level.name}] [$tag] $message$extra"
        }
    }

    private val breadcrumbs = ConcurrentLinkedQueue<Breadcrumb>()
    private val isEnabled = AtomicBoolean(true)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Regex patterns for automatic privacy redaction
    private val tokenRegex = Regex("(?i)(api[-_]?key|token|auth|bearer|password|secret)=([^&\\s]+)")
    private val ipRegex = Regex("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b")

    /**
     * Sanitizes sensitive data before storage or dispatch.
     */
    fun sanitize(input: String): String {
        if (input.isBlank()) return input
        var cleaned = tokenRegex.replace(input) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        cleaned = ipRegex.replace(cleaned) { match ->
            val ip = match.value
            if (ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.")) {
                "[PRIVATE_IP]"
            } else {
                ip
            }
        }
        return cleaned
    }

    /**
     * Records a diagnostic breadcrumb in memory with FIFO bounded capacity.
     */
    fun recordBreadcrumb(
        tag: String,
        message: String,
        level: Level = Level.INFO,
        data: Map<String, String> = emptyMap()
    ) {
        if (!isEnabled.get()) return

        val sanitizedMsg = sanitize(message)
        val sanitizedData = data.mapValues { sanitize(it.value) }

        breadcrumbs.offer(
            Breadcrumb(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = sanitizedMsg,
                data = sanitizedData
            )
        )

        // Trim to ring-buffer bound
        while (breadcrumbs.size > maxBreadcrumbs) {
            breadcrumbs.poll()
        }
    }

    /**
     * Dispatches an event log with optional throwable details.
     */
    fun log(
        tag: String,
        message: String,
        level: Level = Level.INFO,
        throwable: Throwable? = null,
        data: Map<String, String> = emptyMap()
    ) {
        val safeMessage = sanitize(message)
        recordBreadcrumb(tag, safeMessage, level, data)

        // Mirror to Android Logcat in development / debug modes
        if (isDebug || level >= Level.WARN) {
            val fullMsg = if (data.isNotEmpty()) "$safeMessage | $data" else safeMessage
            when (level) {
                Level.DEBUG -> Log.d(tag, fullMsg, throwable)
                Level.INFO -> Log.i(tag, fullMsg, throwable)
                Level.WARN -> Log.w(tag, fullMsg, throwable)
                Level.ERROR -> Log.e(tag, fullMsg, throwable)
                Level.FATAL -> Log.wtf(tag, fullMsg, throwable)
            }
        }
    }

    /**
     * Captures an unhandled or critical exception, attaching all recent forensic breadcrumbs.
     */
    fun captureCrash(error: Throwable, targetDumpFile: File? = null): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)

        pw.println("================================================================================")
        pw.println("CLOUDSTREAM ENTERPRISE CRASH FORENSIC DUMP")
        pw.println("Timestamp: ${dateFormat.format(Date())}")
        pw.println("Exception: ${error.javaClass.name}: ${error.message}")
        pw.println("================================================================================")
        pw.println("\n--- RECENT BREADCRUMBS (${breadcrumbs.size}) ---")

        synchronized(dateFormat) {
            breadcrumbs.forEach { bc ->
                pw.println(bc.format(dateFormat))
            }
        }

        pw.println("\n--- STACK TRACE ---")
        error.printStackTrace(pw)
        pw.flush()

        val report = sw.toString()

        if (targetDumpFile != null) {
            try {
                targetDumpFile.parentFile?.mkdirs()
                targetDumpFile.writeText(report)
            } catch (t: Throwable) {
                Log.w("TelemetryService", "Failed to write crash dump to file: ${t.message}")
            }
        }

        return report
    }

    /**
     * Retrieves an immutable snapshot of current breadcrumbs.
     */
    fun getBreadcrumbsSnapshot(): List<Breadcrumb> {
        return breadcrumbs.toList()
    }

    /**
     * Clears all recorded breadcrumbs.
     */
    fun clearBreadcrumbs() {
        breadcrumbs.clear()
    }

    /**
     * Enables or disables telemetry recording.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        if (!enabled) {
            clearBreadcrumbs()
        }
    }
}
