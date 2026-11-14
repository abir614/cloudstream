package com.lagradost.cloudstream3.plugins

import dalvik.system.PathClassLoader
import java.io.InputStream
import java.net.URL
import java.util.Enumeration

/**
 * Shielded ClassLoader for CloudStream extensions.
 *
 * Implements an in-process classloading and reflection firewall:
 * 1. Delegates plugin bytecode resolution to Dalvik [PathClassLoader].
 * 2. Permits full access to legitimate extension public contracts:
 *    [MainAPI], [ExtractorApi], [RepositoryManager], [RepositoryData],
 *    NiceHttp, Jsoup, Jackson, Kotlin/Java standard libraries, and Android framework.
 * 3. Meta-Plugin Compatibility: Explicitly permits [RepositoryManager] and `com.mega.*`
 *    so multi-repository meta-plugins (e.g. MegaRepo / 3737) execute seamlessly.
 * 4. Reflection Firewall: Strictly blocks access to sensitive host singletons, user authentication
 *    tokens (MAL, AniList, SIMKL), encrypted backup keys, update security gates, and database internals.
 */
class ShieldedClassLoader(
    val dexPath: String,
    parent: ClassLoader
) : ClassLoader(parent) {

    val dexLoader = PathClassLoader(dexPath, parent)

    companion object {
        /**
         * Core internal classes strictly forbidden from third-party extension access.
         */
        private val BLOCKED_CLASS_PREFIXES = listOf(
            "com.lagradost.cloudstream3.syncproviders.AccountManager",
            "com.lagradost.cloudstream3.syncproviders.AuthStore",
            "com.lagradost.cloudstream3.syncproviders.SecureAuthStore",
            "com.lagradost.cloudstream3.utils.UpdateSecurityGate",
            "com.lagradost.cloudstream3.utils.BackupUtils",
            "com.lagradost.cloudstream3.utils.SystemMemoryOptimizer",
            "com.lagradost.cloudstream3.network.UpdateNetworkClient",
            "com.lagradost.cloudstream3.network.DohProviders",
            "com.lagradost.cloudstream3.network.SafeDns",
            "com.lagradost.cloudstream3.ui.settings.GithubReleases",
            "com.lagradost.cloudstream3.services.NativeCoreBridge",
            "com.lagradost.cloudstream3.services.ExtensionSandboxService",
            "com.lagradost.cloudstream3.services.ExtensionSandboxClient",
            "com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter",
            "com.lagradost.cloudstream3.utils.telemetry.CrashHandler",
            "com.lagradost.cloudstream3.utils.persistence.SqliteDataRepository\$DatabaseHelper"
        )

        /**
         * Checks whether a class lookup targets a blocked internal app component.
         */
        fun isBlocked(className: String): Boolean {
            val normalized = className.replace('/', '.').trim()
            for (prefix in BLOCKED_CLASS_PREFIXES) {
                if (normalized == prefix || normalized.startsWith("$prefix$") || normalized.startsWith("$prefix.")) {
                    return true
                }
            }
            return false
        }
    }

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Enforce reflection and class-loading gate
        if (isBlocked(name)) {
            throw ClassNotFoundException("Access to protected app component '$name' blocked by ShieldedClassLoader")
        }

        // 1. Attempt to load from the plugin's own DEX first
        try {
            return dexLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {
            // Fall through to parent
        }

        // 2. Delegate to parent ClassLoader for framework and shared libraries
        return super.loadClass(name, resolve)
    }

    override fun getResource(name: String): URL? {
        return dexLoader.getResource(name) ?: super.getResource(name)
    }

    override fun getResourceAsStream(name: String): InputStream? {
        return dexLoader.getResourceAsStream(name) ?: super.getResourceAsStream(name)
    }

    override fun getResources(name: String): Enumeration<URL> {
        return dexLoader.getResources(name) ?: super.getResources(name)
    }
}
