package com.lagradost.cloudstream3.plugins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldedClassLoaderTest {

    @Test
    fun `test ShieldedClassLoader blocks sensitive internal singletons`() {
        val blockedClasses = listOf(
            "com.lagradost.cloudstream3.syncproviders.AuthStore",
            "com.lagradost.cloudstream3.syncproviders.SecureAuthStore",
            "com.lagradost.cloudstream3.utils.UpdateSecurityGate",
            "com.lagradost.cloudstream3.utils.UpdateSecurityGate\$Companion",
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

        for (className in blockedClasses) {
            assertTrue("Expected $className to be blocked", ShieldedClassLoader.isBlocked(className))
        }
    }

    @Test
    fun `test ShieldedClassLoader allows legitimate extension APIs and meta-plugins`() {
        val allowedClasses = listOf(
            "com.lagradost.cloudstream3.MainAPI",
            "com.lagradost.cloudstream3.utils.ExtractorApi",
            "com.lagradost.cloudstream3.plugins.Plugin",
            "com.lagradost.cloudstream3.plugins.BasePlugin",
            "com.lagradost.cloudstream3.syncproviders.AccountManager",
            "com.lagradost.cloudstream3.syncproviders.AccountManager\$Companion",
            "com.lagradost.cloudstream3.plugins.RepositoryManager",
            "com.lagradost.cloudstream3.plugins.RepositoryManager\$Companion",
            "com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData",
            "com.lagradost.cloudstream3.APIHolder",
            "com.lagradost.cloudstream3.TvType",
            "com.lagradost.cloudstream3.SearchResponse",
            "com.lagradost.cloudstream3.LoadResponse",
            "com.lagradost.cloudstream3.Episode",
            "com.lagradost.cloudstream3.SubtitleFile",
            "com.lagradost.cloudstream3.CloudStreamApp",
            "com.lagradost.cloudstream3.utils.DataStore",
            "com.lagradost.cloudstream3.utils.DataStoreHelper",
            "kotlin.coroutines.intrinsics.IntrinsicsKt",
            "kotlin.jvm.internal.Intrinsics",
            "com.mega.MegaPlugin",
            "com.mega.MegaProvider",
            "org.jsoup.Jsoup",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "okhttp3.OkHttpClient"
        )

        for (className in allowedClasses) {
            assertFalse("Expected $className to be allowed", ShieldedClassLoader.isBlocked(className))
        }
    }

    @Test
    fun `test ShieldedClassLoader blocks internal classes even with slash formatting`() {
        val slashNames = listOf(
            "com/lagradost/cloudstream3/syncproviders/SecureAuthStore",
            "com/lagradost/cloudstream3/utils/UpdateSecurityGate",
            "com/lagradost/cloudstream3/network/UpdateNetworkClient",
            "com/lagradost/cloudstream3/services/NativeCoreBridge"
        )
        for (name in slashNames) {
            assertTrue("Expected slash notation $name to be blocked", ShieldedClassLoader.isBlocked(name))
        }
    }

    @Test
    fun `test RepositoryManager isSafeRepositoryUrl enforces security boundary against SSRF and local probing`() {
        // Block dangerous schemes
        assertFalse(RepositoryManager.isSafeRepositoryUrl("file:///etc/passwd", allowLocalInDebug = false))
        assertFalse(RepositoryManager.isSafeRepositoryUrl("javascript:alert(1)", allowLocalInDebug = false))
        assertFalse(RepositoryManager.isSafeRepositoryUrl("content://media/external/images", allowLocalInDebug = false))
        assertFalse(RepositoryManager.isSafeRepositoryUrl("data:text/html;base64,PHNjcmlwdD4=", allowLocalInDebug = false))

        // Block loopback and local probing
        assertFalse(RepositoryManager.isSafeRepositoryUrl("http://localhost:8080/repo.json", allowLocalInDebug = false))
        assertFalse(RepositoryManager.isSafeRepositoryUrl("http://127.0.0.1:8080/repo.json", allowLocalInDebug = false))
        assertFalse(RepositoryManager.isSafeRepositoryUrl("http://192.168.1.1/repo.json", allowLocalInDebug = false))
        assertFalse(RepositoryManager.isSafeRepositoryUrl("http://10.0.0.1/repo.json", allowLocalInDebug = false))
        assertFalse(RepositoryManager.isSafeRepositoryUrl("http://169.254.169.254/latest/meta-data/", allowLocalInDebug = false))

        // Allow legitimate public HTTPS repositories
        assertTrue(RepositoryManager.isSafeRepositoryUrl("https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json", allowLocalInDebug = false))
        assertTrue(RepositoryManager.isSafeRepositoryUrl("https://cdn.jsdelivr.net/gh/user/repo@main/repo.json", allowLocalInDebug = false))
    }
}
