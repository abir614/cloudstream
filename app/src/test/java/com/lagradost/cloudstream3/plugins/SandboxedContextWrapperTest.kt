package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SandboxedContextWrapperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeContext(
        private val files: File,
        private val cache: File
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getApplicationContext(): Context = this
        override fun getBaseContext(): Context = this
    }

    private lateinit var fakeBaseContext: Context
    private lateinit var baseFilesDir: File
    private lateinit var baseCacheDir: File
    private lateinit var sandboxedContext: SandboxedContextWrapper

    @Before
    fun setUp() {
        baseFilesDir = tempFolder.newFolder("files")
        baseCacheDir = tempFolder.newFolder("cache")
        fakeBaseContext = FakeContext(baseFilesDir, baseCacheDir)

        sandboxedContext = SandboxedContextWrapper(fakeBaseContext, "test_plugin")
    }

    @Test
    fun `test getApplicationContext and getBaseContext return sandboxed wrapper to prevent sandbox escape`() {
        assertSame(sandboxedContext, sandboxedContext.applicationContext)
        assertSame(sandboxedContext, sandboxedContext.baseContext)
    }

    @Test
    fun `test getFilesDir and getCacheDir are confined to plugin sandbox directory`() {
        val filesDir = sandboxedContext.filesDir
        val cacheDir = sandboxedContext.cacheDir

        assertTrue(filesDir.canonicalPath.contains("plugin_sandboxes/test_plugin/files"))
        assertTrue(cacheDir.canonicalPath.contains("plugin_sandboxes/test_plugin/cache"))
        assertTrue(filesDir.canonicalPath.startsWith(baseFilesDir.canonicalPath))
    }

    @Test
    fun `test getDir rejects path traversal attempts`() {
        val maliciousNames = listOf(
            "../escaped",
            "../../etc",
            "sub/../../secret",
            "sub\\..\\secret",
            "dir\u0000hidden"
        )

        for (name in maliciousNames) {
            try {
                sandboxedContext.getDir(name, Context.MODE_PRIVATE)
                fail("Expected SecurityException for malicious getDir name '$name'")
            } catch (_: SecurityException) {
                // Expected
            }
        }
    }

    @Test
    fun `test getDatabasePath rejects host database names and path traversal`() {
        val forbiddenNames = listOf(
            "cloudstream_kv.db",
            "database.db",
            "user_data.db",
            "my_cloudstream_data",
            "../cloudstream_kv.db",
            "../../databases/cloudstream_kv.db",
            "safe_name/../../secret.db",
            "evil\\..\\test.db"
        )

        for (name in forbiddenNames) {
            try {
                sandboxedContext.getDatabasePath(name)
                fail("Expected SecurityException for forbidden database name '$name'")
            } catch (_: SecurityException) {
                // Expected
            }
        }
    }

    @Test
    fun `test getDatabasePath allows valid plugin database inside sandbox`() {
        val dbFile = sandboxedContext.getDatabasePath("plugin_storage.db")
        assertNotNull(dbFile)
        assertTrue(dbFile.canonicalPath.contains("plugin_sandboxes/test_plugin/databases/plugin_storage.db"))
        assertTrue(dbFile.canonicalPath.startsWith(baseFilesDir.canonicalPath))
    }
}
