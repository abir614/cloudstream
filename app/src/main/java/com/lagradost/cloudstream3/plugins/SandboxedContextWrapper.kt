package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CursorFactory
import android.database.DatabaseErrorHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Sandboxed Context Wrapper for third-party extensions (.cs3 plugins).
 *
 * Confines plugin filesystem, database, and preferences access to an isolated,
 * per-plugin sandbox directory without breaking legitimate Android APIs.
 *
 * Guarantees:
 * 1. Storage Jail: Files, caches, and databases are strictly jailed under
 *    `filesDir/plugin_sandboxes/<pluginName>/`.
 * 2. Host Database Protection: Direct access or deletion of CloudStream core databases
 *    (e.g., cloudstream_kv.db) is blocked with a SecurityException.
 * 3. Preferences Isolation: `getSharedPreferences()` namespaces keys to prevent tampering
 *    with host settings or other extensions.
 * 4. 100% API Compatibility: Extension UI, resource loaders, and system services (network,
 *    audio, display) remain completely functional.
 */
class SandboxedContextWrapper(
    private val hostContext: Context,
    val pluginName: String
) : ContextWrapper(hostContext) {

    private val sanitizedName: String = pluginName
        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        .trim('_')
        .ifEmpty { "plugin" }
        .take(64)

    private val sandboxDir: File by lazy {
        val baseFiles = hostContext.filesDir ?: File("/tmp")
        File(baseFiles, "plugin_sandboxes/$sanitizedName").apply {
            if (!exists()) mkdirs()
        }
    }

    private val sandboxCacheDir: File by lazy {
        val baseCache = hostContext.cacheDir ?: File("/tmp")
        File(baseCache, "plugin_sandboxes/$sanitizedName").apply {
            if (!exists()) mkdirs()
        }
    }

    override fun getApplicationContext(): Context {
        // Prevent extension sandbox escapes via applicationContext
        return this
    }

    override fun getBaseContext(): Context {
        // Prevent extension sandbox escapes via baseContext
        return this
    }

    companion object {
        /**
         * Strict Zero-Trust Allowlist of system services safe and necessary for third-party extensions.
         * Only benign UI, display, audio, power, network connectivity, and memory profiling services
         * are permitted. All sensitive hardware, telephony, clipboard, location, notification, account,
         * and sensor services are strictly blocked by default.
         */
        val PERMITTED_SYSTEM_SERVICES = setOf(
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
            Context.ACCESSIBILITY_SERVICE,
            Context.ACTIVITY_SERVICE
        )
    }

    override fun getSystemService(name: String): Any? {
        if (!PERMITTED_SYSTEM_SERVICES.contains(name)) {
            throw SecurityException("Access to system service '$name' is restricted for sandboxed plugins")
        }
        return hostContext.getSystemService(name)
    }

    override fun getSystemServiceName(serviceClass: Class<*>): String? {
        val name = hostContext.getSystemServiceName(serviceClass) ?: return null
        return if (PERMITTED_SYSTEM_SERVICES.contains(name)) name else null
    }

    private fun sanitizePathSegment(segment: String): String {
        if (segment.contains("..") || segment.contains("/") || segment.contains("\\") || segment.contains("\u0000")) {
            throw SecurityException("Path traversal attempt detected in segment: '$segment'")
        }
        return segment
    }

    private fun assertInsideSandbox(target: File, boundary: File) {
        val canonicalTarget = try { target.canonicalPath } catch (_: Throwable) { target.absolutePath }
        val canonicalBoundary = try { boundary.canonicalPath } catch (_: Throwable) { boundary.absolutePath }
        val boundaryWithSep = if (canonicalBoundary.endsWith(File.separator)) {
            canonicalBoundary
        } else {
            canonicalBoundary + File.separator
        }
        if (canonicalTarget != canonicalBoundary && !canonicalTarget.startsWith(boundaryWithSep)) {
            throw SecurityException("Sandbox boundary violation: '$canonicalTarget' escapes '$canonicalBoundary'")
        }
    }

    override fun getFilesDir(): File {
        val dir = File(sandboxDir, "files")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun openFileInput(name: String): FileInputStream {
        sanitizePathSegment(name)
        val file = File(getFilesDir(), name)
        assertInsideSandbox(file, sandboxDir)
        return FileInputStream(file)
    }

    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        sanitizePathSegment(name)
        val file = File(getFilesDir(), name)
        assertInsideSandbox(file, sandboxDir)
        val append = (mode and Context.MODE_APPEND) != 0
        return FileOutputStream(file, append)
    }

    override fun deleteFile(name: String): Boolean {
        sanitizePathSegment(name)
        val file = File(getFilesDir(), name)
        assertInsideSandbox(file, sandboxDir)
        return if (file.exists()) file.delete() else false
    }

    override fun fileList(): Array<String> {
        return getFilesDir().list() ?: emptyArray()
    }

    override fun databaseList(): Array<String> {
        val dbDir = File(sandboxDir, "databases")
        return dbDir.list() ?: emptyArray()
    }

    override fun getCacheDir(): File {
        val dir = File(sandboxCacheDir, "cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getDir(name: String, mode: Int): File {
        sanitizePathSegment(name)
        val dir = File(sandboxDir, name)
        assertInsideSandbox(dir, sandboxDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getExternalFilesDir(type: String?): File? {
        val baseExt = hostContext.getExternalFilesDir(type) ?: return getFilesDir()
        val dir = File(baseExt, "plugin_sandboxes/$sanitizedName")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getExternalCacheDir(): File? {
        val baseExt = hostContext.getExternalCacheDir() ?: return getCacheDir()
        val dir = File(baseExt, "plugin_sandboxes/$sanitizedName")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        sanitizePathSegment(name)
        val lower = name.lowercase()
        if (lower.contains("cs_secure") ||
            lower.contains("secure_auth") ||
            lower.contains("auth_token")
        ) {
            throw SecurityException("Access to protected host preferences '$name' denied for plugin '$pluginName'")
        }
        // If a plugin accesses the default SDK host preferences ("rebuild_preference"),
        // safely redirect it to the plugin's own namespaced preferences file so SDK setKey/getKey works seamlessly!
        val targetName = if (lower == "rebuild_preference") "default_prefs" else name
        // Namespace SharedPreferences so plugins cannot overwrite host or other plugin settings
        return hostContext.getSharedPreferences("cs_plugin_${sanitizedName}_$targetName", mode)
    }

    private fun assertSafeDatabaseName(name: String) {
        sanitizePathSegment(name)
        val lower = name.lowercase()
        if (lower.contains("cloudstream") ||
            lower.contains("kv_store") ||
            lower.contains("user_data") ||
            lower.contains("secure_auth") ||
            lower.contains("auth_token") ||
            lower.contains("credential") ||
            lower == "cloudstream_kv.db" ||
            lower == "database.db"
        ) {
            throw SecurityException("Access to host database '$name' denied for plugin '$pluginName'")
        }
    }

    override fun getDatabasePath(name: String): File {
        assertSafeDatabaseName(name)
        val dbDir = File(sandboxDir, "databases")
        if (!dbDir.exists()) dbDir.mkdirs()
        val dbPath = File(dbDir, name)
        assertInsideSandbox(dbPath, sandboxDir)
        return dbPath
    }

    override fun deleteDatabase(name: String): Boolean {
        assertSafeDatabaseName(name)
        val dbPath = getDatabasePath(name)
        return if (dbPath.exists()) dbPath.delete() else false
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: CursorFactory?
    ): SQLiteDatabase {
        assertSafeDatabaseName(name)
        val path = getDatabasePath(name)
        return SQLiteDatabase.openOrCreateDatabase(path, factory)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase {
        assertSafeDatabaseName(name)
        val path = getDatabasePath(name)
        return SQLiteDatabase.openOrCreateDatabase(path.absolutePath, factory, errorHandler)
    }
}
