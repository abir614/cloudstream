package com.lagradost.cloudstream3.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe client for the isolated ExtensionSandboxService.
 * Handles binding, reconnecting, and asynchronous IPC calls.
 */
object ExtensionSandboxClient {
    private const val TAG = "SandboxClient"
    private const val IPC_TIMEOUT_MS = 5_000L

    private var serviceMessenger: Messenger? = null
    private val isBound = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceMessenger = Messenger(service)
            isBound.set(true)
            isConnecting.set(false)
            Log.i(TAG, "Connected to isolated ExtensionSandboxService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            isBound.set(false)
            isConnecting.set(false)
            Log.w(TAG, "Disconnected from ExtensionSandboxService")
        }
    }

    /**
     * Binds to the sandbox service if not already connected.
     */
    fun bind(context: Context) {
        if (isBound.get() || isConnecting.get()) return
        isConnecting.set(true)
        try {
            val intent = Intent(context, ExtensionSandboxService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            isConnecting.set(false)
            Log.e(TAG, "Failed to bind to ExtensionSandboxService: ${t.message}")
        }
    }

    /**
     * Disconnects from the sandbox service.
     */
    fun unbind(context: Context) {
        if (isBound.get()) {
            try {
                context.unbindService(connection)
            } catch (_: Throwable) {}
            isBound.set(false)
            serviceMessenger = null
        }
    }

    /**
     * Checks if the device is a low-memory / potato hardware tier (<= 768MB RAM or isLowRamDevice).
     */
    fun isPotatoDevice(context: Context): Boolean {
        if (com.lagradost.cloudstream3.utils.DeviceProfile.tier(context) == com.lagradost.cloudstream3.utils.DeviceTier.LOW) return true
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (actManager?.isLowRamDevice == true) return true
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        return memInfo.totalMem in 1..805306368L
    }

    /**
     * Unbinds the sandbox service to reclaim ~35MB of resident memory for video playback.
     * On potato/low-RAM devices (<= 768MB RAM or isLowRamDevice), this ensures the isolated process
     * can be trimmed or collected by Android LMK, granting full hardware buffer headroom to ExoPlayer.
     */
    fun releaseForPlayback(context: Context) {
        if (isPotatoDevice(context)) {
            Log.i(TAG, "Potato device detected: unbinding ExtensionSandboxService to reclaim playback RAM")
            unbind(context)
        }
    }

    /**
     * Validates a URL against SSRF and private network attacks inside the isolated process.
     * Falls back to in-process native validation if sandbox IPC is unavailable.
     */
    suspend fun validateUrl(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        bind(context)
        val messenger = serviceMessenger
        if (messenger == null) {
            // In-process fallback
            return@withContext if (NativeCoreBridge.isNativeReady()) {
                NativeCoreBridge.validateUrlSafety(url)
            } else {
                !url.contains("127.0.0.1") && !url.contains("localhost") && !url.contains("169.254.169.254")
            }
        }

        val deferred = CompletableDeferred<Boolean>()
        val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == ExtensionSandboxService.RESP_URL_RESULT) {
                    val safe = msg.data?.getBoolean(ExtensionSandboxService.KEY_IS_SAFE, false) ?: false
                    deferred.complete(safe)
                } else {
                    deferred.complete(false)
                }
            }
        })

        val req = Message.obtain().apply {
            what = ExtensionSandboxService.CMD_VALIDATE_URL
            replyTo = replyMessenger
            data = Bundle().apply {
                putString(ExtensionSandboxService.KEY_URL, url)
            }
        }

        try {
            messenger.send(req)
            withTimeoutOrNull(IPC_TIMEOUT_MS) { deferred.await() } ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "IPC URL validation failed: ${t.message}, falling back to local")
            if (NativeCoreBridge.isNativeReady()) NativeCoreBridge.validateUrlSafety(url) else false
        }
    }

    /**
     * Runs untrusted WebAssembly bytecode inside the isolated process.
     */
    suspend fun executeWasm(
        context: Context,
        wasmBytes: ByteArray,
        fuel: Long = 500_000L,
        maxMemoryBytes: Long = 16 * 1024 * 1024L
    ): NativeCoreBridge.WasmExecutionResult = withContext(Dispatchers.IO) {
        bind(context)
        val messenger = serviceMessenger
        if (messenger == null) {
            return@withContext NativeCoreBridge.runWasmExtension(wasmBytes, fuel, maxMemoryBytes)
        }

        val deferred = CompletableDeferred<NativeCoreBridge.WasmExecutionResult>()
        val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == ExtensionSandboxService.RESP_WASM_RESULT) {
                    val success = msg.data?.getBoolean(ExtensionSandboxService.KEY_SUCCESS, false) ?: false
                    val error = msg.data?.getString(ExtensionSandboxService.KEY_ERROR)
                    val logs = msg.data?.getStringArrayList(ExtensionSandboxService.KEY_LOGS) ?: ArrayList()
                    deferred.complete(NativeCoreBridge.WasmExecutionResult(success, logs, error))
                } else {
                    deferred.complete(NativeCoreBridge.WasmExecutionResult(false, emptyList(), "Unknown IPC response: ${msg.what}"))
                }
            }
        })

        val req = Message.obtain().apply {
            what = ExtensionSandboxService.CMD_EXECUTE_WASM
            replyTo = replyMessenger
            data = Bundle().apply {
                putByteArray(ExtensionSandboxService.KEY_WASM_BYTES, wasmBytes)
                putLong(ExtensionSandboxService.KEY_FUEL, fuel)
                putLong(ExtensionSandboxService.KEY_MAX_MEMORY, maxMemoryBytes)
            }
        }

        try {
            messenger.send(req)
            withTimeoutOrNull(IPC_TIMEOUT_MS) { deferred.await() }
                ?: NativeCoreBridge.WasmExecutionResult(false, emptyList(), "IPC timeout after ${IPC_TIMEOUT_MS}ms")
        } catch (t: Throwable) {
            Log.w(TAG, "IPC WASM execution failed: ${t.message}")
            NativeCoreBridge.runWasmExtension(wasmBytes, fuel, maxMemoryBytes)
        }
    }
}
