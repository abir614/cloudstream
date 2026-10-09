package com.lagradost.cloudstream3.services

import android.util.Log

/**
 * Interface to the high-performance Rust core (cloudstream_core).
 * Enforces zero-trust URL security, ultra-fast zero-copy M3U IPTV parsing,
 * and capability-gated WebAssembly extension execution.
 */
object NativeCoreBridge {
    private const val TAG = "NativeCoreBridge"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("cloudstream_core")
            isLoaded = true
            Log.i(TAG, "Loaded native core library: ${getCoreInfo()}")
        } catch (t: Throwable) {
            Log.w(TAG, "Native library 'cloudstream_core' not loaded (fallback to JVM): ${t.message}")
        }
    }

    fun isNativeReady(): Boolean = isLoaded

    /**
     * Checks if a URL is safe against SSRF attacks (blocks private IPs, loopbacks, AWS metadata).
     */
    external fun validateUrlSafety(url: String): Boolean

    /**
     * Parses M3U IPTV playlist content into JSON format in native Rust memory without JVM GC pressure.
     */
    external fun parseM3uChannels(content: String): String

    /**
     * Returns the native core version and compile architecture (ARMv7, ARMv8, etc.).
     */
    external fun getCoreInfo(): String
}
