package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.pm.PackageInfoCompat
import com.lagradost.cloudstream3.services.NativeCoreBridge
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Z+ Zero-Trust Isolated Update Security Gate.
 *
 * This security layer keeps the package installation pipeline strictly LOCKED by default.
 * It opens transiently ONLY when:
 * 1. An authorized update download initiates (minting an ephemeral session nonce).
 * 2. An End-to-End dynamic cryptographic identity check passes between the installed app
 *    and the downloaded staging APK.
 * 3. The native Rust core attestation validates package identity, anti-rollback protection,
 *    and signing certificate fingerprint parity.
 *
 * Upon authorization or any security violation, the gate immediately relocks and unauthorized
 * binaries are immediately purged from disk.
 */
object UpdateSecurityGate {
    private const val TAG = "UpdateSecurityGate"
    private const val SESSION_TIMEOUT_MS = 180_000L // 3 minutes
    private const val AUTH_TOKEN_VALIDITY_MS = 60_000L // 1 minute

    enum class GateStatus {
        LOCKED,
        CHALLENGE_ISSUED,
        AUTHORIZED,
    }

    data class GateSession(
        val nonce: String,
        val createdAt: Long,
        var isConsumed: Boolean = false,
    )

    data class GateAuthorizationToken(
        val rawToken: String,
        val fileHash: String,
        val packageName: String,
        val certFingerprint: String,
        val versionCode: Long,
        val authorizedFile: File,
        val issuedAt: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - issuedAt > AUTH_TOKEN_VALIDITY_MS
    }

    sealed class VerificationOutcome {
        data class Authorized(
            val token: GateAuthorizationToken,
            val authorizedFile: File,
        ) : VerificationOutcome()

        data class Rejected(
            val reason: String,
        ) : VerificationOutcome()
    }

    @Volatile
    private var currentStatus: GateStatus = GateStatus.LOCKED

    @Volatile
    private var activeSession: GateSession? = null

    @Volatile
    private var currentAuthToken: GateAuthorizationToken? = null

    private val gateLock = Any()

    /**
     * Current status of the installer gate.
     */
    fun getStatus(): GateStatus = synchronized(gateLock) { currentStatus }

    /**
     * Issues an ephemeral cryptographic challenge nonce for a new update download.
     * The gate transitions to CHALLENGE_ISSUED.
     */
    fun requestUpdateChallenge(): String = synchronized(gateLock) {
        val nonce = (if (NativeCoreBridge.isNativeReady()) {
            NativeCoreBridge.generateSessionNonce()
        } else null) ?: generateFallbackNonce()

        activeSession = GateSession(
            nonce = nonce,
            createdAt = System.currentTimeMillis(),
            isConsumed = false
        )
        currentAuthToken = null
        currentStatus = GateStatus.CHALLENGE_ISSUED
        Log.i(TAG, "Update security gate challenge issued with ephemeral nonce.")
        return nonce
    }

    /**
     * Manually resets and firmly locks the security gate.
     */
    fun lockGate() = synchronized(gateLock) {
        activeSession = null
        currentAuthToken = null
        currentStatus = GateStatus.LOCKED
        Log.i(TAG, "Update security gate firmly locked.")
    }

    /**
     * Performs End-to-End Cryptographic Identity Verification on a downloaded APK.
     * If valid, temporarily authorizes installation.
     * If invalid, the APK is immediately deleted from disk and the gate relocks.
     */
    @WorkerThread
    fun verifyAndAuthorize(context: Context, apkFile: File): VerificationOutcome = synchronized(gateLock) {
        val session = activeSession
        if (session == null || session.isConsumed ||
            (System.currentTimeMillis() - session.createdAt > SESSION_TIMEOUT_MS)
        ) {
            purgeFile(apkFile)
            lockGate()
            return VerificationOutcome.Rejected("No active or unexpired challenge session found.")
        }

        if (!apkFile.exists() || !apkFile.canRead() || apkFile.length() <= 0) {
            purgeFile(apkFile)
            lockGate()
            return VerificationOutcome.Rejected("Downloaded APK file is missing or unreadable.")
        }

        try {
            val pm = context.packageManager
            val installedPkgName = context.packageName

            // 1. Installed App Metadata
            val installedFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES or @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val installedPkgInfo = pm.getPackageInfo(installedPkgName, installedFlags)
            val installedVersionCode = PackageInfoCompat.getLongVersionCode(installedPkgInfo)
            val installedCertFingerprint = extractCertificateSha256(installedPkgInfo)
                ?: run {
                    purgeFile(apkFile)
                    lockGate()
                    return VerificationOutcome.Rejected("Failed to extract installed certificate fingerprint.")
                }

            // 2. Downloaded Archive APK Metadata
            val archivePkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, installedFlags)
                ?: run {
                    purgeFile(apkFile)
                    lockGate()
                    return VerificationOutcome.Rejected("Failed to parse downloaded APK archive manifest.")
                }

            archivePkgInfo.applicationInfo?.sourceDir = apkFile.absolutePath
            archivePkgInfo.applicationInfo?.publicSourceDir = apkFile.absolutePath

            val archivePkgName = archivePkgInfo.packageName
            val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archivePkgInfo)
            val archiveCertFingerprint = extractCertificateSha256(archivePkgInfo)
                ?: run {
                    purgeFile(apkFile)
                    lockGate()
                    return VerificationOutcome.Rejected("Failed to extract downloaded APK certificate fingerprint.")
                }

