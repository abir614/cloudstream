package com.lagradost.cloudstream3.services

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build.VERSION.SDK_INT
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ApkInstaller
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.InAppUpdater
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UpdateSecurityGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class PackageInstallerService : Service() {
    private var installer: ApkInstaller? = null

    private val baseNotification by lazy {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntentCompat.getActivity(this, 0, intent, 0, false)

        NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
            .setAutoCancel(false)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // If low priority then the notification might not show :(
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(this.colorFromAttribute(R.attr.colorPrimary))
            .setContentTitle(getString(R.string.update_notification_downloading))
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.drawable.rdload)
    }

    override fun onCreate() {
        this.createNotificationChannel(
            UPDATE_CHANNEL_ID,
            UPDATE_CHANNEL_NAME,
            UPDATE_CHANNEL_DESCRIPTION
        )
        if (SDK_INT >= 29)
        startForeground(UPDATE_NOTIFICATION_ID, baseNotification.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(UPDATE_NOTIFICATION_ID, baseNotification.build())
    }

    private val updateLock = Mutex()

    private suspend fun downloadUpdate(url: String, expectedSha256: String? = null): Boolean {
        try {
            Log.d("PackageInstallerService", "Downloading update: $url")
            val appUpdateName = "CloudStream"
            val appUpdateSuffix = "apk"

            // Request ephemeral challenge from UpdateSecurityGate
            UpdateSecurityGate.requestUpdateChallenge()

            // Prepare persistent updates directory
            val updatesDir = File(this@PackageInstallerService.filesDir, "updates").apply { mkdirs() }
            val stagedFile = File(updatesDir, "CloudStream-Update.apk")
            if (stagedFile.exists()) {
                stagedFile.delete()
            }

            val success = updateLock.withLock {
                updateNotificationProgress(
                    0f,
                    ApkInstaller.InstallProgressStatus.Downloading
                )

                val body = app.get(url).body
                val totalSize = body.contentLength()
                var currentSize = 0L
                var lastUpdateMs = 0L

                stagedFile.outputStream().use { fileOut ->
                    body.byteStream().use { netIn ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (netIn.read(buf).also { read = it } != -1) {
                            fileOut.write(buf, 0, read)
                            currentSize += read

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateMs >= 120 || (totalSize > 0 && currentSize == totalSize)) {
                                lastUpdateMs = now
                                val percentage = if (totalSize > 0) currentSize / totalSize.toFloat() else 0f
                                val downloadedMb = currentSize.toDouble() / (1024.0 * 1024.0)
                                val totalMb = totalSize.toDouble() / (1024.0 * 1024.0)
                                val progressText = if (totalSize > 0) {
                                    String.format(Locale.ROOT, "%.1f MB / %.1f MB (%d%%)", downloadedMb, totalMb, (percentage * 100).toInt())
                                } else {
                                    String.format(Locale.ROOT, "%.1f MB", downloadedMb)
                                }
                                updateNotificationProgress(
                                    percentage,
                                    ApkInstaller.InstallProgressStatus.Downloading,
                                    progressText
                                )
                            }
                        }
                        fileOut.flush()
                    }
                }

                val outcome = UpdateSecurityGate.verifyAndAuthorize(this@PackageInstallerService, stagedFile, expectedSha256)
                if (outcome !is UpdateSecurityGate.VerificationOutcome.Authorized) {
                    val reason = (outcome as? UpdateSecurityGate.VerificationOutcome.Rejected)?.reason
                        ?: "Update rejected by security gate"
                    Log.e("PackageInstallerService", "Update verification failed: $reason")
                    updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Failed)
                    return false
                }

                // Register pending install so that if install permission is needed, onResume can resume
                InAppUpdater.setPendingInstall(this@PackageInstallerService, stagedFile, expectedSha256)

                installer = ApkInstaller(this@PackageInstallerService)
                val installSuccessful = UpdateSecurityGate.executeInstallation(
                    this@PackageInstallerService,
                    outcome.token
                ) { authorizedFile ->
                    val verifiedSize = authorizedFile.length()
                    authorizedFile.inputStream().use { verifiedIn ->
                        installer?.installApk(this@PackageInstallerService, verifiedIn, verifiedSize, {
                            // Handled during download
                        }) { status ->
                            updateNotificationProgress(0f, status)
                        }
                    }
                }
                installSuccessful
            }
            return success
        } catch (e: Exception) {
            logError(e)
            UpdateSecurityGate.lockGate()
            updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Failed)
            return false
        }
    }

    private fun updateNotificationProgress(
        percentage: Float,
        state: ApkInstaller.InstallProgressStatus,
        contentText: String? = null
    ) {
        val text = when (state) {
            ApkInstaller.InstallProgressStatus.Installing -> R.string.update_notification_installing
            ApkInstaller.InstallProgressStatus.Preparing, ApkInstaller.InstallProgressStatus.Downloading -> R.string.update_notification_downloading
            ApkInstaller.InstallProgressStatus.Failed -> R.string.update_notification_failed
        }

        val newNotification = baseNotification
            .setContentTitle(getString(text))
            .apply {
                if (contentText != null) {
                    setContentText(contentText)
                }
                if (state == ApkInstaller.InstallProgressStatus.Failed) {
                    setSmallIcon(R.drawable.rderror)
                    setAutoCancel(true)
                } else {
                    setProgress(
                        10000, (10000 * percentage).roundToInt(),
                        state != ApkInstaller.InstallProgressStatus.Downloading
                    )
                }
            }
            .build()

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Persistent notification on failure
        val id =
            if (state == ApkInstaller.InstallProgressStatus.Failed) UPDATE_NOTIFICATION_ID + 1 else UPDATE_NOTIFICATION_ID
        notificationManager.notify(id, newNotification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val expectedSha256 = intent.getStringExtra(EXTRA_EXPECTED_SHA256)
        ioSafe {
            downloadUpdate(url, expectedSha256)
            // Close the service after the update is done
            // If no sleep then the install prompt may not appear and the notification
            // will disappear instantly
            delay(60_000)
            this@PackageInstallerService.stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        installer?.unregisterInstallActionReceiver()
        installer = null
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onTimeout(reason: Int) {
        stopSelf()
        Log.e("PackageInstallerService", "Service stopped due to timeout: $reason")
    }

    companion object {
        private const val EXTRA_URL = "EXTRA_URL"
        private const val EXTRA_EXPECTED_SHA256 = "EXTRA_EXPECTED_SHA256"

        const val UPDATE_CHANNEL_ID = "cloudstream3.updates"
        const val UPDATE_CHANNEL_NAME = "App Updates"
        const val UPDATE_CHANNEL_DESCRIPTION = "App updates notification channel"
        const val UPDATE_NOTIFICATION_ID = -68454136 // Random unique

        fun getIntent(
            context: Context,
            url: String,
            expectedSha256: String? = null,
        ): Intent {
            return Intent(context, PackageInstallerService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_EXPECTED_SHA256, expectedSha256)
        }
    }
}