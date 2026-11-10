package com.lagradost.cloudstream3.plugins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldedClassLoaderTest {

    @Test
    fun `test ShieldedClassLoader blocks sensitive internal singletons`() {
        val blockedClasses = listOf(
            "com.lagradost.cloudstream3.syncproviders.AccountManager",
            "com.lagradost.cloudstream3.syncproviders.AccountManager\$Companion",
            "com.lagradost.cloudstream3.syncproviders.AuthStore",
            "com.lagradost.cloudstream3.utils.UpdateSecurityGate",
            "com.lagradost.cloudstream3.utils.UpdateSecurityGate\$Companion",
            "com.lagradost.cloudstream3.utils.BackupUtils",
            "com.lagradost.cloudstream3.utils.telemetry.RemoteCrashReporter",
            "com.lagradost.cloudstream3.utils.telemetry.CrashHandler",
            "com.lagradost.cloudstream3.utils.persistence.SqliteDataRepository\$DatabaseHelper",
            "com.lagradost.cloudstream3.CloudStreamApp",
            "com.lagradost.cloudstream3.CloudStreamApp\$Companion",
            "com.lagradost.cloudstream3.utils.DataStore",
            "com.lagradost.cloudstream3.utils.DataStoreHelper"
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
            "com.lagradost.cloudstream3.plugins.RepositoryManager",
            "com.lagradost.cloudstream3.plugins.RepositoryManager\$Companion",
            "com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData",
            "com.lagradost.cloudstream3.APIHolder",
            "com.lagradost.cloudstream3.TvType",
            "com.lagradost.cloudstream3.SearchResponse",
            "com.lagradost.cloudstream3.LoadResponse",
            "com.lagradost.cloudstream3.Episode",
            "com.lagradost.cloudstream3.SubtitleFile",
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
}
