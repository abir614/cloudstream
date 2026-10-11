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
        val token = UpdateSecurityGate.GateAuthorizationToken(
            rawToken = "mock_token",
            fileHash = "mock_hash",
            packageName = "com.lagradost.cloudstream3",
            certFingerprint = "mock_cert",
            versionCode = 1001L,
            authorizedFile = File("/mock/path.apk"),
            issuedAt = System.currentTimeMillis() - 120_000L // 2 mins ago (limit is 60s)
        )
        assertTrue(token.isExpired())
    }
}
