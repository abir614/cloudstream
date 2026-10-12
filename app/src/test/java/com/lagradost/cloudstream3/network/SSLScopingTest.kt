package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.ui.player.SSLTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

class SSLScopingTest {

    @Test
    fun `test SSLTrustManager instantiation does not tamper with JVM global defaults`() {
        val initialSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val initialHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        // Instantiate and initialize local SSLTrustManager
        val trustManager = SSLTrustManager()
        val localContext = SSLContext.getInstance("TLS")
        localContext.init(null, arrayOf<TrustManager>(trustManager), null)

        val currentSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val currentHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        // Assert JVM global defaults are completely unchanged
        assertEquals(initialSocketFactory, currentSocketFactory)
        assertEquals(initialHostnameVerifier, currentHostnameVerifier)
    }

    @Test
    fun `test SSLTrustManager accepted issuers is empty and non-null`() {
        val trustManager = SSLTrustManager()
        val issuers = trustManager.acceptedIssuers
        assertNotNull(issuers)
        assertEquals(0, issuers.size)
    }
}
