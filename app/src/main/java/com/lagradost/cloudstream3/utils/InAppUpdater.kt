package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object InAppUpdater {
    private const val GITHUB_USER_NAME = "abir614"
    private const val GITHUB_REPO = "cloudstream"

    private const val PRERELEASE_PACKAGE_NAME = "com.lagradost.cloudstream3.prerelease"
    private const val LOG_TAG = "InAppUpdater"

    @Serializable
    private data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        @JsonProperty("type") @SerialName("type") val type: String,
        @JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    @Serializable
    private data class Update(
        @JsonProperty("shouldUpdate") @SerialName("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") @SerialName("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") @SerialName("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") @SerialName("updateNodeId") val updateNodeId: String?,
        @JsonProperty("expectedSha256") @SerialName("expectedSha256") val expectedSha256: String? = null,
    )

    private suspend fun extractExpectedSha256(
        assets: List<GithubAsset>,
        targetAssetFileName: String
    ): String? {
        val sumsAsset = assets.firstOrNull {
            it.name.equals("SHA256SUMS", ignoreCase = true) || it.name.equals("SHA256SUMS.txt", ignoreCase = true)
        } ?: return null
        return try {
            val sumsText = app.get(sumsAsset.browserDownloadUrl, cacheTime = 15, cacheUnit = TimeUnit.MINUTES).text
            if (sumsText.isBlank()) return null

            val sigAsset = assets.firstOrNull {
                it.name.equals("SHA256SUMS.sig", ignoreCase = true) ||
                it.name.equals("SHA256SUMS.minisig", ignoreCase = true)
            }
            if (sigAsset != null) {
                val sigText = app.get(sigAsset.browserDownloadUrl, cacheTime = 15, cacheUnit = TimeUnit.MINUTES).text.trim()
                val isValid = UpdateSecurityGate.verifyUpdateManifestSignature(sumsText, sigText)
                if (!isValid) {
                    Log.e(LOG_TAG, "Ed25519 signature verification failed for SHA256SUMS manifest!")
                    return null
                }
                Log.i(LOG_TAG, "Ed25519 signature verified for SHA256SUMS manifest.")
            }

            sumsText.lines().firstOrNull { line ->
                line.contains(targetAssetFileName)
            }?.split("""\s+""".toRegex())?.firstOrNull()?.trim()?.lowercase()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Failed to resolve or verify SHA256SUMS: $e")
            null
        }
    }

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return try {
            when {
                // No updates on debug version
                BuildConfig.DEBUG -> Update(false, null, null, null, null)
                BuildConfig.FLAVOR == "prerelease" || installPrerelease -> getPreReleaseUpdate()
                else -> getReleaseUpdate()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            Update(false, null, null, null, null)
        }
    }

    fun parseVersionScore(versionStr: String?): Long {
        if (versionStr.isNullOrBlank()) return 0L
        val clean = versionStr.removePrefix("v").substringBefore("-").trim()
        val parts = clean.split('.').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            0 -> clean.filter { it.isDigit() }.toLongOrNull() ?: 0L
            1 -> parts[0] * 100_000_000L
            2 -> parts[0] * 100_000_000L + parts[1] * 10_000L
            else -> parts[0] * 100_000_000L + parts[1] * 10_000L + parts[2]
        }
    }

    private fun extractVersionFromFileName(fileName: String): String {
        val semver = Regex("""(\d+\.\d+\.\d+)""").find(fileName)?.value
        if (semver != null) return semver
        val count = Regex("""(?:v|r|-)(\d+)(?:\.apk|-)""").find(fileName)?.groupValues?.getOrNull(1)
        if (count != null) return count
        val intMatch = Regex("""(\d+)""").find(fileName)?.value
        return intMatch ?: fileName
    }

    private suspend fun Activity.getReleaseUpdate(): Update {
        val url = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val releaseHttp = app.get(url, headers = headers, cacheTime = 15, cacheUnit = TimeUnit.MINUTES)

        if (releaseHttp.code == 403 || releaseHttp.code == 429) {
            Log.w(LOG_TAG, "GitHub API rate limit exceeded (HTTP ${releaseHttp.code}). Skipping release update check.")
            return Update(false, null, null, null, null)
        }
        if (!releaseHttp.isSuccessful) {
            Log.w(LOG_TAG, "GitHub API releases request failed with HTTP ${releaseHttp.code}")
            return Update(false, null, null, null, null)
        }

        val response = parseJson<Array<GithubRelease>>(releaseHttp.text).toList()

        val foundList = response.filter { rel ->
            !rel.prerelease
        }.sortedWith(compareBy { release ->
            val tagScore = parseVersionScore(release.tagName)
            if (tagScore > 0L) tagScore else {
                release.assets.firstOrNull { it.contentType == "application/vnd.android.package-archive" || it.name.endsWith(".apk") }?.name?.let { fileName ->
                    parseVersionScore(extractVersionFromFileName(fileName))
                } ?: 0L
            }
        }).toList()

        val found = foundList.lastOrNull() ?: return Update(false, null, null, null, null)
        val apkAssets = found.assets.filter {
            it.contentType == "application/vnd.android.package-archive" || it.name.endsWith(".apk")
        }.sortedByDescending { asset ->
            parseVersionScore(extractVersionFromFileName(asset.name))
        }

        if (apkAssets.isEmpty()) {
            return Update(false, null, null, null, null)
        }

        val supportedAbis = android.os.Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        val foundAsset = when {
            supportedAbis.any { it.contains("arm64") } ->
                apkAssets.firstOrNull { it.name.contains("arm64-v8a") }
            supportedAbis.any { it.contains("armeabi") } ->
                apkAssets.firstOrNull { it.name.contains("armeabi-v7a") }
            else -> null
        } ?: apkAssets.firstOrNull { it.name.contains("universal") }
          ?: apkAssets.first()

        val remoteVersionStr = if (parseVersionScore(found.tagName) > 0L) {
            found.tagName
        } else {
            extractVersionFromFileName(foundAsset.name)
        }
        val remoteScore = parseVersionScore(remoteVersionStr)

        val currentVersion = packageName?.let {
            packageManager.getPackageInfo(it, 0)
        }
        val currentScore = parseVersionScore(currentVersion?.versionName)

        val shouldUpdate = if (foundAsset.browserDownloadUrl.isBlank() || remoteScore <= 0L) {
            false
        } else {
            currentScore < remoteScore
        }

        val expectedSha256 = extractExpectedSha256(found.assets, foundAsset.name)
        return Update(
            shouldUpdate,
            foundAsset.browserDownloadUrl,
            remoteVersionStr,
            found.body,
            found.nodeId,
            expectedSha256
        )
    }

    private suspend fun Activity.getPreReleaseUpdate(): Update {
        val tagUrl =
            "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/git/ref/tags/pre-release"
        val releaseUrl = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val releaseHttp = app.get(releaseUrl, headers = headers, cacheTime = 15, cacheUnit = TimeUnit.MINUTES)

        if (releaseHttp.code == 403 || releaseHttp.code == 429) {
            Log.w(LOG_TAG, "GitHub API rate limit exceeded (HTTP ${releaseHttp.code}). Skipping pre-release update check.")
            return Update(false, null, null, null, null)
        }
        if (!releaseHttp.isSuccessful) {
            Log.w(LOG_TAG, "GitHub API pre-release request failed with HTTP ${releaseHttp.code}")
            return Update(false, null, null, null, null)
        }

        val response = parseJson<Array<GithubRelease>>(releaseHttp.text).toList()

        val found = response.lastOrNull { rel ->
            rel.prerelease || rel.tagName == "pre-release"
        } ?: return Update(false, null, null, null, null)

        val apkAssets = found.assets.filter {
            it.contentType == "application/vnd.android.package-archive" || it.name.endsWith(".apk")
        }.sortedByDescending { asset ->
            parseVersionScore(extractVersionFromFileName(asset.name))
        }

        if (apkAssets.isEmpty()) {
            return Update(false, null, null, null, null)
        }

        // Intelligently select best matching APK for running device architecture
        val supportedAbis = android.os.Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        val foundAsset = when {
            supportedAbis.any { it.contains("arm64") } ->
                apkAssets.firstOrNull { it.name.contains("arm64-v8a") }
            supportedAbis.any { it.contains("armeabi") } ->
                apkAssets.firstOrNull { it.name.contains("armeabi-v7a") }
            else -> null
        } ?: apkAssets.firstOrNull { it.name.contains("universal") }
          ?: apkAssets.first()

        val releaseCommitSha = if (found.targetCommitish.matches(Regex("^[0-9a-fA-F]{7,40}$"))) {
            found.targetCommitish.trim().take(7)
        } else null

        val tagHttp = app.get(tagUrl, headers = headers, cacheTime = 15, cacheUnit = TimeUnit.MINUTES)
        val tagCommitSha = if (tagHttp.isSuccessful) {
            try {
                val tagResponse = parseJson<GithubTag>(tagHttp.text)
                tagResponse.githubObject.sha.trim().take(7)
            } catch (_: Throwable) {
                null
            }
        } else {
            Log.w(LOG_TAG, "GitHub pre-release tag request returned HTTP ${tagHttp.code}")
            null
        }

        val updateCommitHash = releaseCommitSha ?: tagCommitSha ?: found.targetCommitish.take(7)

        Log.d(LOG_TAG, "Resolved pre-release target commit hash: $updateCommitHash")

        val currentHash = currentCommitHash()
        val shouldUpdate = if (currentHash.isNotBlank() && updateCommitHash.isNotBlank()) {
            currentHash != updateCommitHash
        } else {
            false
        }

        val expectedSha256 = extractExpectedSha256(found.assets, foundAsset.name)
        return Update(
            shouldUpdate,
            foundAsset.browserDownloadUrl,
            updateCommitHash,
            found.body,
            found.nodeId,
            expectedSha256
        )
    }

    private val updateLock = Mutex()
    private val isPromptingResume = AtomicBoolean(false)

    const val PREF_KEY_PENDING_UPDATE_FILE = "pending_update_apk_path"
    const val PREF_KEY_PENDING_UPDATE_SHA256 = "pending_update_expected_sha256"

    fun getPendingInstall(context: Context): Pair<File, String?>? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val path = prefs.getString(PREF_KEY_PENDING_UPDATE_FILE, null) ?: return null
        val expectedSha = prefs.getString(PREF_KEY_PENDING_UPDATE_SHA256, null)
        val file = File(path)
        return if (file.exists() && file.isFile && file.length() > 1024 * 1024) {
            Pair(file, expectedSha)
        } else {
            clearPendingInstall(context)
            null
        }
    }

    fun setPendingInstall(context: Context, file: File, expectedSha256: String?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_KEY_PENDING_UPDATE_FILE, file.absolutePath)
            putString(PREF_KEY_PENDING_UPDATE_SHA256, expectedSha256)
        }
    }

    fun clearPendingInstall(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            remove(PREF_KEY_PENDING_UPDATE_FILE)
            remove(PREF_KEY_PENDING_UPDATE_SHA256)
        }
    }

    private var lastDismissedInstallPromptMs = 0L

    fun checkAndResumePendingInstall(activity: Activity) {
        if (isPromptingResume.get()) return
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return
        if (System.currentTimeMillis() - lastDismissedInstallPromptMs < 45_000L) return
        val pending = getPendingInstall(activity) ?: return
        val (apkFile, expectedSha256) = pending

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                return
            }
        }

        val installedVersionCode = try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            PackageInfoCompat.getLongVersionCode(pInfo)
        } catch (_: Throwable) {
            0L
        }
        val archivePkgInfo = try {
            activity.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } catch (_: Throwable) {
            null
        }
        if (archivePkgInfo != null) {
            val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archivePkgInfo)
            if (archiveVersionCode <= installedVersionCode) {
                apkFile.delete()
                clearPendingInstall(activity)
                return
            }
        }

        if (!isPromptingResume.compareAndSet(false, true)) return

        activity.runOnUiThread {
            try {
                if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
                    isPromptingResume.set(false)
                    return@runOnUiThread
                }
                AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                    .setTitle(R.string.install_ready_title)
                    .setMessage(R.string.install_ready_message)
                    .setPositiveButton(R.string.update) { _, _ ->
                        ioSafe {
                            try {
                                UpdateSecurityGate.requestUpdateChallenge()
                                val outcome = UpdateSecurityGate.verifyAndAuthorize(
                                    activity, apkFile, expectedSha256
                                )
                                when (outcome) {
                                    is UpdateSecurityGate.VerificationOutcome.Authorized -> {
                                        val success = UpdateSecurityGate.executeInstallation(
                                            activity, outcome.token
                                        ) { authorizedFile ->
                                            openApk(activity, authorizedFile)
                                        }
                                        if (success) {
                                            clearPendingInstall(activity)
                                        }
                                    }
                                    is UpdateSecurityGate.VerificationOutcome.Rejected -> {
                                        Log.e(LOG_TAG, "Pending update verification failed: ${outcome.reason}")
                                        clearPendingInstall(activity)
                                        activity.runOnUiThread {
                                            showToast(
                                                "Update verification failed: ${outcome.reason}",
                                                Toast.LENGTH_LONG
                                            )
                                        }
                                    }
                                }
                            } finally {
                                isPromptingResume.set(false)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        lastDismissedInstallPromptMs = System.currentTimeMillis()
                        isPromptingResume.set(false)
                    }
                    .setOnDismissListener {
                        isPromptingResume.set(false)
                    }
                    .show()
                    .setDefaultFocus()
            } catch (t: Throwable) {
                logError(t)
                isPromptingResume.set(false)
            }
        }
    }

    private fun Activity.handleAuthorizedUpdate(
        outcome: UpdateSecurityGate.VerificationOutcome.Authorized,
        expectedSha256: String?
    ) {
        val targetAct = com.lagradost.cloudstream3.CommonActivity.activity ?: this
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !targetAct.packageManager.canRequestPackageInstalls()

        setPendingInstall(targetAct, outcome.authorizedFile, expectedSha256)

        if (targetAct.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && targetAct.isDestroyed)) {
            return
        }

        if (needsPermission) {
            targetAct.runOnUiThread {
                try {
                    AlertDialog.Builder(targetAct, R.style.AlertDialogCustom)
                        .setTitle(R.string.install_permission_title)
                        .setMessage(R.string.install_permission_message)
                        .setPositiveButton(R.string.install_permission_button) { _, _ ->
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${targetAct.packageName}")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            targetAct.startActivity(intent)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                        .setDefaultFocus()
                } catch (t: Throwable) {
                    logError(t)
                }
            }
        } else {
            targetAct.runOnUiThread {
                try {
                    AlertDialog.Builder(targetAct, R.style.AlertDialogCustom)
                        .setTitle(R.string.install_ready_title)
                        .setMessage(R.string.install_ready_message)
                        .setPositiveButton(R.string.update) { _, _ ->
                            UpdateSecurityGate.executeInstallation(targetAct, outcome.token) { authorizedFile ->
                                openApk(targetAct, authorizedFile)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                        .setDefaultFocus()
                } catch (t: Throwable) {
                    logError(t)
                }
            }
        }
    }

    private suspend fun Activity.downloadUpdate(url: String, expectedSha256: String? = null): Boolean {
        var progressDialog: AlertDialog? = null
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            Log.d(LOG_TAG, "Downloading update: $url")
            val updatesDir = File(filesDir, "updates").apply { mkdirs() }
            val downloadedFile = File(updatesDir, "CloudStream-Update.apk")

            // Check if existing file matches and is valid
            val pending = getPendingInstall(this)
            if (pending != null && downloadedFile.exists() && downloadedFile.length() > 1024 * 1024) {
                if (expectedSha256.isNullOrBlank() || expectedSha256.equals(pending.second, ignoreCase = true)) {
                    UpdateSecurityGate.requestUpdateChallenge()
                    val outcome = UpdateSecurityGate.verifyAndAuthorize(this, downloadedFile, expectedSha256)
                    if (outcome is UpdateSecurityGate.VerificationOutcome.Authorized) {
                        Log.i(LOG_TAG, "Using existing verified pending update without re-downloading.")
                        handleAuthorizedUpdate(outcome, expectedSha256)
                        return true
                    }
                }
            }

            // Create notification channel
            createNotificationChannel(
                PackageInstallerService.UPDATE_CHANNEL_ID,
                PackageInstallerService.UPDATE_CHANNEL_NAME,
                PackageInstallerService.UPDATE_CHANNEL_DESCRIPTION
            )

            val notifIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntentCompat.getActivity(this, 0, notifIntent, 0, false)
            val notifBuilder = NotificationCompat.Builder(this, PackageInstallerService.UPDATE_CHANNEL_ID)
                .setAutoCancel(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setColor(this.colorFromAttribute(R.attr.colorPrimary))
                .setContentTitle(getString(R.string.update_notification_downloading))
                .setSmallIcon(R.drawable.rdload)
                .setContentIntent(pendingIntent)

            // Setup in-app progress dialog
            val padding = (16 * resources.displayMetrics.density).toInt()
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, padding / 2)
            }
            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = false
                progress = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, padding / 2, 0, padding / 2)
                }
            }
            val progressText = TextView(this).apply {
                text = getString(R.string.update_notification_downloading)
                gravity = Gravity.END
            }
            layout.addView(progressBar)
            layout.addView(progressText)

            runOnUiThread {
                try {
                    progressDialog = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                        .setTitle(R.string.update_notification_downloading)
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton(R.string.background) { d, _ ->
                            d.dismiss()
                        }
                        .show()
                        .apply { setDefaultFocus() }
                } catch (t: Throwable) {
                    logError(t)
                }
            }

            if (downloadedFile.exists()) {
                downloadedFile.delete()
            }

            UpdateSecurityGate.requestUpdateChallenge()

            val isInstalled = updateLock.withLock {
                val response = app.get(url)
                val body = response.body
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                val buf = ByteArray(64 * 1024)
                var lastUpdateMs = 0L

                downloadedFile.outputStream().use { fileOut ->
                    body.byteStream().use { netIn ->
                        var read: Int
                        while (netIn.read(buf).also { read = it } != -1) {
                            fileOut.write(buf, 0, read)
                            downloadedBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateMs >= 120 || (totalBytes > 0 && downloadedBytes == totalBytes)) {
                                lastUpdateMs = now
                                val percent = if (totalBytes > 0) {
                                    ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else 0
                                val downloadedMb = downloadedBytes.toDouble() / (1024.0 * 1024.0)
                                val totalMb = totalBytes.toDouble() / (1024.0 * 1024.0)
                                val progressStr = if (totalBytes > 0) {
                                    String.format(Locale.ROOT, "%.1f MB / %.1f MB (%d%%)", downloadedMb, totalMb, percent)
                                } else {
                                    String.format(Locale.ROOT, "%.1f MB", downloadedMb)
                                }

                                notificationManager?.notify(
                                    PackageInstallerService.UPDATE_NOTIFICATION_ID,
                                    notifBuilder
                                        .setContentText(progressStr)
                                        .setProgress(100, percent, totalBytes <= 0)
                                        .build()
                                )

                                runOnUiThread {
                                    try {
                                        if (progressDialog?.isShowing == true) {
                                            if (totalBytes > 0) {
                                                progressBar.isIndeterminate = false
                                                progressBar.progress = percent
                                            } else {
                                                progressBar.isIndeterminate = true
                                            }
                                            progressText.text = progressStr
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                        fileOut.flush()
                    }
                }

                runOnUiThread {
                    try {
                        if (progressDialog?.isShowing == true) {
                            progressDialog?.dismiss()
                        }
                    } catch (_: Throwable) {}
                }

                val outcome = UpdateSecurityGate.verifyAndAuthorize(this, downloadedFile, expectedSha256)
                when (outcome) {
                    is UpdateSecurityGate.VerificationOutcome.Authorized -> {
                        val completionNotif = NotificationCompat.Builder(this, PackageInstallerService.UPDATE_CHANNEL_ID)
                            .setAutoCancel(true)
                            .setOngoing(false)
                            .setOnlyAlertOnce(true)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setColor(this.colorFromAttribute(R.attr.colorPrimary))
                            .setContentTitle(getString(R.string.install_ready_title))
                            .setContentText(getString(R.string.install_ready_message))
                            .setSmallIcon(R.drawable.rdload)
                            .setContentIntent(pendingIntent)
                            .build()
                        notificationManager?.notify(PackageInstallerService.UPDATE_NOTIFICATION_ID, completionNotif)

                        handleAuthorizedUpdate(outcome, expectedSha256)
                        true
                    }
                    is UpdateSecurityGate.VerificationOutcome.Rejected -> {
                        notificationManager?.cancel(PackageInstallerService.UPDATE_NOTIFICATION_ID)
                        Log.e(LOG_TAG, "Update rejected by security gate: ${outcome.reason}")
                        runOnUiThread {
                            showToast("Update verification failed: ${outcome.reason}", Toast.LENGTH_LONG)
                        }
                        false
                    }
                }
            }

            return isInstalled
        } catch (e: Exception) {
            logError(e)
            UpdateSecurityGate.lockGate()
            runOnUiThread {
                try {
                    if (progressDialog?.isShowing == true) {
                        progressDialog?.dismiss()
                    }
                } catch (_: Throwable) {}
            }
            notificationManager?.cancel(PackageInstallerService.UPDATE_NOTIFICATION_ID)
            return false
        }
    }

    private fun openApk(context: Context, file: File) = safe {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            setPendingInstall(context, file, null)
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return@safe
        }
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", file
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(PackageInstallerService.UPDATE_NOTIFICATION_ID)
        val act = com.lagradost.cloudstream3.CommonActivity.activity ?: (context as? Activity)
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            act.runOnUiThread {
                try {
                    act.startActivity(installIntent)
                } catch (t: Throwable) {
                    context.startActivity(installIntent)
                }
            }
        } else {
            context.startActivity(installIntent)
        }
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
        val isInstalled = try {
            packageManager.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (!runAutoUpdate(checkAutoUpdate = false, installPrerelease = true)) {
            showToast(R.string.prerelease_install_failed)
        }
    }


    /**
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUpdateEnabled =
            settingsManager.getBoolean(getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val update = getAppUpdate(installPrerelease)
        if (!update.shouldUpdate || update.updateURL == null) {
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
            return false
        }

        runOnUiThread {
            safe {
                val currentVersion = packageName?.let {
                    packageManager.getPackageInfo(it, 0)
                }

                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(
                    getString(R.string.new_update_format).format(
                        currentVersion?.versionName, update.updateVersion
                    )
                )

                val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
                val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                    matchResult.groupValues[1]
                } // Sanitized because it looks cluttered

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        // Forcefully start any delayed installations
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return@setPositiveButton

                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        // Check if the setting hasn't been changed
                        if (settingsManager.getInt(
                                getString(R.string.apk_installer_key), -1
                            ) == -1
                        ) {
                            // Set to legacy installer if using MIUI
                            if (isMiUi()) {
                                settingsManager.edit {
                                    putInt(getString(R.string.apk_installer_key), 1)
                                }
                            }
                        }

                        val currentInstaller = settingsManager.getInt(
                            getString(R.string.apk_installer_key), 1
                        )

                        when (currentInstaller) {
                            // New method
                            0 -> {
                                val intent = PackageInstallerService.Companion.getIntent(
                                    this@runAutoUpdate, update.updateURL, update.expectedSha256
                                )
                                ContextCompat.startForegroundService(
                                    this@runAutoUpdate, intent
                                )
                            }
                            // Legacy
                            1 -> {
                                ioSafe {
                                    if (!downloadUpdate(update.updateURL, update.expectedSha256)) {
                                        runOnUiThread {
                                            showToast(
                                                R.string.download_failed, Toast.LENGTH_LONG
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use {
            it.readLine()
        }
    } catch (_: IOException) {
        null
    }
}
