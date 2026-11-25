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
) : PathClassLoader(dexPath, parent) {

    /**
     * Compatibility reference to this ClassLoader so loaded classes always retain
     * ShieldedClassLoader as their defining loader.
     */
    val dexLoader: PathClassLoader get() = this

    companion object {
        /**
         * Core internal classes strictly forbidden from third-party extension access.
         */
        private val BLOCKED_CLASS_PREFIXES = listOf(
            "com.lagradost.cloudstream3.syncproviders.AccountManager",
            "com.lagradost.cloudstream3.syncproviders.AuthStore",
            "com.lagradost.cloudstream3.syncproviders.AuthRepo",
            "com.lagradost.cloudstream3.syncproviders.SecureAuthStore",
            "com.lagradost.cloudstream3.utils.UpdateSecurityGate",
            "com.lagradost.cloudstream3.utils.InAppUpdater",
            "com.lagradost.cloudstream3.utils.ApkUpdater",
            "com.lagradost.cloudstream3.utils.BackupUtils",
            "com.lagradost.cloudstream3.utils.BiometricAuthenticator",
            "com.lagradost.cloudstream3.utils.SystemMemoryOptimizer",
            "com.lagradost.cloudstream3.network.UpdateNetworkClient",
            "com.lagradost.cloudstream3.network.DohProviders",
            "com.lagradost.cloudstream3.network.SafeDns",
            "com.lagradost.cloudstream3.ui.settings.GithubReleases",
            "com.lagradost.cloudstream3.di.ServiceLocator",
            "com.lagradost.cloudstream3.services.NativeCoreBridge",
            "com.lagradost.cloudstream3.services.ExtensionSandboxService",
            "com.lagradost.cloudstream3.services.ExtensionSandboxClient",
            "com.lagradost.cloudstream3.utils.telemetry.",
            "com.lagradost.cloudstream3.utils.persistence.",
            "com.lagradost.cloudstream3.domain.engine.PluginArchiveIntegrityEngine",
            "com.lagradost.cloudstream3.plugins.PluginFingerprintStore",
            "com.lagradost.cloudstream3.plugins.PluginManager"
        )

        /**
         * Checks whether a class lookup targets a blocked internal app component.
         */
        fun isBlocked(className: String): Boolean {
            val normalized = className.replace('/', '.').trim()
            for (prefix in BLOCKED_CLASS_PREFIXES) {
                if (prefix.endsWith(".")) {
                    if (normalized.startsWith(prefix)) return true
                } else if (normalized == prefix || normalized.startsWith("$prefix$") || normalized.startsWith("$prefix.")) {
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

        // 1. Check already loaded classes or attempt plugin DEX before delegating to parent
        findLoadedClass(name)?.let { return it }
        try {
            return findClass(name)
        } catch (_: ClassNotFoundException) {
            // Fall through to parent
        }

        // 2. Delegate to parent ClassLoader for framework and shared libraries
        return super.loadClass(name, resolve)
    }

    @Throws(ClassNotFoundException::class)
    override fun findClass(name: String): Class<*> {
        if (isBlocked(name)) {
            throw ClassNotFoundException("Access to protected app component '$name' blocked by ShieldedClassLoader")
        }
        return super.findClass(name)
    }

    override fun getResource(name: String): URL? {
        return findResource(name) ?: super.getResource(name)
    }

    override fun getResourceAsStream(name: String): InputStream? {
        val url = findResource(name)
        if (url != null) {
            return try {
                url.openStream()
            } catch (_: Throwable) {
                null
            }
        }
        return super.getResourceAsStream(name)
    }

    override fun getResources(name: String): Enumeration<URL> {
        val local = findResources(name)
        val parent = super.getResources(name)
        val merged = ArrayList<URL>()
        while (local.hasMoreElements()) {
            merged.add(local.nextElement())
        }
        while (parent.hasMoreElements()) {
            val url = parent.nextElement()
            if (!merged.contains(url)) merged.add(url)
        }
        return java.util.Collections.enumeration(merged)
    }
}
