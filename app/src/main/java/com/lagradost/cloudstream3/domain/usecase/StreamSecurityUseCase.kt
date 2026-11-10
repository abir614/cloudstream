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
     * Fallback URL safety check with strict URI parsing and IP resolution.
     */
    fun isSafeFallback(url: String): Boolean {
        if (!isAllowedScheme(url)) return false
        if (com.lagradost.cloudstream3.services.NativeCoreBridge.isNativeReady()) {
            return com.lagradost.cloudstream3.services.NativeCoreBridge.validateUrlSafety(url)
        }
        return try {
            val uri = java.net.URI(url)
            val host = uri.host?.lowercase() ?: return false
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                host == "0.0.0.0" || host == "127.0.0.1" || host == "::1" || host == "169.254.169.254"
            ) {
                return false
            }
            val isIp = host.matches(Regex("^[0-9.]+$")) || host.contains(":")
            if (isIp) {
                val inet = java.net.InetAddress.getByName(host)
                !com.lagradost.cloudstream3.network.SafeDns.Companion.run { inet.isBlocked() }
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
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
        return try {
            val uri = java.net.URI(url)
            val query = uri.rawQuery ?: return url
            val trackingKeys = setOf(
                "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
                "fbclid", "gclid", "dclid", "msclkid", "zanpid", "ref", "source"
            )
            val cleanQuery = query.split("&")
                .filterNot { param ->
                    val key = param.substringBefore("=").lowercase()
                    trackingKeys.contains(key)
                }
                .joinToString("&")
            val newQuery = if (cleanQuery.isBlank()) null else cleanQuery
            java.net.URI(uri.scheme, uri.authority, uri.rawPath, newQuery, uri.rawFragment).toASCIIString()
        } catch (_: Throwable) {
            url
        }
    }
}
