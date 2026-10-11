package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.domain.engine.ArchiveValidationResult
import com.lagradost.cloudstream3.domain.engine.PluginArchiveIntegrityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginArchiveIntegrityEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var engine: PluginArchiveIntegrityEngine

    @Before
    fun setup() {
        engine = PluginArchiveIntegrityEngine(maxAllowedEntries = 100, maxUncompressedBytes = 10 * 1024 * 1024L)
    }

    @Test
    fun `test isSafeEntryPath permits valid relative paths`() {
        assertTrue(engine.isSafeEntryPath("manifest.json"))
        assertTrue(engine.isSafeEntryPath("classes.dex"))
        assertTrue(engine.isSafeEntryPath("assets/icon.png"))
        assertTrue(engine.isSafeEntryPath("lib/armeabi-v7a/libnative.so"))
    }

    @Test
    fun `test isSafeEntryPath detects and blocks Zip-Slip path traversal attacks`() {
        // Relative traversal
        assertFalse(engine.isSafeEntryPath("../evil.sh"))
        assertFalse(engine.isSafeEntryPath("assets/../../evil.dex"))
        assertFalse(engine.isSafeEntryPath(".."))
        assertFalse(engine.isSafeEntryPath("a/b/../../../etc/passwd"))

        // Absolute paths
        assertFalse(engine.isSafeEntryPath("/system/bin/sh"))
        assertFalse(engine.isSafeEntryPath("/data/data/app/lib.so"))
        assertFalse(engine.isSafeEntryPath("~/root_file"))

        // Windows drive specs and backslash traversal
        assertFalse(engine.isSafeEntryPath("C:\\Windows\\System32\\cmd.exe"))
        assertFalse(engine.isSafeEntryPath("..\\..\\payload.exe"))

        // Null byte injection
        assertFalse(engine.isSafeEntryPath("manifest.json\u0000.sh"))
        assertFalse(engine.isSafeEntryPath(""))
        assertFalse(engine.isSafeEntryPath("   "))
    }

    private fun createZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `test validateArchive passes valid archive containing manifest`() {
        val zipFile = tempFolder.newFile("valid_plugin.cs3")
        createZip(
            zipFile,
            mapOf(
                "manifest.json" to "{\"name\":\"TestPlugin\",\"version\":1}",
                "classes.dex" to "fake dex bytes"
            )
        )

        val result = engine.validateArchive(zipFile)
        assertTrue(result is ArchiveValidationResult.Valid)
    }

    @Test
    fun `test validateArchive fails when manifest is missing`() {
        val zipFile = tempFolder.newFile("missing_manifest.cs3")
        createZip(
            zipFile,
            mapOf("classes.dex" to "fake dex bytes")
        )

        val result = engine.validateArchive(zipFile)
        assertTrue(result is ArchiveValidationResult.Corrupted)
        val corrupted = result as ArchiveValidationResult.Corrupted
        assertTrue(corrupted.reason.contains("manifest.json"))
    }

    @Test
    fun `test validateArchive detects Zip-Slip security violation in zip contents`() {
        val zipFile = tempFolder.newFile("malicious_zipslip.cs3")
        createZip(
            zipFile,
            mapOf(
                "manifest.json" to "{\"name\":\"Evil\"}",
                "../evil_exploit.so" to "malicious payload"
            )
        )

        val result = engine.validateArchive(zipFile)
        assertTrue(result is ArchiveValidationResult.SecurityViolation)
        val violation = result as ArchiveValidationResult.SecurityViolation
        assertTrue(violation.reason.contains("Zip-Slip"))
    }

    @Test
    fun `test validateArchive verifies SHA-256 integrity checksum`() {
        val zipFile = tempFolder.newFile("hashed_plugin.cs3")
        createZip(zipFile, mapOf("manifest.json" to "{\"name\":\"Valid\"}"))

        val correctHash = engine.computeFileSha256(zipFile)
        val validResult = engine.validateArchive(zipFile, expectedSha256 = correctHash)
        assertTrue(validResult is ArchiveValidationResult.Valid)

        // Standard Cloudstream extension format: sha256-<hash>
        val prefixedResult = engine.validateArchive(zipFile, expectedSha256 = "sha256-$correctHash")
        assertTrue(prefixedResult is ArchiveValidationResult.Valid)

        // Colon format and case insensitivity
        val colonResult = engine.validateArchive(zipFile, expectedSha256 = "sha256:$correctHash")
        assertTrue(colonResult is ArchiveValidationResult.Valid)

        val upperResult = engine.validateArchive(zipFile, expectedSha256 = "SHA256:${correctHash.uppercase()}")
        assertTrue(upperResult is ArchiveValidationResult.Valid)

        val hyphenResult = engine.validateArchive(zipFile, expectedSha256 = "sha-256-$correctHash")
        assertTrue(hyphenResult is ArchiveValidationResult.Valid)

        // Null and blank strings should skip verification and remain valid
        val nullResult = engine.validateArchive(zipFile, expectedSha256 = null)
        assertTrue(nullResult is ArchiveValidationResult.Valid)

        val blankResult = engine.validateArchive(zipFile, expectedSha256 = "   ")
        assertTrue(blankResult is ArchiveValidationResult.Valid)

        val nullStrResult = engine.validateArchive(zipFile, expectedSha256 = "null")
        assertTrue(nullStrResult is ArchiveValidationResult.Valid)

        val invalidResult = engine.validateArchive(zipFile, expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000")
        assertTrue(invalidResult is ArchiveValidationResult.SecurityViolation)

        val invalidPrefixed = engine.validateArchive(zipFile, expectedSha256 = "sha256-0000000000000000000000000000000000000000000000000000000000000000")
        assertTrue(invalidPrefixed is ArchiveValidationResult.SecurityViolation)
    }

    @Test
    fun `test parseExpectedHash normalizes various hash formats correctly`() {
        val testSha256 = "742a29f499669c954b8993f283ce3b45b562f29bf9156486e2bb6e36e871ac9b"

        assertEquals("SHA-256" to testSha256, PluginArchiveIntegrityEngine.parseExpectedHash(testSha256))
        assertEquals("SHA-256" to testSha256, PluginArchiveIntegrityEngine.parseExpectedHash("sha256-$testSha256"))
        assertEquals("SHA-256" to testSha256, PluginArchiveIntegrityEngine.parseExpectedHash("sha256:$testSha256"))
        assertEquals("SHA-256" to testSha256, PluginArchiveIntegrityEngine.parseExpectedHash("sha-256-$testSha256"))
        assertEquals("SHA-256" to testSha256, PluginArchiveIntegrityEngine.parseExpectedHash("SHA256:${testSha256.uppercase()}"))

        val testMd5 = "d41d8cd98f00b204e9800998ecf8427e"
        assertEquals("MD5" to testMd5, PluginArchiveIntegrityEngine.parseExpectedHash("md5:$testMd5"))
        assertEquals("MD5" to testMd5, PluginArchiveIntegrityEngine.parseExpectedHash("md5-$testMd5"))
        assertEquals("MD5" to testMd5, PluginArchiveIntegrityEngine.parseExpectedHash(testMd5))

        assertEquals(null, PluginArchiveIntegrityEngine.parseExpectedHash(null))
        assertEquals(null, PluginArchiveIntegrityEngine.parseExpectedHash(""))
        assertEquals(null, PluginArchiveIntegrityEngine.parseExpectedHash("   "))
        assertEquals(null, PluginArchiveIntegrityEngine.parseExpectedHash("null"))
        assertEquals(null, PluginArchiveIntegrityEngine.parseExpectedHash("none"))
    }

    @Test
    fun `test validateArchive passes archive with relative path prefix for manifest`() {
        val zipFile = tempFolder.newFile("relative_manifest.cs3")
        createZip(
            zipFile,
            mapOf(
                "./manifest.json" to "{\"name\":\"TestRelative\",\"version\":1}",
                "classes.dex" to "fake dex bytes"
            )
        )

        val result = engine.validateArchive(zipFile)
        assertTrue(result is ArchiveValidationResult.Valid)
    }

    @Test
    fun `test validateArchive fails gracefully on non-existent or empty file`() {
        val nonExistent = File(tempFolder.root, "does_not_exist.cs3")
        val r1 = engine.validateArchive(nonExistent)
        assertTrue(r1 is ArchiveValidationResult.Corrupted)

        val emptyFile = tempFolder.newFile("empty.cs3")
        val r2 = engine.validateArchive(emptyFile)
        assertTrue(r2 is ArchiveValidationResult.Corrupted)
    }
}
