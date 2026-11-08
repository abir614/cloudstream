package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.di.ServiceLocator
import com.lagradost.cloudstream3.domain.engine.PluginArchiveIntegrityEngine
import java.io.File

/**
 * Trust-On-First-Use (TOFU) Plugin Fingerprint Store.
 *
 * Persists SHA-256 cryptographic fingerprints of installed plugins in SQLite.
 *
 * Guarantees:
 * 1. Zero Breakage for Existing Users: All extensions installed prior to this update
 *    are automatically grandfathered in as Trusted on first launch.
 * 2. Supply-Chain Tamper Detection: Detects unauthorized binary modifications or rogue
 *    repository substitution attacks if a plugin file changes unexpectedly.
 * 3. Offline Integrity Verification: Verifies local plugin binary integrity during app boot
 *    prior to ClassLoader instantiation.
 */
object PluginFingerprintStore {

    private const val PREFIX = "plugin_tofu_"

    data class FingerprintRecord(
        @JsonProperty("internalName") val internalName: String,
        @JsonProperty("sha256") val sha256: String,
        @JsonProperty("repositoryUrl") val repositoryUrl: String?,
        @JsonProperty("installedAt") val installedAt: Long,
        @JsonProperty("isTrusted") val isTrusted: Boolean
    )

    sealed class IntegrityResult {
        object Valid : IntegrityResult()
        data class Tampered(val expectedSha256: String, val actualSha256: String) : IntegrityResult()
    }

    private val integrityEngine by lazy {
        ServiceLocator.resolveOrNull<PluginArchiveIntegrityEngine>()
            ?: PluginArchiveIntegrityEngine()
    }

    /**
     * Retrieves the stored fingerprint for a plugin, if any.
     */
    fun getRecord(internalName: String): FingerprintRecord? {
        return getKey<FingerprintRecord>("$PREFIX$internalName")
    }

    /**
     * Records or updates a plugin's trusted SHA-256 fingerprint.
     */
    fun pinFingerprint(
        internalName: String,
        sha256: String,
        repositoryUrl: String? = null
    ) {
        val record = FingerprintRecord(
            internalName = internalName,
            sha256 = sha256.lowercase(),
            repositoryUrl = repositoryUrl,
            installedAt = System.currentTimeMillis(),
            isTrusted = true
        )
        setKey("$PREFIX$internalName", record)
    }

    /**
     * Verifies the integrity of a plugin file on disk before loading.
     * Automatically grandfathers existing plugins without stored records.
     */
    fun verifyAndGrandfather(
        file: File,
        internalName: String,
        repositoryUrl: String? = null
    ): IntegrityResult {
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            return IntegrityResult.Tampered("non-empty", "missing-or-empty")
        }

        val actualSha256 = try {
            integrityEngine.computeFileSha256(file).lowercase()
        } catch (_: Throwable) {
            return IntegrityResult.Tampered("readable", "read-error")
        }

        val stored = getRecord(internalName)
        if (stored == null) {
            // Grandfather existing plugins on first observation
            pinFingerprint(internalName, actualSha256, repositoryUrl)
            return IntegrityResult.Valid
        }

        if (stored.sha256.equals(actualSha256, ignoreCase = true)) {
            return IntegrityResult.Valid
        }

        return IntegrityResult.Tampered(
            expectedSha256 = stored.sha256,
            actualSha256 = actualSha256
        )
    }
}
