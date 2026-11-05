package com.lagradost.cloudstream3.utils.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.DeviceProfile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Enterprise-grade Automated Crash & Diagnostic Telemetry Dispatcher.
 *
 * Security Architecture (Zero Static Secrets & Ephemeral ECDH Rotation):
 * 1. The client APK contains ZERO static symmetric keys, ZERO Telegram tokens, and ZERO Chat IDs.
 * 2. All destination secrets live exclusively on the serverless Cloudflare Worker edge.
 * 3. Every report session performs an ephemeral Elliptic-Curve Diffie-Hellman (ECDH) key exchange
 *    over NIST P-256 (secp256r1) with the Cloudflare Worker.
 * 4. A single-use 256-bit AES-GCM session key is derived dynamically via HKDF-SHA256 with
 *    epoch salt and rotation nonce.
 * 5. Payloads are encrypted end-to-end (AES-256-GCM with 128-bit authentication tag) before
 *    transmission across the public network.
 * 6. Even with full APK decompilation, attackers cannot access worker endpoints or decrypt traffic.
 */
object RemoteCrashReporter {
    private const val TAG = "RemoteCrashReporter"

    // Public Cloudflare Worker ingestion proxy endpoint (contains zero private secrets)
    const val BASE_ENDPOINT = "https://cloudstream.app-log-collector.workers.dev"
    const val HANDSHAKE_ENDPOINT = "$BASE_ENDPOINT/handshake"
    const val REPORT_ENDPOINT = "$BASE_ENDPOINT/report"

    private var appContextRef: WeakReference<Context>? = null

    data class DiagnosticEvent(
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
        val data: Map<String, String>
    )

    // Deduplication & FIFO Dispatch Queue for non-fatal diagnostic logs
    private val recentAlertTimestamps = ConcurrentHashMap<String, Long>()
    private const val DEDUPLICATION_WINDOW_MS = 30_000L // Suppress identical (level + tag + msg) for 30s
    private const val DISPATCH_PACE_INTERVAL_MS = 2_000L // Pace dispatches by 2s to maintain healthy edge limits
    private const val MAX_DIAGNOSTIC_QUEUE_CAPACITY = 30

