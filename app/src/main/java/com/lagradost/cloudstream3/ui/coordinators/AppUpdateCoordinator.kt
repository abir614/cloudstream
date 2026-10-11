package com.lagradost.cloudstream3.ui.coordinators

import com.lagradost.cloudstream3.domain.usecase.AppUpdateUseCase
import com.lagradost.cloudstream3.utils.UpdateSecurityGate

/**
 * Decoupled coordinator for evaluating application update availability
 * and challenging the UpdateSecurityGate before launching installation intents.
 */
class AppUpdateCoordinator(
    private val updateUseCase: AppUpdateUseCase = AppUpdateUseCase()
) {
    sealed class UpdateState {
        object Idle : UpdateState()
        data class UpdateAvailable(val currentVersion: Long, val newVersion: Long, val updateUrl: String) : UpdateState()
        object UpToDate : UpdateState()
        data class SecurityChallengeFailed(val reason: String) : UpdateState()
    }

    fun evaluateUpdate(
        currentInstalledVersion: Long,
        remoteVersion: Long,
        downloadUrl: String
    ): UpdateState {
        if (!updateUseCase.isUpdateRequired(currentInstalledVersion, remoteVersion)) {
            return UpdateState.UpToDate
        }

        // Challenge security gate
        val challengeNonce = UpdateSecurityGate.requestUpdateChallenge()
        if (challengeNonce.isBlank()) {
            return UpdateState.SecurityChallengeFailed("Failed to acquire cryptographic session nonce")
        }

        return UpdateState.UpdateAvailable(
            currentVersion = currentInstalledVersion,
            newVersion = remoteVersion,
            updateUrl = downloadUrl
        )
    }
}
