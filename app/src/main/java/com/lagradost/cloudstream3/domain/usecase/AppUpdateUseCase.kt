package com.lagradost.cloudstream3.domain.usecase

import android.content.Context
import com.lagradost.cloudstream3.utils.UpdateSecurityGate
import java.io.File

/**
 * Domain UseCase that orchestrates application updates under the Z+ Zero-Trust Security Gate.
 * Decouples Activities and ViewModels from low-level cryptographic state and intent dispatching.
 */
class AppUpdateUseCase(
    private val securityGate: UpdateSecurityGate = UpdateSecurityGate
) {
    /**
     * Evaluates whether an update is required given the current installed and remote version codes.
     */
    fun isUpdateRequired(currentVersion: Long, remoteVersion: Long): Boolean {
        return remoteVersion > currentVersion
    }

    /**
     * Issues an ephemeral cryptographic session nonce for a verified update stream.
     */
    fun beginUpdateSession(): String {
        return securityGate.requestUpdateChallenge()
    }

    /**
     * Cryptographically audits a downloaded staging APK against the running application's identity.
     */
    fun verifyUpdateBinary(
        context: Context,
        apkFile: File,
        expectedSha256: String? = null
    ): UpdateSecurityGate.VerificationOutcome {
        return securityGate.verifyAndAuthorize(context, apkFile, expectedSha256)
    }

    /**
     * Dispatches package installation for an authorized staging binary.
     */
    fun installVerifiedUpdate(
        context: Context,
        token: UpdateSecurityGate.GateAuthorizationToken,
        onExecuteInstall: (File) -> Unit = {}
    ): Boolean {
        return securityGate.executeInstallation(context, token, onExecuteInstall)
    }

    /**
     * Aborts the update session and firmly locks the gate.
     */
    fun abortUpdate() {
        securityGate.lockGate()
    }
}
