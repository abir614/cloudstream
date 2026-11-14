package com.lagradost.cloudstream3.syncproviders

import org.junit.Assert.*
import org.junit.Test

class SecureAuthStoreTest {

    @Test
    fun `test encrypt and decrypt round trip returns original plaintext`() {
        val original = "{\"accessToken\":\"test_oauth_token_12345\",\"refreshToken\":\"refresh_987\"}"
        val encrypted = SecureAuthStore.encrypt(original)
        assertNotNull(encrypted)
        assertNotEquals(original, encrypted)

        val decrypted = SecureAuthStore.decrypt(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun `test encrypt generates distinct nonces for identical plaintext`() {
        val text = "secret_api_token_mal"
        val enc1 = SecureAuthStore.encrypt(text)
        val enc2 = SecureAuthStore.encrypt(text)

        assertNotEquals("Each encryption should use a fresh random IV", enc1, enc2)
        assertEquals(text, SecureAuthStore.decrypt(enc1))
        assertEquals(text, SecureAuthStore.decrypt(enc2))
    }

    private fun testBase64Decode(str: String): ByteArray {
        return try {
            val res = android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
            if (res != null && res.isNotEmpty()) res else java.util.Base64.getDecoder().decode(str)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }

    private fun testBase64Encode(bytes: ByteArray): String {
        return try {
            val res = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            if (!res.isNullOrBlank()) res else java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    @Test
    fun `test decrypt rejects corrupted or tampered ciphertext`() {
        val original = "sample_sensitive_password"
        val encrypted = SecureAuthStore.encrypt(original)

        // Corrupt payload
        val tamperedBytes = testBase64Decode(encrypted)
        if (tamperedBytes.size > 15) {
            tamperedBytes[14] = (tamperedBytes[14].toInt() xor 0xFF).toByte()
        }
        val tamperedBase64 = testBase64Encode(tamperedBytes)

        val decrypted = SecureAuthStore.decrypt(tamperedBase64)
        assertNull("Tampered AES-GCM ciphertext must fail authentication and return null", decrypted)
    }

    @Test
    fun `test decrypt handles empty and malformed payloads safely`() {
        assertNull(SecureAuthStore.decrypt(""))
        assertNull(SecureAuthStore.decrypt("not_base_64!"))
        assertNull(SecureAuthStore.decrypt("AAAA"))
    }

    @Test
    fun `test encrypt and decrypt handles complex UTF-8 Unicode and large JSON payloads`() {
        val complex = "{\"user\":\"山田太郎 🚀\",\"bio\":\"Streaming & Subtitles / 日本語 / Español\",\"token\":\"eyJhbGciOi...\"}"
        val encrypted = SecureAuthStore.encrypt(complex)
        val decrypted = SecureAuthStore.decrypt(encrypted)
        assertEquals(complex, decrypted)
    }
}
