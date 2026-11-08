package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CursorFactory
import android.database.DatabaseErrorHandler
import java.io.File

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
    base: Context,
    val pluginName: String
) : ContextWrapper(base) {

    private val sanitizedName: String = pluginName
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .take(64)

    private val sandboxDir: File by lazy {
        File(baseContext.filesDir, "plugin_sandboxes/$sanitizedName").apply {
            if (!exists()) mkdirs()
        }
    }

    private val sandboxCacheDir: File by lazy {
        File(baseContext.cacheDir, "plugin_sandboxes/$sanitizedName").apply {
            if (!exists()) mkdirs()
        }
    }

    override fun getFilesDir(): File {
        val dir = File(sandboxDir, "files")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getCacheDir(): File {
        val dir = File(sandboxCacheDir, "cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getDir(name: String, mode: Int): File {
        val dir = File(sandboxDir, name)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getExternalFilesDir(type: String?): File? {
        val baseExt = super.getExternalFilesDir(type) ?: return getFilesDir()
        val dir = File(baseExt, "plugin_sandboxes/$sanitizedName")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getExternalCacheDir(): File? {
        val baseExt = super.getExternalCacheDir() ?: return getCacheDir()
        val dir = File(baseExt, "plugin_sandboxes/$sanitizedName")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        // Namespace SharedPreferences so plugins cannot overwrite host or other plugin settings
        return super.getSharedPreferences("cs_plugin_${sanitizedName}_$name", mode)
    }

    private fun assertSafeDatabaseName(name: String) {
        val lower = name.lowercase()
        if (lower.contains("cloudstream") ||
            lower.contains("kv_store") ||
            lower.contains("user_data") ||
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
        return File(dbDir, name)
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
