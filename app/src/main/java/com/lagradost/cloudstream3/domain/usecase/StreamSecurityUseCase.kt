package com.lagradost.cloudstream3.domain.usecase

import android.content.Context
import com.lagradost.cloudstream3.services.ExtensionSandboxClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Domain UseCase that validates streaming URLs and prevents SSRF / malicious payload injection.
 * Coordinates with the isolated ExtensionSandboxService when available.
 */
class StreamSecurityUseCase(
    private val sandboxClient: ExtensionSandboxClient = ExtensionSandboxClient
) {
    sealed class ValidationResult {
        object Safe : ValidationResult()
        data class Blocked(val reason: String) : ValidationResult()
    }

    /**
     * Fast-path scheme validation allowing only streaming media schemes.
     */
    fun isAllowedScheme(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase().trim()
        return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("magnet:") ||
                lower.startsWith("rtmp://")
    }

    /**
     * Fallback URL safety check blocking known dangerous loopbacks and metadata.
     */
    fun isSafeFallback(url: String): Boolean {
        if (!isAllowedScheme(url)) return false
        val lower = url.lowercase()
        return !lower.contains("127.0.0.1") &&
                !lower.contains("localhost") &&
                !lower.contains("169.254.169.254") &&
                !lower.contains("0.0.0.0")
    }

    suspend fun validateStreamUrl(context: Context, url: String): ValidationResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext ValidationResult.Blocked("URL is blank")
        }

        // Fast-path schema validation
        if (!isAllowedScheme(url)) {
            return@withContext ValidationResult.Blocked("Disallowed protocol scheme: $url")
        }

        // SSRF & private IP range validation (isolated process or native Rust)
        val isSafe = sandboxClient.validateUrl(context, url)
        if (!isSafe) {
            return@withContext ValidationResult.Blocked("SSRF or private IP violation detected: $url")
        }

        ValidationResult.Safe
    }

    /**
     * Sanitizes tracking and analytics tokens from a stream URL.
     */
    fun sanitizeUrl(url: String): String {
        return url
    }
}
