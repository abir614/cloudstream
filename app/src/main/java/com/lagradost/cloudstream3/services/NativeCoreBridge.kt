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
     * Returns the native core version and compile architecture (ARMv7, ARMv8, etc.).
     */
    external fun getCoreInfo(): String

    /**
     * Generates a cryptographically secure 256-bit ephemeral session nonce in native Rust.
     */
    external fun generateSessionNonce(): String?

    /**
     * Performs Z+ Zero-Trust End-to-End identity verification on an APK file in native Rust.
     * Returns JSON containing ApkVerificationResult (is_valid, file_sha256, attestation_token, error_message).
     */
    external fun verifyApkIdentity(
        apkPath: String,
        sessionNonce: String,
        expectedPkg: String,
        actualPkg: String,
        expectedCert: String,
        actualCert: String,
        installedVersionCode: Long,
        apkVersionCode: Long,
        repoHeaderHex: String?
    ): String?

    /**
     * Verifies an ephemeral HMAC attestation token in native Rust.
     */
    external fun verifyAttestationToken(
        sessionNonce: String,
        token: String,
        fileHash: String,
        pkgName: String,
        certFingerprint: String,
        versionCode: Long
    ): Boolean

    // --- Native LRU Cache ---
    external fun nativeCacheGet(cacheName: String, key: String): String?
    external fun nativeCachePut(cacheName: String, key: String, value: String)
    external fun nativeCacheRemove(cacheName: String, key: String): Boolean
    external fun nativeCacheClear(cacheName: String)

    // --- Native Subtitles ---
    external fun nativeParseSubtitles(content: String): String?

    // --- Native Network & URL Cleaning ---
    external fun nativeSanitizeUrl(url: String): String?
    external fun nativeResolveUrl(base: String, relative: String): String?

    // --- Native Hashing & Crypto ---
    external fun nativeFastHash64(input: String): Long
    external fun nativeSha256(input: String): String?
    external fun nativeMd5(input: String): String?
    external fun nativeFileSha256(filePath: String): String?

    // --- Native Keyring & Honeypot Moving-Target Defense ---
    external fun nativeKeyringIssueToken(slot: Int, payload: String): String?
    external fun nativeKeyringVerifyToken(slot: Int, payload: String, token: String): Boolean
    external fun nativeHoneypotProbe(path: String, payload: String): String?
    external fun nativeHoneypotGetDigest(): String?
}

