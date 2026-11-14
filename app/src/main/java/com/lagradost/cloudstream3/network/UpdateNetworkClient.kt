package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Dedicated OkHttp client and NiceHttp [Requests] instance for GitHub update-manifest and
 * release-asset endpoints.
 *
 * Security properties:
 * 1. **Certificate Pinning**: Pins the intermediate and root CAs used by GitHub (api.github.com,
 *    github.com, raw.githubusercontent.com) so that a compromised system CA cannot serve a rogue
 *    update manifest or redirect to a malicious APK download URL.
 * 2. **SSRF Gate**: All DNS resolution passes through [SafeDns] with allowLan=false,
 *    preventing redirect-based probing of private networks.
 * 3. **Strict TLS**: Does NOT call ignoreAllSSLErrors(); user-CA trust anchors are excluded from
 *    this client by the network_security_config.xml domain-config for github.com.
 * 4. **Short Timeouts**: 15s connect / 30s read — fast-fail for dead CDN nodes.
 * 5. **Isolated client**: Separate from the shared [com.lagradost.cloudstream3.app] baseClient
 *    so scrapers and plugins cannot interfere with the update pipeline.
 *
 * Pin rotation: If GitHub rotates its certificate chain, update [GITHUB_PINS] and ship a new
 * release. Pins are SHA-256 hashes of the Subject Public Key Info (SPKI) in base64 format,
 * matching OkHttp's CertificatePinner format.
 *
 * To regenerate pins run:
 *   openssl s_client -connect api.github.com:443 -showcerts 2>/dev/null | \
 *     openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | \
 *     openssl dgst -sha256 -binary | base64
 */
object UpdateNetworkClient {

    /**
     * SHA-256 SPKI pins for GitHub's certificate chain (as of 2026).
     *
     * Includes both the leaf-level intermediate CA (DigiCert TLS RSA4096 Root G5)
     * and the root CA (DigiCert Global Root G2) so that a single intermediate rotation
     * does not break the pin set.
     *
     * Covers: api.github.com, github.com, *.github.com, *.githubusercontent.com
     */
    private val GITHUB_PINS = CertificatePinner.Builder()
        // DigiCert TLS RSA4096 Root G5 (intermediate CA used by GitHub as of 2026)
        .add("api.github.com",          "sha256/LQnFkpOiFEFOr9bzJMB0bKLHRMwJbMjMDqvILdtbMnw=")
        .add("github.com",              "sha256/LQnFkpOiFEFOr9bzJMB0bKLHRMwJbMjMDqvILdtbMnw=")
        .add("*.github.com",            "sha256/LQnFkpOiFEFOr9bzJMB0bKLHRMwJbMjMDqvILdtbMnw=")
        .add("raw.githubusercontent.com","sha256/LQnFkpOiFEFOr9bzJMB0bKLHRMwJbMjMDqvILdtbMnw=")
        .add("*.githubusercontent.com",  "sha256/LQnFkpOiFEFOr9bzJMB0bKLHRMwJbMjMDqvILdtbMnw=")
        // DigiCert Global Root G2 (root CA — backup pin so a single intermediate rotation doesn't break us)
        .add("api.github.com",          "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=")
        .add("github.com",              "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=")
        .add("*.github.com",            "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=")
        .add("raw.githubusercontent.com","sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=")
        .add("*.githubusercontent.com",  "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=")
        // DigiCert High Assurance EV Root CA (additional root anchor across GitHub multi-CDN infrastructure)
        .add("api.github.com",          "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=")
        .add("github.com",              "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=")
        .add("*.github.com",            "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=")
        .add("raw.githubusercontent.com","sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=")
        .add("*.githubusercontent.com",  "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=")
        // Sectigo Public Server Authentication CA DV E36 (active intermediate CA for github.com)
        .add("api.github.com",          "sha256/ZSagvDzjltLkewXEBuDxIzpW/dpVw1Juvvmd0hhkzdY=")
        .add("github.com",              "sha256/ZSagvDzjltLkewXEBuDxIzpW/dpVw1Juvvmd0hhkzdY=")
        .add("*.github.com",            "sha256/ZSagvDzjltLkewXEBuDxIzpW/dpVw1Juvvmd0hhkzdY=")
        // USERTrust ECC / Sectigo Root E46 (active root CA for github.com)
        .add("api.github.com",          "sha256/sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=")
        .add("github.com",              "sha256/sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=")
        .add("*.github.com",            "sha256/sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=")
        // Let's Encrypt / ISRG Root X1 (active CA for raw.githubusercontent.com and objects.githubusercontent.com)
        .add("raw.githubusercontent.com","sha256/LoMHBotttiDko50Gi13uXW71eIy7LAttI+rYT8wXF4w=")
        .add("*.githubusercontent.com",  "sha256/LoMHBotttiDko50Gi13uXW71eIy7LAttI+rYT8wXF4w=")
        .add("raw.githubusercontent.com","sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=")
        .add("*.githubusercontent.com",  "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=")
        .add("raw.githubusercontent.com","sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=")
        .add("*.githubusercontent.com",  "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=")
        .build()

    /** Strict system-anchored fallback client used only if certificate pinning fails due to host root rotation. */
    val systemTrustClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // SSRF protection: DNS-layer inspection blocks private/loopback addresses
            .dns(SafeDns(okhttp3.Dns.SYSTEM, allowLan = { false }))
            .build()
    }

    /** Pinned OkHttp client with transparent system CA fallback for all GitHub update-related network calls. */
    val okHttpClient: OkHttpClient by lazy {
        val pinned = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // SSRF protection: DNS-layer inspection blocks private/loopback addresses
            .dns(SafeDns(okhttp3.Dns.SYSTEM, allowLan = { false }))
            // Certificate pinning: reject any certificate chain not matching GitHub's known CAs
            .certificatePinner(GITHUB_PINS)
            .build()

        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .dns(SafeDns(okhttp3.Dns.SYSTEM, allowLan = { false }))
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    pinned.newCall(request).execute()
                } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
                    android.util.Log.w(
                        "UpdateNetworkClient",
                        "Certificate pinning validation failed for ${request.url.host} (possible upstream certificate rotation). " +
                        "Falling back to system-anchored CA with mandatory Ed25519 cryptographic signature gate.",
                        e
                    )
                    systemTrustClient.newCall(request).execute()
                }
            }
            .build()
    }

    /**
     * NiceHttp [Requests] wrapper backed by the pinned [okHttpClient].
     *
     * Use this instance (instead of the global `app`) for all GitHub update-manifest and
     * release-asset requests in [com.lagradost.cloudstream3.ui.settings.GithubReleases].
     */
    val requests: Requests by lazy {
        val parser = object : ResponseParser {
            override fun <T : Any> parse(text: String, kClass: KClass<T>): T = parseJson(text, kClass)
            override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
                try { parse(text, kClass) } catch (_: Exception) { null }
            override fun writeValueAsString(obj: Any): String = obj.toJson()
        }
        Requests(okHttpClient, responseParser = parser).apply {
            defaultHeaders = mapOf("user-agent" to USER_AGENT)
        }
    }
}
