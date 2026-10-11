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
     * Computes the SHA-256 hash of a file on disk.
     */
    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
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
        if (expectedSha256 != null && expectedSha256.isNotBlank()) {
            val actualHash = computeFileSha256(file)
            if (!actualHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                return ArchiveValidationResult.SecurityViolation(
                    "SHA-256 checksum mismatch. Expected: $expectedSha256, Actual: $actualHash"
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

                    if (entry.name == MANIFEST_FILE_NAME && !entry.isDirectory) {
                        if (entry.size > 0 || entry.size == -1L) {
                            hasManifest = true
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