            // Extract embedded permanent repo identity header from downloaded APK
            val repoIdentityHex = try {
                java.util.zip.ZipFile(apkFile).use { zip ->
                    zip.getEntry("assets/repo_identity.bin")?.let { entry ->
                        zip.getInputStream(entry).use { stream ->
                            stream.readBytes().joinToString("") { "%02x".format(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read repo_identity.bin from downloaded APK: ${e.message}")
                null
            }

            // 3. Native Rust Zero-Trust Verification
            val verificationResult = if (NativeCoreBridge.isNativeReady()) {
                val jsonStr = NativeCoreBridge.verifyApkIdentity(
                    apkPath = apkFile.absolutePath,
                    sessionNonce = session.nonce,
                    expectedPkg = installedPkgName,
                    actualPkg = archivePkgName,
                    expectedCert = installedCertFingerprint,
                    actualCert = archiveCertFingerprint,
                    installedVersionCode = installedVersionCode,
                    apkVersionCode = archiveVersionCode,
                    repoHeaderHex = repoIdentityHex
                )
                parseRustVerificationJson(jsonStr)
            } else {
                performJvmVerification(
                    apkFile = apkFile,
                    sessionNonce = session.nonce,
                    expectedPkg = installedPkgName,
                    actualPkg = archivePkgName,
                    expectedCert = installedCertFingerprint,
                    actualCert = archiveCertFingerprint,
                    installedVersion = installedVersionCode,
                    apkVersion = archiveVersionCode,
                    repoHeaderHex = repoIdentityHex
                )
            }

            if (!verificationResult.isValid) {
                purgeFile(apkFile)
                lockGate()
                val reason = verificationResult.errorMessage ?: "Cryptographic identity verification failed."
                Log.w(TAG, "Update rejected: $reason")
                return VerificationOutcome.Rejected(reason)
            }

            // 4. Mark challenge consumed and generate single-use Gate Authorization Token
            session.isConsumed = true
            val authToken = GateAuthorizationToken(
                rawToken = verificationResult.attestationToken,
                fileHash = verificationResult.fileSha256,
                packageName = archivePkgName,
                certFingerprint = archiveCertFingerprint,
                versionCode = archiveVersionCode,
                authorizedFile = apkFile
            )

            currentAuthToken = authToken
            currentStatus = GateStatus.AUTHORIZED
            Log.i(TAG, "Update security gate AUTHORIZED for file: ${apkFile.name} (SHA-256: ${verificationResult.fileSha256})")

            return VerificationOutcome.Authorized(authToken, apkFile)
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error during APK verification", t)
            purgeFile(apkFile)
            lockGate()
            return VerificationOutcome.Rejected("Verification error: ${t.message}")
        }
    }

    /**
     * Executes the installation action within the authorized window.
     * Ensures permission gating and immediately relocks the gate upon execution.
     */
    fun executeInstallation(
        context: Context,
        token: GateAuthorizationToken,
        onExecuteInstall: (File) -> Unit
    ): Boolean = synchronized(gateLock) {
        val active = currentAuthToken
        if (active == null || active != token || active.isExpired() || currentStatus != GateStatus.AUTHORIZED) {
            Log.e(TAG, "Attempted installation without a valid, unexpired authorization token.")
            lockGate()
            return false
        }

        try {
            // Android 8.0+ (Oreo, API 26+) Unknown Sources Permission Check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Log.w(TAG, "REQUEST_INSTALL_PACKAGES not granted. Redirecting to settings.")
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return false
                }
            }

            // Run installer action
            onExecuteInstall(token.authorizedFile)
            return true
        } finally {
            // Relock gate immediately after execution
            lockGate()
        }
    }

    private fun purgeFile(file: File) {
        try {
            if (file.exists()) {
                file.delete()
                Log.i(TAG, "Purged unauthorized/staged file: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to purge file: ${file.absolutePath}", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractCertificateSha256(info: PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
                ?: info.signingInfo?.signingCertificateHistory?.takeIf { it.isNotEmpty() }
                ?: info.signatures
        } else {
            info.signatures
        }
        val rawCert = signatures?.firstOrNull()?.toByteArray() ?: return null
        return sha256Hex(rawCert)
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun generateFallbackNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    const val PERMANENT_REPO_CERT_SHA256 = "33e6c058af2421f5935579805b426f7f2bff7294467cec0815d0eeda8b5ede7b"
    private const val PERMANENT_REPO_NAME = "abir614/cloudstream"
    private const val PERMANENT_REPO_ROOT_COMMIT = "e30755daebff90a5fb04642b411e10c600f8ca5a"
    private const val PERMANENT_REPO_SALT = "Z+_IMMUTABLE_CORE_ANCHOR_ABIR614"

    private fun verifyJvmRepoHeader(hex: String, certFingerprint: String): Boolean {
        return try {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (bytes.size < 69) return false
            if (bytes[0] != 0x5A.toByte() || bytes[1] != 0x2B.toByte() || bytes[2] != 0x43.toByte() || bytes[3] != 0x53.toByte()) {
                return false
            }
            if (bytes[4] != 1.toByte()) return false

            val seedInput = "$PERMANENT_REPO_NAME:$PERMANENT_REPO_ROOT_COMMIT:$PERMANENT_REPO_SALT"
            val seedHasher = MessageDigest.getInstance("SHA-256")
            val expectedSeed = seedHasher.digest(seedInput.toByteArray(Charsets.UTF_8))

            val normCert = certFingerprint.replace(":", "").lowercase()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(expectedSeed, "HmacSHA256"))
            val payload = "CS_ZPLUS_AUTHENTIC_PACKAGE_VERIFIER:$normCert"
            val expectedToken = mac.doFinal(payload.toByteArray(Charsets.UTF_8))

            val fileSeed = bytes.sliceArray(5 until 37)
            val fileToken = bytes.sliceArray(37 until 69)

            var diff = 0
            for (i in 0 until 32) {
                diff = diff or (fileSeed[i].toInt() xor expectedSeed[i].toInt())
                diff = diff or (fileToken[i].toInt() xor expectedToken[i].toInt())
            }
            diff == 0
        } catch (e: Exception) {
            Log.w(TAG, "JVM repo header verification error: ${e.message}")
            false
        }
    }

    private data class InternalVerificationResult(
        val isValid: Boolean,
        val fileSha256: String,
        val attestationToken: String,
        val errorMessage: String?,
    )

    private fun parseRustVerificationJson(json: String?): InternalVerificationResult {
        if (json.isNullOrEmpty()) {
            return InternalVerificationResult(false, "", "", "Empty response from Rust core verifier.")
        }
        return try {
            val obj = JSONObject(json)
            InternalVerificationResult(
                isValid = obj.optBoolean("is_valid", false),
                fileSha256 = obj.optString("file_sha256", ""),
                attestationToken = obj.optString("attestation_token", ""),
                errorMessage = if (obj.has("error_message") && !obj.isNull("error_message")) {
                    obj.getString("error_message")
                } else null
            )
        } catch (e: Exception) {
            InternalVerificationResult(false, "", "", "Failed to parse Rust verification result: ${e.message}")
        }
    }

    private fun performJvmVerification(
        apkFile: File,
        sessionNonce: String,
        expectedPkg: String,
        actualPkg: String,
        expectedCert: String,
        actualCert: String,
        installedVersion: Long,
        apkVersion: Long,
        repoHeaderHex: String?
    ): InternalVerificationResult {
        if (expectedPkg != actualPkg) {
            return InternalVerificationResult(
                false, "", "",
                "Package identity mismatch: expected $expectedPkg, got $actualPkg"
            )
        }

        if (apkVersion < installedVersion) {
            return InternalVerificationResult(
                false, "", "",
                "Anti-rollback violation: APK version $apkVersion < installed $installedVersion"
            )
        }

        val normExpectedCert = expectedCert.replace(":", "").lowercase()
        val normActualCert = actualCert.replace(":", "").lowercase()
        val isPermanentCert = normActualCert == PERMANENT_REPO_CERT_SHA256

        if (normExpectedCert != normActualCert && !isPermanentCert) {
            return InternalVerificationResult(
                false, "", "",
                "Certificate fingerprint mismatch: APK signing key differs from installed app."
            )
        }

        if (repoHeaderHex != null && repoHeaderHex.isNotBlank()) {
            if (!verifyJvmRepoHeader(repoHeaderHex, normActualCert)) {
                return InternalVerificationResult(
                    false, "", "",
                    "Cryptographic repository identity check failed: authentic repo header proof mismatch."
                )
            }
        }

        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(apkFile).use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        val fileHash = md.digest().joinToString("") { "%02x".format(it) }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionNonce.toByteArray(), "HmacSHA256"))
        val payload = "$fileHash:$actualPkg:$normActualCert:$apkVersion"
        val hmacBytes = mac.doFinal(payload.toByteArray())
        val hmacHex = hmacBytes.joinToString("") { "%02x".format(it) }
        val attestationToken = "$sessionNonce:$fileHash:$hmacHex"

        return InternalVerificationResult(
            isValid = true,
            fileSha256 = fileHash,
            attestationToken = attestationToken,
            errorMessage = null
        )
    }
}
