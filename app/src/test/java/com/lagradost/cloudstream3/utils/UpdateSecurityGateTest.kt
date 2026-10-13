package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class UpdateSecurityGateTest {

    @Before
    fun setup() {
        UpdateSecurityGate.lockGate()
    }

    @Test
    fun `test initial state is locked`() {
        assertEquals(UpdateSecurityGate.GateStatus.LOCKED, UpdateSecurityGate.getStatus())
    }

    @Test
    fun `test requestUpdateChallenge transitions to challenge issued`() {
        val nonce = UpdateSecurityGate.requestUpdateChallenge()
        assertNotNull(nonce)
        assertTrue(nonce.isNotBlank())
        assertEquals(UpdateSecurityGate.GateStatus.CHALLENGE_ISSUED, UpdateSecurityGate.getStatus())
    }

    @Test
    fun `test lockGate resets gate to locked`() {
        UpdateSecurityGate.requestUpdateChallenge()
        UpdateSecurityGate.lockGate()
        assertEquals(UpdateSecurityGate.GateStatus.LOCKED, UpdateSecurityGate.getStatus())
    }

    @Test
    fun `test authorization token expiry logic`() {
        val freshToken = UpdateSecurityGate.GateAuthorizationToken(
            rawToken = "mock_token_fresh",
            fileHash = "mock_hash",
            packageName = "com.lagradost.cloudstream3",
            certFingerprint = "mock_cert",
            versionCode = 1001L,
            authorizedFile = File("/mock/path.apk"),
            issuedAt = System.currentTimeMillis() - 60_000L // 1 minute ago (valid for 15 mins)
        )
        org.junit.Assert.assertFalse(freshToken.isExpired())

        val expiredToken = UpdateSecurityGate.GateAuthorizationToken(
            rawToken = "mock_token_expired",
            fileHash = "mock_hash",
            packageName = "com.lagradost.cloudstream3",
            certFingerprint = "mock_cert",
            versionCode = 1001L,
            authorizedFile = File("/mock/path.apk"),
            issuedAt = System.currentTimeMillis() - 1_000_000L // ~16.6 mins ago (> 15 mins limit)
        )
        assertTrue(expiredToken.isExpired())
    }
}
