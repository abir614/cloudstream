package com.lagradost.cloudstream3.services

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * Isolated Extension Execution Service.
 *
 * Runs in an isolated Linux process (android:isolatedProcess="true", android:process=":plugin_sandbox").
 * This process has a throwaway Linux UID with ZERO Android permissions granted by the kernel.
 * Even if the main CloudStream process is granted MANAGE_EXTERNAL_STORAGE or REQUEST_INSTALL_PACKAGES,
 * this sandbox process CANNOT access files, packages, or privileged OS APIs.
 */
class ExtensionSandboxService : Service() {

    private val binder = SandboxBinder()

    class SandboxBinder : Binder() {
        /**
         * Verifies URL safety via the Rust core inside the isolated process.
         */
        fun isUrlSafe(url: String): Boolean {
            return if (NativeCoreBridge.isNativeReady()) {
                NativeCoreBridge.validateUrlSafety(url)
            } else {
                // Fallback basic check
                !url.contains("127.0.0.1") && !url.contains("localhost") && !url.contains("169.254.169.254")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ExtensionSandboxService started in isolated process")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    companion object {
        private const val TAG = "ExtensionSandbox"
    }
}