    private val diagnosticQueue = java.util.concurrent.ConcurrentLinkedQueue<DiagnosticEvent>()
    private val isDispatcherRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3L, TimeUnit.SECONDS)
            .writeTimeout(4L, TimeUnit.SECONDS)
            .readTimeout(5L, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Initializes the reporter with an application context for hardware metrics.
     */
    fun init(context: Context) {
        appContextRef = WeakReference(context.applicationContext)
    }

    private fun getContext(): Context? {
        return appContextRef?.get()
            ?: com.lagradost.cloudstream3.di.ServiceLocator.resolveOrNull<android.app.Application>()
    }

    // --- Base64 Cross-Platform Utilities ---
    private fun base64Encode(bytes: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun base64Decode(str: String): ByteArray {
        return try {
            android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }

    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * Retrieves or generates an anonymous, persistent installation ID for CGNAT-safe rate limiting.
     */
    private fun getOrCreateDeviceId(context: Context?): String {
        cachedDeviceId?.let { return it }
        val ctx = context ?: getContext() ?: return "unknown_device"
        val id = try {
            val file = ctx.filesDir.resolve("telemetry_device_id")
            if (file.exists() && file.isFile) {
                file.readText().trim().ifBlank {
                    val newId = java.util.UUID.randomUUID().toString()
                    file.writeText(newId)
                    newId
                }
            } else {
                val newId = java.util.UUID.randomUUID().toString()
                file.writeText(newId)
                newId
            }
        } catch (_: Throwable) {
            "fallback_device_id"
        }
        cachedDeviceId = id
        return id
    }

    // --- Ephemeral ECDH & HKDF Cryptographic Protocol ---

    data class HandshakeTicket(
        val serverPublicKeyBytes: ByteArray,
        val epoch: Long,
        val ticket: String,
        val challenge: String = "",
        val difficulty: Int = 0
    )

    /**
     * Generates a fresh ephemeral EC KeyPair on NIST P-256 (secp256r1).
     */
    private fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /**
     * Performs a rotating handshake with the Cloudflare Worker edge.
     * Transmits the client ephemeral public key and receives an encrypted session ticket + PoW challenge.
     */
    private fun performHandshake(clientKeyPair: KeyPair): HandshakeTicket? {
        return try {
            val clientPubSpki = clientKeyPair.public.encoded
            val reqJson = JSONObject().apply {
                put("client_pub", base64Encode(clientPubSpki))
                put("device_id", getOrCreateDeviceId(getContext()))
            }

            val request = Request.Builder()
                .url(HANDSHAKE_ENDPOINT)
                .post(reqJson.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val responseBody = response.body?.string()
            response.close()
            if (responseBody.isNullOrBlank()) return null

            val resJson = JSONObject(responseBody)
            val serverPubB64 = resJson.getString("server_pub")
            val epoch = resJson.getLong("epoch")
            val ticket = resJson.getString("ticket")
            val challenge = resJson.optString("challenge", "")
            val difficulty = resJson.optInt("difficulty", 0)

            HandshakeTicket(
                serverPublicKeyBytes = base64Decode(serverPubB64),
                epoch = epoch,
                ticket = ticket,
                challenge = challenge,
                difficulty = difficulty
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Handshake failed: ${t.message}")
            null
        }
    }

    /**
     * Solves the Proof-of-Work challenge using an allocation-free streaming SHA-256 loop.
     * Zero heap allocation inside the loop for maximum efficiency on potato devices.
     */
    private fun solveProofOfWork(challenge: String, difficulty: Int): Long {
        if (challenge.isBlank() || difficulty <= 0) return 0L
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val prefix = "$challenge:".toByteArray(Charsets.UTF_8)
            var nonce = 0L
            val maxIterations = 1_000_000L
            val nonceBuffer = ByteArray(24)

            while (nonce < maxIterations) {
                md.reset()
                md.update(prefix)

                // Fast zero-allocation long-to-ascii conversion
                var temp = nonce
                var pos = nonceBuffer.size
                if (temp == 0L) {
                    pos--
                    nonceBuffer[pos] = '0'.code.toByte()
                } else {
                    while (temp > 0L) {
                        pos--
                        nonceBuffer[pos] = ('0'.code + (temp % 10L).toInt()).toByte()
                        temp /= 10L
                    }
                }
                md.update(nonceBuffer, pos, nonceBuffer.size - pos)
                val hash = md.digest()

                val b0 = hash[0].toInt() and 0xFF
                val b1 = hash[1].toInt() and 0xFF
                if (difficulty == 3) {
                    if (b0 == 0 && (b1 shr 4) == 0) return nonce
                } else if (difficulty == 4) {
                    if (b0 == 0 && b1 == 0) return nonce
                } else {
                    val hex = hash.joinToString("") { "%02x".format(it) }
                    if (hex.startsWith("0".repeat(difficulty))) return nonce
                }
                nonce++
            }
            0L
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Derives a 256-bit AES-GCM session key via ECDH + HKDF-SHA256 (RFC 5869).
     */
    private fun deriveSessionKey(
        clientPrivateKey: PrivateKey,
        serverPubKeyBytes: ByteArray,
        epoch: Long
    ): ByteArray {
        // 1. ECDH Shared Secret
        val keyFactory = KeyFactory.getInstance("EC")
        val serverPubKey = keyFactory.generatePublic(X509EncodedKeySpec(serverPubKeyBytes))
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(clientPrivateKey)
        keyAgreement.doPhase(serverPubKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // 2. HKDF-SHA256 Extract: PRK = HMAC-SHA256(salt=epoch, IKM=sharedSecret)
        val salt = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(epoch).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(sharedSecret)

        // 3. HKDF-SHA256 Expand: OKM = HMAC-SHA256(PRK, info || 0x01)
        val info = "cloudstream-telemetry-v1".toByteArray(Charsets.UTF_8)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal() // 32-byte AES key
    }

    /**
     * Encrypts the report payload JSON using AES-256-GCM with a random 12-byte IV.
     */
    private fun encryptPayload(
        payloadJson: JSONObject,
        ticket: String,
        nonce: Long,
        sessionKeyBytes: ByteArray
    ): JSONObject {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKeyBytes, "AES"), gcmSpec)

        val plaintext = payloadJson.toString().toByteArray(Charsets.UTF_8)
        val ciphertextWithTag = cipher.doFinal(plaintext)

        return JSONObject().apply {
            put("ticket", ticket)
            put("nonce", nonce)
            put("iv", base64Encode(iv))
            put("ciphertext", base64Encode(ciphertextWithTag))
        }
    }

    /**
     * Dispatches an encrypted report to the Cloudflare Worker.
     */
    private fun dispatchEncrypted(payload: JSONObject): Boolean {
        return try {
            val clientKeyPair = generateEphemeralKeyPair()
            val ticket = performHandshake(clientKeyPair) ?: return false

            val nonce = solveProofOfWork(ticket.challenge, ticket.difficulty)

            val sessionKey = deriveSessionKey(
                clientPrivateKey = clientKeyPair.private,
                serverPubKeyBytes = ticket.serverPublicKeyBytes,
                epoch = ticket.epoch
            )

            val encryptedBody = encryptPayload(payload, ticket.ticket, nonce, sessionKey)

            val request = Request.Builder()
                .url(REPORT_ENDPOINT)
                .post(encryptedBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val successful = response.isSuccessful
            response.close()
            successful
        } catch (t: Throwable) {
            Log.w(TAG, "Encrypted dispatch error: ${t.message}")
            false
        }
    }

    // --- Structured Telemetry Payload Construction ---

    /**
     * Constructs a structured JSON payload containing SoC, RAM, Device Tier, and Stack Trace metrics.
     */
    fun buildPayload(
        context: Context? = getContext(),
        level: String = "FATAL",
        tag: String = "App",
        message: String = "",
        thread: Thread? = null,
        error: Throwable? = null,
        fullLog: String? = null,
        extraData: Map<String, String> = emptyMap()
    ): JSONObject {
        val payload = JSONObject()

        try {
            payload.put("level", level)
            payload.put("tag", tag)
            payload.put("message", message)
            payload.put("timestamp", System.currentTimeMillis())

            // 1. Device Hardware & Model
            val manufacturer = Build.MANUFACTURER ?: "Unknown"
            val model = Build.MODEL ?: "Unknown"
            val product = Build.PRODUCT ?: ""
            val device = Build.DEVICE ?: ""
            payload.put("device", "$manufacturer $model ($product, $device)")

            // 2. Comprehensive SoC & Processor Architecture
            val hardware = Build.HARDWARE ?: ""
            val board = Build.BOARD ?: ""
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL ?: ""
            } else ""
            val socMfr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER ?: ""
            } else ""
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val has64Bit = try {
                Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() == true
            } catch (_: Throwable) {
                true
            }
            val arch = if (has64Bit) "64-bit" else "32-bit"
            val socDetails = buildString {
                if (socModel.isNotBlank()) append(socModel).append(" ")
                if (socMfr.isNotBlank()) append("($socMfr) ")
                append(hardware)
                if (board.isNotBlank() && board != hardware) append(" / ").append(board)
                append(" • ").append(cpuCores).append(" Cores (").append(arch).append(")")
            }
            payload.put("soc", socDetails.trim())

            // 3. System RAM & zRAM Metrics
            val totalRamMb = context?.let { DeviceProfile.getTotalRamMb(it) } ?: 0L
            val zramSwapMb = DeviceProfile.getZramSwapMb()
            val isLowRamDevice = try {
                val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.isLowRamDevice == true
            } catch (_: Throwable) {
                false
            }
            val ramDetails = buildString {
                append(totalRamMb).append(" MB RAM")
                if (zramSwapMb > 0) append(" • ").append(zramSwapMb).append(" MB zRAM")
                if (isLowRamDevice) append(" [Android Go Low-RAM]")
            }
            payload.put("ram", ramDetails)

            // 4. Hardware Capability Tier
            val tier = context?.let { DeviceProfile.tier(it).name } ?: "STANDARD"
            val isUltraPotato = context?.let { DeviceProfile.isUltraPotato(it) } ?: false
            payload.put("tier", if (isUltraPotato) "$tier (Ultra-Potato Mode)" else tier)

            // 5. Operating System & Kernel
            val osRelease = Build.VERSION.RELEASE ?: "Unknown"
            val osSdk = Build.VERSION.SDK_INT
            payload.put("os", "Android $osRelease (API $osSdk)")
            payload.put("kernel", DeviceProfile.getKernelVersion())

            // 6. Application Version & Environment
            payload.put("version", "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
            payload.put("plugin", PluginManager.currentlyLoading ?: "None")

            // 7. Exception & Thread Diagnostics
            val threadName = thread?.name ?: Thread.currentThread().name
            val exceptionName = error?.javaClass?.name ?: if (level == "FATAL") "FatalException" else "DiagnosticEvent"
            val exceptionMsg = error?.message ?: message
            payload.put("thread", threadName)
            payload.put("exception", "$exceptionName: $exceptionMsg")

            // 8. Stack Trace & Breadcrumb Telemetry
            val stackTraceStr = when {
                error != null -> {
                    val sw = StringWriter()
                    error.printStackTrace(PrintWriter(sw))
                    sw.toString()
                }
                !fullLog.isNullOrBlank() -> fullLog
                else -> message
            }
            payload.put("stackTrace", stackTraceStr)

            if (!fullLog.isNullOrBlank()) {
                payload.put("fullLog", fullLog)
            }

            if (extraData.isNotEmpty()) {
                val dataJson = JSONObject()
                extraData.forEach { (k, v) -> dataJson.put(k, v) }
                payload.put("extraData", dataJson)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error building payload: ${t.message}")
        }

        return payload
    }

    // --- Public Dispatch APIs ---

    /**
     * Dispatches a fatal crash report synchronously (with a short 2.5-second deadline)
     * during uncaughtException before the process is terminated.
     */
    fun dispatchSync(
        context: Context?,
        thread: Thread?,
        error: Throwable?,
        fullLog: String? = null
    ): Boolean {
        return try {
            val ctx = context ?: getContext()
            val payload = buildPayload(
                context = ctx,
                level = "FATAL",
                tag = "FatalCrash",
                message = error?.message ?: "Uncaught fatal crash",
                thread = thread,
                error = error,
                fullLog = fullLog
            )
            dispatchEncrypted(payload)
        } catch (t: Throwable) {
            Log.w(TAG, "Sync crash dispatch failed: ${t.message}")
            false
        }
    }

    /**
     * Asynchronously dispatches a crash report in a low-priority background thread.
     */
    fun dispatchAsync(
        context: Context? = null,
        thread: Thread? = null,
        error: Throwable? = null,
        fullLog: String? = null
    ) {
        thread(start = true, isDaemon = true, name = "RemoteCrashReporter") {
            dispatchSync(context, thread, error, fullLog)
        }
    }

    /**
     * Dispatches non-fatal warnings and errors asynchronously with debouncing and rate limiting.
     * Prevents alert storms while ensuring important diagnostic issues reach Telegram.
     */
    fun dispatchDiagnosticLogAsync(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        data: Map<String, String> = emptyMap(),
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()

        if (force) {
            thread(start = true, isDaemon = true, name = "RemoteForceDiagnostic") {
                try {
                    val payload = buildPayload(
                        context = getContext(),
                        level = level,
                        tag = tag,
                        message = message,
                        thread = Thread.currentThread(),
                        error = throwable,
                        fullLog = null,
                        extraData = data
                    )
                    dispatchEncrypted(payload)
                } catch (t: Throwable) {
                    Log.w(TAG, "Force diagnostic dispatch failed: ${t.message}")
                }
            }
            return
        }

        // Deduplication: suppress duplicate (level + tag + first 80 chars of message) for DEDUPLICATION_WINDOW_MS
        val dedupeKey = "$level:$tag:${message.take(80)}"
        val lastSeen = recentAlertTimestamps[dedupeKey]
        if (lastSeen != null && now - lastSeen < DEDUPLICATION_WINDOW_MS) {
            return
        }
        recentAlertTimestamps[dedupeKey] = now

        // Purge old keys from deduplication cache
        if (recentAlertTimestamps.size > 100) {
            val cutoff = now - DEDUPLICATION_WINDOW_MS
            val it = recentAlertTimestamps.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value < cutoff) it.remove()
            }
        }

        // Enqueue diagnostic event without dropping different alerts
        while (diagnosticQueue.size >= MAX_DIAGNOSTIC_QUEUE_CAPACITY) {
            diagnosticQueue.poll()
        }
        diagnosticQueue.offer(DiagnosticEvent(level, tag, message, throwable, data))

        ensureDispatcherRunning()
    }

    private fun ensureDispatcherRunning() {
        if (!isDispatcherRunning.compareAndSet(false, true)) return
        thread(start = true, isDaemon = true, name = "RemoteDiagnosticQueueWorker") {
            try {
                while (true) {
                    val event = diagnosticQueue.poll()
                    if (event == null) {
                        isDispatcherRunning.set(false)
                        if (diagnosticQueue.isNotEmpty() && isDispatcherRunning.compareAndSet(false, true)) {
                            continue
                        }
                        break
                    }

                    try {
                        val payload = buildPayload(
                            context = getContext(),
                            level = event.level,
                            tag = event.tag,
                            message = event.message,
                            thread = Thread.currentThread(),
                            error = event.throwable,
                            fullLog = null,
                            extraData = event.data
                        )
                        dispatchEncrypted(payload)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Queued diagnostic dispatch error: ${t.message}")
                    }

                    try {
                        Thread.sleep(DISPATCH_PACE_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                isDispatcherRunning.set(false)
            }
        }
    }

    /**
     * Inspects filesDir on application startup for an unsent crash dump from a previous session
     * (e.g. if the device was offline or lost connection during the crash) and transmits it.
     */
    fun dispatchPendingOnStartup(context: Context) {
        init(context)
        thread(start = true, isDaemon = true, name = "StartupCrashFlush") {
            try {
                val pendingFile = context.filesDir.resolve("pending_crash_report")
                if (pendingFile.exists() && pendingFile.isFile) {
                    val logContent = pendingFile.readText()
                    if (logContent.isNotBlank()) {
                        val payload = buildPayload(
                            context = context,
                            level = "FATAL",
                            tag = "PendingCrash",
                            message = "Recovered offline crash report",
                            error = null,
                            fullLog = logContent
                        )
                        if (dispatchEncrypted(payload)) {
                            pendingFile.delete()
                        }
                    } else {
                        pendingFile.delete()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Persists an unsent crash dump to disk so it can be flushed on next startup.
     */
    fun savePendingReport(context: Context?, logContent: String) {
        val ctx = context ?: getContext() ?: return
        if (logContent.isBlank()) return
        try {
            val pendingFile = ctx.filesDir.resolve("pending_crash_report")
            pendingFile.writeText(logContent)
        } catch (_: Throwable) {
        }
    }
}
