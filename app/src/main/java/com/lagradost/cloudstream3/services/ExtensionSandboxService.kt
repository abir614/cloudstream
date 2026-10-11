package com.lagradost.cloudstream3.services

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log

/**
 * Isolated Extension Execution Service.
 *
 * Runs in an isolated Linux process (android:isolatedProcess="true", android:process=":plugin_sandbox").
 * This process has a throwaway Linux UID with ZERO Android permissions granted by the kernel.
 * Even if the main CloudStream process is granted MANAGE_EXTERNAL_STORAGE or REQUEST_INSTALL_PACKAGES,
 * this sandbox process CANNOT access files, packages, or privileged OS APIs.
 *
 * IPC Communication is facilitated via Android Messenger and structured Bundles.
 */
class ExtensionSandboxService : Service() {

    private lateinit var incomingMessenger: Messenger

    class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val replyMessenger = msg.replyTo
            val reply = Message.obtain()

            when (msg.what) {
                CMD_PING -> {
                    reply.what = RESP_PING_OK
                    val data = Bundle().apply {
                        putInt(KEY_PID, Process.myPid())
                        putInt(KEY_UID, Process.myUid())
                        putString(KEY_CORE_INFO, if (NativeCoreBridge.isNativeReady()) NativeCoreBridge.getCoreInfo() else "JVM-Fallback")
                    }
                    reply.data = data
                }

                CMD_VALIDATE_URL -> {
                    val url = msg.data?.getString(KEY_URL) ?: ""
                    val isSafe = if (NativeCoreBridge.isNativeReady()) {
                        NativeCoreBridge.validateUrlSafety(url)
                    } else {
                        // Fallback check
                        !url.contains("127.0.0.1") && !url.contains("localhost") && !url.contains("169.254.169.254")
                    }
                    reply.what = RESP_URL_RESULT
                    reply.data = Bundle().apply {
                        putBoolean(KEY_IS_SAFE, isSafe)
                    }
                }

                CMD_EXECUTE_WASM -> {
                    val bytes = msg.data?.getByteArray(KEY_WASM_BYTES) ?: ByteArray(0)
                    val fuel = msg.data?.getLong(KEY_FUEL, 500_000L) ?: 500_000L
                    val maxMemory = msg.data?.getLong(KEY_MAX_MEMORY, 16 * 1024 * 1024L) ?: (16 * 1024 * 1024L)

                    val result = NativeCoreBridge.runWasmExtension(bytes, fuel, maxMemory)
                    reply.what = RESP_WASM_RESULT
                    reply.data = Bundle().apply {
                        putBoolean(KEY_SUCCESS, result.success)
                        putStringArrayList(KEY_LOGS, ArrayList(result.logs))
                        putString(KEY_ERROR, result.error)
                    }
                }

                else -> {
                    reply.what = RESP_UNKNOWN_CMD
                }
            }

            if (replyMessenger != null) {
                try {
                    replyMessenger.send(reply)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to send IPC reply: ${t.message}")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        incomingMessenger = Messenger(IncomingHandler())
        Log.i(TAG, "ExtensionSandboxService started in isolated UID: ${Process.myUid()}, PID: ${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder {
        return incomingMessenger.binder
    }

    companion object {
        const val TAG = "ExtensionSandbox"

        // Request Commands
        const val CMD_PING = 1
        const val CMD_VALIDATE_URL = 2
        const val CMD_EXECUTE_WASM = 3

        // Response Codes
        const val RESP_PING_OK = 100
        const val RESP_URL_RESULT = 101
        const val RESP_WASM_RESULT = 102
        const val RESP_UNKNOWN_CMD = 199

        // Bundle Keys
        const val KEY_PID = "key_pid"
        const val KEY_UID = "key_uid"
        const val KEY_CORE_INFO = "key_core_info"
        const val KEY_URL = "key_url"
        const val KEY_IS_SAFE = "key_is_safe"
        const val KEY_WASM_BYTES = "key_wasm_bytes"
        const val KEY_FUEL = "key_fuel"
        const val KEY_MAX_MEMORY = "key_max_memory"
        const val KEY_SUCCESS = "key_success"
        const val KEY_LOGS = "key_logs"
        const val KEY_ERROR = "key_error"
    }
}
