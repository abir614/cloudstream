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

            // Hook native execution providers for zero-GC low-end optimization
            com.lagradost.cloudstream3.utils.JsUnpacker.nativeUnpacker = { script ->
                if (isLoaded) nativeUnpackJs(script) else null
            }
            com.lagradost.cloudstream3.utils.Levenshtein.nativeFuzzyRatioProvider = { s1, s2 ->
                if (isLoaded) nativeFuzzyRatio(s1, s2) else null
            }
            com.lagradost.cloudstream3.utils.M3u8Helper2.nativeM3u8Parser = { content, baseUrl ->
                if (!isLoaded) null
                else try {
                    val json = nativeParseM3u8Master(content, baseUrl)
                    if (json != null) {
                        val arr = org.json.JSONArray(json)
                        val res = ArrayList<com.lagradost.cloudstream3.utils.M3u8Helper.M3u8Stream>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val streamUrl = obj.getString("url")
                            val resStr = if (obj.isNull("resolution")) null else obj.getString("resolution")
                            val height = resStr?.split("x")?.getOrNull(1)?.toIntOrNull()
                            res.add(com.lagradost.cloudstream3.utils.M3u8Helper.M3u8Stream(streamUrl, height))
                        }
                        res
                    } else null
                } catch (_: Throwable) {
                    null
                }
            }
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

    // --- Native Media & Manifest Parsing ---
    external fun nativeParseM3u8Master(content: String, baseUrl: String?): String?
    external fun nativeParseIptv(content: String): String?

    // --- Native JavaScript Unpacking & Extractor ---
    external fun nativeUnpackJs(script: String): String?
    external fun nativeExtractStreamUrls(content: String): String?

    // --- Native Fast HTML Media Link Extraction ---
    external fun nativeExtractMediaLinks(html: String): String?
    external fun nativeExtractScriptJson(html: String, variableName: String): String?

    // --- Native Levenshtein & Fuzzy Search ---
    external fun nativeFuzzyRatio(s1: String, s2: String): Int
    external fun nativeLevenshtein(s1: String, s2: String): Int

    // --- Native NSFW / Hentai Classification ---
    external fun nativeIsNsfw(title: String, url: String, tags: String?): Boolean
    external fun nativeIsSkinExposureExcessive(bytes: ByteArray, bytesPerPixel: Int): Boolean

    fun isNsfw(title: String, url: String, tags: String? = null): Boolean? {
        return if (isLoaded) {
            try {
                nativeIsNsfw(title, url, tags)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    fun isSkinExposureExcessive(bytes: ByteArray, bytesPerPixel: Int = 3): Boolean? {
        return if (isLoaded) {
            try {
                nativeIsSkinExposureExcessive(bytes, bytesPerPixel)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }


    // --- Native Fast In-Memory DEFLATE Compression ---
    external fun nativeCompressString(text: String): ByteArray?
    external fun nativeDecompressString(bytes: ByteArray): String?

    fun compressString(text: String): ByteArray? {
        return if (isLoaded) nativeCompressString(text) else null
    }

    fun decompressString(bytes: ByteArray): String? {
        return if (isLoaded) nativeDecompressString(bytes) else null
    }

    // --- Native Capability-Gated WebAssembly Sandbox Execution ---
    external fun nativeRunWasmExtension(
        wasmBytes: ByteArray,
        initialFuel: Long,
        maxMemoryBytes: Long
    ): String?

    data class WasmExecutionResult(
        val success: Boolean,
        val logs: List<String>,
        val error: String?
    )

    fun runWasmExtension(
        wasmBytes: ByteArray,
        initialFuel: Long = 500_000L,
        maxMemoryBytes: Long = 16 * 1024 * 1024L
    ): WasmExecutionResult {
        if (!isLoaded) {
            return WasmExecutionResult(false, emptyList(), "Native core library is not loaded")
        }
        return try {
            val json = nativeRunWasmExtension(wasmBytes, initialFuel, maxMemoryBytes)
            if (json != null) {
                val obj = org.json.JSONObject(json)
                val success = obj.optBoolean("success", false)
                val error = if (obj.isNull("error")) null else obj.optString("error")
                val logsArr = obj.optJSONArray("logs")
                val logs = ArrayList<String>()
                if (logsArr != null) {
                    for (i in 0 until logsArr.length()) {
                        logs.add(logsArr.getString(i))
                    }
                }
                WasmExecutionResult(success, logs, error)
            } else {
                WasmExecutionResult(false, emptyList(), "Null response from native runtime")
            }
        } catch (t: Throwable) {
            WasmExecutionResult(false, emptyList(), t.message ?: "Unknown WASM execution error")
        }
    }

    // --- Native Offline Ed25519 Signature Verification (§2.3) ---
    external fun nativeVerifyEd25519(manifest: String, signatureHex: String, publicKeyHex: String): Boolean

    fun verifyEd25519(manifest: String, signatureHex: String, publicKeyHex: String): Boolean {
        return if (isLoaded) nativeVerifyEd25519(manifest, signatureHex, publicKeyHex) else false
    }

    // --- Native Authenticated Backup Encryption (§4.1) ---
    external fun nativeEncryptBackup(data: ByteArray, passphrase: String): ByteArray?
    external fun nativeDecryptBackup(encrypted: ByteArray, passphrase: String): ByteArray?
    external fun nativeIsEncryptedBackup(data: ByteArray): Boolean

    fun encryptBackup(data: ByteArray, passphrase: String): ByteArray? {
        return if (isLoaded) nativeEncryptBackup(data, passphrase) else null
    }

    fun decryptBackup(encrypted: ByteArray, passphrase: String): ByteArray? {
        return if (isLoaded) nativeDecryptBackup(encrypted, passphrase) else null
    }

    fun isEncryptedBackup(data: ByteArray): Boolean {
        return if (isLoaded) {
            nativeIsEncryptedBackup(data)
        } else {
            data.size >= 6 && data.sliceArray(0 until 6).contentEquals("CSENC1".toByteArray(Charsets.UTF_8))
        }
    }
}
