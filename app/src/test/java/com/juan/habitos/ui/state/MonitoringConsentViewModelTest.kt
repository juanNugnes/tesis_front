package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.MonitoringConsent
import com.juan.habitos.domain.model.MonitoringConsentStatus
import com.juan.habitos.domain.model.RepresentativeVerification
import com.juan.habitos.domain.model.RepresentativeVerificationStatus
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.MonitoringConsentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class TestMonitoringConsentRepository(
    var verification: RepresentativeVerification? = verification(RepresentativeVerificationStatus.APPROVED),
    var current: MonitoringConsent? = null,
) : MonitoringConsentRepository {
    var verificationCalls = 0
    var currentCalls = 0
    var grantCalls = 0
    var revokeCalls = 0
    var grantedVersion: String? = null
    var grantedPurposes: Set<String>? = null
    var revokedId: String? = null
    var failure: Exception? = null

    override suspend fun getRepresentativeVerification(profileId: String): RepresentativeVerification? {
        verificationCalls++
        failure?.let { throw it }
        return verification
    }

    override suspend fun getCurrentConsent(profileId: String): MonitoringConsent? {
        currentCalls++
        failure?.let { throw it }
        return current
    }

    override suspend fun grantConsent(profileId: String, privacyNoticeVersion: String, purposes: Set<String>): MonitoringConsent {
        grantCalls++
        failure?.let { throw it }
        grantedVersion = privacyNoticeVersion
        grantedPurposes = purposes
        return activeConsent().also { current = it }
    }

    override suspend fun revokeConsent(profileId: String, consentId: String): MonitoringConsent {
        revokeCalls++
        failure?.let { throw it }
        revokedId = consentId
        current = null
        return activeConsent().copy(status = MonitoringConsentStatus.REVOKED, revokedAt = "2026-10-07T11:00:00Z")
    }
}

private fun verification(status: RepresentativeVerificationStatus) = RepresentativeVerification(
    id = "verification-1",
    profileId = "profile-1",
    status = status,
)

private fun activeConsent() = MonitoringConsent(
    id = "consent-1",
    profileId = "profile-1",
    status = MonitoringConsentStatus.ACTIVE,
    privacyNoticeVersion = "1.0",
    purposes = setOf("USAGE_MONITORING"),
    grantedAt = "2026-10-07T10:15:00Z",
)

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringConsentViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `pending verification never queries current consent or enables grant`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository(verification(RepresentativeVerificationStatus.PENDING))
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", demoMode = false)
        advanceUntilIdle()

        assertEquals(1, repository.verificationCalls)
        assertEquals(0, repository.currentCalls)
        assertEquals(RepresentativeVerificationStatus.PENDING, viewModel.state.value.verificationStatus)
        assertFalse(viewModel.state.value.canGrant)
    }

    @Test fun `rejected verification never queries current consent`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository(verification(RepresentativeVerificationStatus.REJECTED))
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", demoMode = false)
        advanceUntilIdle()

        assertEquals(0, repository.currentCalls)
        assertEquals(RepresentativeVerificationStatus.REJECTED, viewModel.state.value.verificationStatus)
        assertFalse(viewModel.state.value.canGrant)
    }

    @Test fun `approved verification without active consent enables explicit grant`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository()
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", demoMode = false)
        advanceUntilIdle()

        assertEquals(1, repository.currentCalls)
        assertNull(viewModel.state.value.consent)
        assertTrue(viewModel.state.value.canGrant)
    }

    @Test fun `explicit grant sends fixed version and purpose then reloads backend state`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository()
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", demoMode = false)
        advanceUntilIdle()

        viewModel.grant()
        viewModel.grant()
        advanceUntilIdle()

        assertEquals(1, repository.grantCalls)
        assertEquals(PRIVACY_NOTICE_VERSION, repository.grantedVersion)
        assertEquals(setOf("USAGE_MONITORING"), repository.grantedPurposes)
        assertEquals(2, repository.verificationCalls)
        assertEquals(2, repository.currentCalls)
        assertTrue(viewModel.state.value.monitoringEnabled)
        assertFalse(viewModel.state.value.canGrant)
    }

    @Test fun `active consent requires confirmation and revoke reloads deactivated state`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository(current = activeConsent())
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", demoMode = false)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.monitoringEnabled)

        viewModel.requestRevoke()
        assertTrue(viewModel.state.value.showRevokeConfirmation)
        viewModel.confirmRevoke()
        viewModel.confirmRevoke()
        advanceUntilIdle()

        assertEquals(1, repository.revokeCalls)
        assertEquals("consent-1", repository.revokedId)
        assertEquals(2, repository.verificationCalls)
        assertEquals(2, repository.currentCalls)
        assertFalse(viewModel.state.value.showRevokeConfirmation)
        assertTrue(viewModel.state.value.monitoringRevoked)
        assertTrue(viewModel.state.value.canGrant)
    }

    @Test fun `missing verification stays neutral and never implies pending`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository(verification = null)
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", demoMode = false)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.verificationMissing)
        assertNull(viewModel.state.value.verificationStatus)
        assertEquals(0, repository.currentCalls)
    }

    @Test fun `adult 401 requests login while 403 stays on screen`() = runTest(dispatcher) {
        val expiredRepository = TestMonitoringConsentRepository().apply { failure = AdultAuthenticationRequiredException() }
        val expired = MonitoringConsentViewModel(expiredRepository, "profile-1", demoMode = false)
        advanceUntilIdle()
        assertTrue(expired.state.value.adultAuthenticationRequired)

        val forbiddenRepository = TestMonitoringConsentRepository().apply { failure = AdultAuthorizationException() }
        val forbidden = MonitoringConsentViewModel(forbiddenRepository, "profile-1", demoMode = false)
        advanceUntilIdle()
        assertFalse(forbidden.state.value.adultAuthenticationRequired)
        assertEquals("No tenés permiso para administrar el monitoreo de este perfil.", forbidden.state.value.errorMessage)
    }

    @Test fun `demo mode never accesses backend`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository()
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", demoMode = true)
        advanceUntilIdle()

        assertEquals(0, repository.verificationCalls)
        assertEquals(0, repository.currentCalls)
        assertFalse(viewModel.state.value.loading)
    }
}
