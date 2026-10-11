package com.lagradost.cloudstream3.domain.engine

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Result of plugin archive validation.
 */
sealed class ArchiveValidationResult {
    object Valid : ArchiveValidationResult()
    data class SecurityViolation(val reason: String) : ArchiveValidationResult()
    data class Corrupted(val reason: String) : ArchiveValidationResult()
}

/**
 * Enterprise Plugin Archive Integrity & Zip-Slip Defense Engine.
 * Enforces Zero-Trust validation on downloaded plugin .zip / .cs3 archives:
 * 1. Blocks Zip-Slip directory traversal attacks (.. / absolute paths).
 * 2. Defends against Zip-Bombs (uncompressed size & entry count limits).
 * 3. Verifies mandatory manifest.json existence and non-zero length.
 * 4. Performs streaming SHA-256 cryptographic verification.
 */
class PluginArchiveIntegrityEngine(
    val maxAllowedEntries: Int = 1_000,
    val maxUncompressedBytes: Long = 100 * 1024 * 1024L // 100 MB max uncompressed
) {
    companion object {
        const val MANIFEST_FILE_NAME = "manifest.json"

        /**
         * Parses and normalizes an expected hash string from plugin metadata.
         * Handles prefixes (e.g. "sha256-", "sha256:", "sha-256-", "SHA256-", "sha512-")
         * and normalizes to (algorithm, hexHash).
         * Returns null if raw string is empty, null, or indicates no hash.
         */
        fun parseExpectedHash(raw: String?): Pair<String, String>? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            if (trimmed.equals("null", ignoreCase = true) || trimmed.equals("none", ignoreCase = true)) {
                return null
            }

            val (algo, hashPart) = when {
                trimmed.startsWith("sha256-", ignoreCase = true) -> "SHA-256" to trimmed.substring(7)
                trimmed.startsWith("sha256:", ignoreCase = true) -> "SHA-256" to trimmed.substring(7)
                trimmed.startsWith("sha-256-", ignoreCase = true) -> "SHA-256" to trimmed.substring(8)
                trimmed.startsWith("sha-256:", ignoreCase = true) -> "SHA-256" to trimmed.substring(8)
                trimmed.startsWith("sha512-", ignoreCase = true) -> "SHA-512" to trimmed.substring(7)
                trimmed.startsWith("sha512:", ignoreCase = true) -> "SHA-512" to trimmed.substring(7)
                trimmed.startsWith("sha-512-", ignoreCase = true) -> "SHA-512" to trimmed.substring(8)
                trimmed.startsWith("sha-512:", ignoreCase = true) -> "SHA-512" to trimmed.substring(8)
                trimmed.startsWith("sha384-", ignoreCase = true) -> "SHA-384" to trimmed.substring(7)
                trimmed.startsWith("sha384:", ignoreCase = true) -> "SHA-384" to trimmed.substring(7)
                trimmed.startsWith("sha1-", ignoreCase = true) -> "SHA-1" to trimmed.substring(5)
                trimmed.startsWith("sha1:", ignoreCase = true) -> "SHA-1" to trimmed.substring(5)
                trimmed.startsWith("md5-", ignoreCase = true) -> "MD5" to trimmed.substring(4)
                trimmed.startsWith("md5:", ignoreCase = true) -> "MD5" to trimmed.substring(4)
                trimmed.contains(':') -> {
                    val prefix = trimmed.substringBefore(':').uppercase()
                    val algoCandidate = when (prefix) {
                        "SHA-256", "SHA256" -> "SHA-256"
                        "SHA-512", "SHA512" -> "SHA-512"
                        "SHA-384", "SHA384" -> "SHA-384"
                        "SHA-1", "SHA1" -> "SHA-1"
                        "MD5" -> "MD5"
                        else -> null
                    }
                    if (algoCandidate != null) algoCandidate to trimmed.substringAfter(':') else null to trimmed
                }
                trimmed.contains('-') && (trimmed.startsWith("sha", ignoreCase = true) || trimmed.startsWith("md", ignoreCase = true)) -> {
                    val prefix = trimmed.substringBefore('-').uppercase()
                    val algoCandidate = when (prefix) {
                        "SHA-256", "SHA256" -> "SHA-256"
                        "SHA-512", "SHA512" -> "SHA-512"
                        "SHA-384", "SHA384" -> "SHA-384"
                        "SHA-1", "SHA1" -> "SHA-1"
                        "MD5" -> "MD5"
                        else -> null
                    }
                    if (algoCandidate != null) algoCandidate to trimmed.substringAfter('-') else null to trimmed
                }
                else -> null to trimmed
            }

            val cleanHash = hashPart.trim().lowercase()
            if (cleanHash.isEmpty()) return null

            // Determine algorithm if not explicitly specified
            val finalAlgo = algo ?: when (cleanHash.length) {
                64 -> "SHA-256"
                128 -> "SHA-512"
                96 -> "SHA-384"
                40 -> "SHA-1"
                32 -> "MD5"
                else -> "SHA-256"
            }

            // Check if hex or base64
            val isHex = cleanHash.all { it in '0'..'9' || it in 'a'..'f' }
            val hexHash = if (isHex) {
                cleanHash
            } else {
                try {
                    val decoded = java.util.Base64.getDecoder().decode(cleanHash)
                    decoded.joinToString("") { "%02x".format(it) }
                } catch (_: Throwable) {
                    try {
                        val decoded = android.util.Base64.decode(cleanHash, android.util.Base64.DEFAULT)
                        decoded.joinToString("") { "%02x".format(it) }
                    } catch (_: Throwable) {
                        cleanHash
                    }
                }
            }

            return finalAlgo to hexHash
        }
    }

    /**
     * Checks if a zip entry path is safe and free of path traversal sequences.
     */
    fun isSafeEntryPath(entryName: String): Boolean {
        if (entryName.isBlank()) return false
        // Normalize backslashes to forward slashes
        val normalized = entryName.replace('\\', '/')

        // Block directory traversal (..)
        if (normalized.contains("../") || normalized.contains("/..") || normalized == "..") {
            return false
        }

        // Block absolute path injection
        if (normalized.startsWith("/") || normalized.startsWith("~")) {
            return false
        }

        // Block Windows drive root specifications (e.g. C:)
        if (normalized.length >= 2 && normalized[1] == ':') {
            return false
        }

        // Block null bytes
        if (normalized.contains('\u0000')) {
            return false
        }

        return true
    }

    /**
     * Computes the cryptographic hash of a file on disk for a given algorithm.
     */
    fun computeFileDigest(file: File, algorithm: String = "SHA-256"): String {
        val digest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the SHA-256 hash of a file on disk.
     */
    fun computeFileSha256(file: File): String = computeFileDigest(file, "SHA-256")

    /**
     * Validates a plugin archive file on disk.
     */
    fun validateArchive(file: File, expectedSha256: String? = null): ArchiveValidationResult {
        if (!file.exists() || !file.isFile) {
            return ArchiveValidationResult.Corrupted("File does not exist or is not a regular file")
        }

        if (file.length() == 0L) {
            return ArchiveValidationResult.Corrupted("Archive file is empty (0 bytes)")
        }

        // 1. Verify cryptographic checksum if provided
        val parsedHash = parseExpectedHash(expectedSha256)
        if (parsedHash != null) {
            val (algo, expectedHex) = parsedHash
            val actualHash = computeFileDigest(file, algo)
            if (!actualHash.equals(expectedHex, ignoreCase = true)) {
                return ArchiveValidationResult.SecurityViolation(
                    "Cryptographic checksum mismatch for algorithm $algo. Expected: $expectedSha256, Actual: $actualHash"
                )
            }
        }

        // 2. Validate zip structure, Zip-Slip safety, manifest, and decompression bounds
        try {
            ZipFile(file).use { zip ->
                var entryCount = 0
                var totalUncompressedSize = 0L
                var hasManifest = false

                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++

                    if (entryCount > maxAllowedEntries) {
                        return ArchiveValidationResult.SecurityViolation(
                            "Archive exceeds maximum allowed entry count ($maxAllowedEntries). Possible Zip-Bomb."
                        )
                    }

                    // Zip-Slip check
                    if (!isSafeEntryPath(entry.name)) {
                        return ArchiveValidationResult.SecurityViolation(
                            "Malicious Zip-Slip path traversal sequence detected in entry: '${entry.name}'"
                        )
                    }

                    val size = entry.size
                    if (size > 0) {
                        totalUncompressedSize += size
                        if (totalUncompressedSize > maxUncompressedBytes) {
                            return ArchiveValidationResult.SecurityViolation(
                                "Archive uncompressed size exceeds limit ($maxUncompressedBytes bytes). Possible decompression bomb."
                            )
                        }
                    }

                    val cleanName = entry.name.replace('\\', '/').removePrefix("./").trimStart('/')
                    if (cleanName.equals(MANIFEST_FILE_NAME, ignoreCase = true) && !entry.isDirectory) {
                        if (entry.size > 0 || entry.size == -1L) {
                            hasManifest = true
                        } else {
                            try {
                                zip.getInputStream(entry).use { stream ->
                                    if (stream.read() != -1) {
                                        hasManifest = true
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }

                if (!hasManifest) {
                    return ArchiveValidationResult.Corrupted(
                        "Plugin archive is missing mandatory '$MANIFEST_FILE_NAME'"
                    )
                }
            }
        } catch (t: Throwable) {
            return ArchiveValidationResult.Corrupted("Malformed or corrupt ZIP archive: ${t.message}")
        }

        return ArchiveValidationResult.Valid
    }
}
