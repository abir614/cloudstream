package com.lagradost.cloudstream3.ui.player

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Permissive X509TrustManager for legacy/untrusted streaming endpoints.
 *
 * CAUTION: MUST strictly be bound to isolated player OkHttpClient instances.
 * NEVER set as JVM-wide HttpsURLConnection default to prevent process-wide MitM risks.
 */
class SSLTrustManager : X509TrustManager {
    override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {
    }

    override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf()
    }
}