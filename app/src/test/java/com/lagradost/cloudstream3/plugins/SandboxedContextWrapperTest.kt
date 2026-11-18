package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val requestedPrefNames = mutableListOf<String>()

        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getApplicationContext(): Context = this
        override fun getBaseContext(): Context = this
        override fun getSystemService(name: String): Any? = "mock_service_$name"
        override fun getSystemServiceName(serviceClass: Class<*>): String? {
            return when (serviceClass.simpleName) {
                "ActivityManager" -> Context.ACTIVITY_SERVICE
                "ConnectivityManager" -> Context.CONNECTIVITY_SERVICE
                "ClipboardManager" -> Context.CLIPBOARD_SERVICE
                "LocationManager" -> Context.LOCATION_SERVICE
                else -> null
            }
        }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            requestedPrefNames.add(name)
            return FakeSharedPreferences(name)
        }

        private class FakeSharedPreferences(val name: String) : SharedPreferences {
            private val data = mutableMapOf<String, Any?>()
            override fun getAll(): Map<String, *> = data
            override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = (data[key] as? Set<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = data.containsKey(key)
            override fun edit(): SharedPreferences.Editor = FakeEditor(data)
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

            private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
                private val temp = mutableMapOf<String, Any?>()
                override fun putString(key: String?, value: String?) = apply { key?.let { temp[it] = value } }
                override fun putStringSet(key: String?, values: Set<String>?) = apply { key?.let { temp[it] = values } }
                override fun putInt(key: String?, value: Int) = apply { key?.let { temp[it] = value } }
                override fun putLong(key: String?, value: Long) = apply { key?.let { temp[it] = value } }
                override fun putFloat(key: String?, value: Float) = apply { key?.let { temp[it] = value } }
                override fun putBoolean(key: String?, value: Boolean) = apply { key?.let { temp[it] = value } }
                override fun remove(key: String?) = apply { key?.let { temp.remove(it); data.remove(it) } }
                override fun clear() = apply { temp.clear(); data.clear() }
                override fun commit(): Boolean { data.putAll(temp); return true }
                override fun apply() { data.putAll(temp) }
            }
        }
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

    @Test
    fun `test getSystemService restricts privileged and sensitive services`() {
        val restrictedServices = listOf(
            Context.CLIPBOARD_SERVICE,
            Context.TELEPHONY_SERVICE,
            Context.LOCATION_SERVICE,
            Context.WIFI_SERVICE,
            Context.ACCOUNT_SERVICE,
            Context.DEVICE_POLICY_SERVICE,
            Context.KEYGUARD_SERVICE,
            Context.DROPBOX_SERVICE,
            Context.NOTIFICATION_SERVICE,
            "camera",
            "bluetooth",
            "media_projection",
            "unknown_rogue_service"
        )
        for (srv in restrictedServices) {
            try {
                sandboxedContext.getSystemService(srv)
                fail("Expected SecurityException when requesting restricted service '$srv'")
            } catch (_: SecurityException) {
                // Expected
            }
        }
    }

    @Test
    fun `test getSystemService permits safe services for UI and memory profiling`() {
        val safeServices = listOf(
            Context.ACTIVITY_SERVICE,
            Context.CONNECTIVITY_SERVICE,
            Context.LAYOUT_INFLATER_SERVICE,
            Context.WINDOW_SERVICE,
            Context.POWER_SERVICE,
            Context.AUDIO_SERVICE,
            Context.INPUT_METHOD_SERVICE,
            Context.UI_MODE_SERVICE,
            Context.VIBRATOR_SERVICE,
            Context.CAPTIONING_SERVICE,
            Context.DISPLAY_SERVICE,
            Context.ACCESSIBILITY_SERVICE
        )
        for (srv in safeServices) {
            val result = sandboxedContext.getSystemService(srv)
            assertNotNull("Safe service '$srv' should return non-null from mock context", result)
        }
    }

    @Test
    fun `test getSystemServiceName filters restricted service classes`() {
        assertEquals(Context.ACTIVITY_SERVICE, sandboxedContext.getSystemServiceName(android.app.ActivityManager::class.java))
        assertEquals(Context.CONNECTIVITY_SERVICE, sandboxedContext.getSystemServiceName(android.net.ConnectivityManager::class.java))
        assertEquals(null, sandboxedContext.getSystemServiceName(android.content.ClipboardManager::class.java))
        assertEquals(null, sandboxedContext.getSystemServiceName(android.location.LocationManager::class.java))
    }

    @Test
    fun `test file I-O operations operate strictly inside sandbox and block path traversal`() {
        val testFileName = "test_plugin_data.txt"
        sandboxedContext.openFileOutput(testFileName, Context.MODE_PRIVATE).use {
            it.write("secure plugin data".toByteArray())
        }

        val fileList = sandboxedContext.fileList()
        assertTrue("fileList should contain created file", fileList.contains(testFileName))

        sandboxedContext.openFileInput(testFileName).use {
            val content = it.bufferedReader().readText()
            assertEquals("secure plugin data", content)
        }

        assertTrue("deleteFile should succeed for owned file", sandboxedContext.deleteFile(testFileName))
        assertFalse("fileList should no longer contain deleted file", sandboxedContext.fileList().contains(testFileName))

        // Path traversal attempts
        val evilFileNames = listOf("../escape.txt", "../../evil.txt", "sub/../../secret.txt")
        for (evil in evilFileNames) {
            try {
                sandboxedContext.openFileOutput(evil, Context.MODE_PRIVATE)
                fail("Expected SecurityException for path traversal '$evil'")
            } catch (_: SecurityException) {
                // Expected
            }
        }
    }

    @Test
    fun `test getSharedPreferences rejects host preferences and path traversal`() {
        val forbidden = listOf(
            "cs_secure_auth",
            "secure_auth_store",
            "../rebuild_preference",
            "sub/../../secret",
            "evil\\preference"
        )
        for (pref in forbidden) {
            try {
                sandboxedContext.getSharedPreferences(pref, Context.MODE_PRIVATE)
                fail("Expected SecurityException for forbidden preference '$pref'")
            } catch (_: SecurityException) {
                // Expected
            }
        }
    }

    @Test
    fun `test getSharedPreferences safely isolates default rebuild_preference`() {
        val prefs = sandboxedContext.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
        assertNotNull(prefs)
        val fakeHost = fakeBaseContext as FakeContext
        assertTrue(fakeHost.requestedPrefNames.any { it.contains("default_prefs") })
        assertFalse(fakeHost.requestedPrefNames.contains("rebuild_preference"))
    }
}
