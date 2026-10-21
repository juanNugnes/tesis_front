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
        return activeConsent().copy(privacyNoticeVersion = privacyNoticeVersion).also { current = it }
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
        assertFalse(viewModel.state.value.explicitlyAccepted)
        assertFalse(viewModel.state.value.canGrant)
        viewModel.setAcceptance(true)
        assertTrue(viewModel.state.value.canGrant)
    }

    @Test fun `explicit grant sends fixed version and purpose then reloads backend state`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository()
        val resumedProfiles = mutableListOf<String>()
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", demoMode = false) { resumedProfiles += it }
        advanceUntilIdle()

        viewModel.setAcceptance(true)
        viewModel.grant()
        viewModel.grant()
        advanceUntilIdle()

        assertEquals(1, repository.grantCalls)
        assertEquals(PRIVACY_NOTICE_VERSION, repository.grantedVersion)
        assertEquals("1.1", repository.grantedVersion)
        assertEquals("1.1", viewModel.state.value.consent?.privacyNoticeVersion)
        assertEquals(setOf("USAGE_MONITORING"), repository.grantedPurposes)
        assertEquals(listOf("profile-1"), resumedProfiles)
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
        assertFalse(viewModel.state.value.canGrant)
        assertFalse(viewModel.state.value.explicitlyAccepted)
        viewModel.setAcceptance(true)
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

    @Test fun `direct grant without accepting notice never submits or resumes synchronization`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository()
        var resumed = false
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", false) { resumed = true }
        viewModel.setAcceptance(true) // Loading cannot pre-authorize acceptance.
        advanceUntilIdle()
        viewModel.grant()
        advanceUntilIdle()
        assertEquals(0, repository.grantCalls)
        assertFalse(resumed)
        assertFalse(viewModel.state.value.explicitlyAccepted)
    }

    @Test fun `unchecking and leaving screen require a new voluntary acceptance`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository()
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", false)
        advanceUntilIdle()
        viewModel.setAcceptance(true)
        assertTrue(viewModel.state.value.canGrant)
        viewModel.setAcceptance(false)
        assertFalse(viewModel.state.value.canGrant)
        viewModel.setAcceptance(true)
        viewModel.clearAcceptance() // Same operation used on route entry/disposal.
        viewModel.grant()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.explicitlyAccepted)
        assertEquals(0, repository.grantCalls)
        val reentered = MonitoringConsentViewModel(repository, "profile-1", false)
        advanceUntilIdle()
        assertFalse(reentered.state.value.explicitlyAccepted)
        assertFalse(reentered.state.value.canGrant)
    }

    @Test fun `old active consent retains version and cannot be silently replaced`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository(current = activeConsent())
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", false)
        advanceUntilIdle()
        viewModel.setAcceptance(true); viewModel.grant(); viewModel.refresh()
        advanceUntilIdle()
        assertEquals("1.0", viewModel.state.value.consent?.privacyNoticeVersion)
        assertEquals(0, repository.grantCalls)
        assertTrue(viewModel.state.value.monitoringEnabled)
        assertFalse(viewModel.state.value.explicitlyAccepted)
    }

    @Test fun `information remains available with active consent and never grants or revokes`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository(current = activeConsent())
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", false)
        advanceUntilIdle()
        val reads = repository.verificationCalls + repository.currentCalls
        viewModel.showDataTreatmentInformation()
        assertTrue(viewModel.state.value.showDataTreatmentInformation)
        assertFalse(viewModel.state.value.explicitlyAccepted)
        viewModel.hideDataTreatmentInformation()
        assertFalse(viewModel.state.value.showDataTreatmentInformation)
        assertEquals(reads, repository.verificationCalls + repository.currentCalls)
        assertEquals(0, repository.grantCalls)
        assertEquals(0, repository.revokeCalls)
    }

    @Test fun `acceptance cannot bypass missing pending or rejected representative verification`() = runTest(dispatcher) {
        for (status in listOf(null, RepresentativeVerificationStatus.PENDING, RepresentativeVerificationStatus.REJECTED)) {
            val repository = TestMonitoringConsentRepository(status?.let(::verification))
            val viewModel = MonitoringConsentViewModel(repository, "profile-1", false)
            advanceUntilIdle()
            viewModel.setAcceptance(true); viewModel.grant()
            advanceUntilIdle()
            assertFalse(viewModel.state.value.canGrant)
            assertFalse(viewModel.state.value.explicitlyAccepted)
            assertEquals(0, repository.grantCalls)
        }
    }

    @Test fun `revoked old consent requires fresh acceptance for version 1_1`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository(current = activeConsent().copy(status = MonitoringConsentStatus.REVOKED))
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", false)
        advanceUntilIdle()
        viewModel.grant()
        advanceUntilIdle()
        assertEquals(0, repository.grantCalls)
        assertEquals("1.0", viewModel.state.value.consent?.privacyNoticeVersion)
        viewModel.setAcceptance(true); viewModel.grant()
        advanceUntilIdle()
        assertEquals(1, repository.grantCalls)
        assertEquals("1.1", viewModel.state.value.consent?.privacyNoticeVersion)
        assertFalse(viewModel.state.value.explicitlyAccepted)
    }

    @Test fun `authorization failure clears acceptance and prevents resubmission`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository()
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", false)
        advanceUntilIdle()
        viewModel.setAcceptance(true)
        repository.failure = AdultAuthorizationException()
        viewModel.grant()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.explicitlyAccepted)
        assertFalse(viewModel.state.value.canGrant)
        viewModel.setAcceptance(true); viewModel.grant()
        advanceUntilIdle()
        assertEquals(1, repository.grantCalls)
    }

    @Test fun `grant is disabled while submitting and acceptance is not inherited after reload`() = runTest(dispatcher) {
        val repository = TestMonitoringConsentRepository()
        val viewModel = MonitoringConsentViewModel(repository, "profile-1", false)
        advanceUntilIdle()
        viewModel.setAcceptance(true); viewModel.grant()
        assertTrue(viewModel.state.value.submitting)
        viewModel.setAcceptance(true)
        assertFalse(viewModel.state.value.explicitlyAccepted)
        assertFalse(viewModel.state.value.canGrant)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.explicitlyAccepted)
    }
}
