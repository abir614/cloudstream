package com.lagradost.cloudstream3.domain

import com.lagradost.cloudstream3.domain.usecase.AppUpdateUseCase
import com.lagradost.cloudstream3.utils.UpdateSecurityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdateUseCaseTest {

    private val useCase = AppUpdateUseCase()

    @Before
    fun setup() {
        useCase.abortUpdate()
    }

    @Test
    fun `test beginUpdateSession transitions gate and returns valid nonce`() {
        val nonce = useCase.beginUpdateSession()
        assertNotNull(nonce)
        assertTrue(nonce.isNotBlank())
        assertEquals(UpdateSecurityGate.GateStatus.CHALLENGE_ISSUED, UpdateSecurityGate.getStatus())
    }

    @Test
    fun `test abortUpdate securely locks the gate`() {
        useCase.beginUpdateSession()
        assertEquals(UpdateSecurityGate.GateStatus.CHALLENGE_ISSUED, UpdateSecurityGate.getStatus())

        useCase.abortUpdate()
        assertEquals(UpdateSecurityGate.GateStatus.LOCKED, UpdateSecurityGate.getStatus())
    }

    @Test
    fun `test isUpdateRequired correctly compares version codes`() {
        assertTrue(useCase.isUpdateRequired(currentVersion = 1000L, remoteVersion = 1050L))
        org.junit.Assert.assertFalse(useCase.isUpdateRequired(currentVersion = 1050L, remoteVersion = 1050L))
        org.junit.Assert.assertFalse(useCase.isUpdateRequired(currentVersion = 1060L, remoteVersion = 1050L))
    }
}
