package com.juan.habitos.ui.state

import com.juan.habitos.data.mock.MockAuthRepository
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

private class AdultVmSessionStore : DeviceSessionRepository {
    override val session = MutableStateFlow(DeviceSession())
    override val adultAccessToken = MutableStateFlow<String?>(null)
    override suspend fun setMode(mode: DeviceMode) { session.value = session.value.copy(mode = mode) }
    override suspend fun authenticateAdult(email: String) { session.value = session.value.copy(DeviceMode.ADULT, email, true) }
    override suspend fun addLinkedProfile(profileKey: String) { session.value = session.value.copy(linkedProfileKeys = session.value.linkedProfileKeys + profileKey) }
    override suspend fun saveAdultAccessToken(token: String) { adultAccessToken.value = token }
    override suspend fun clearAdultAuthentication() { adultAccessToken.value = null; session.value = session.value.copy(adultAuthenticated = false) }
}

private class AdultVmAuthRepository : AuthRepository {
    var requestedEmail: String? = null
    var verifiedEmail: String? = null
    var verifiedCode: String? = null
    var verifyResult = true
    override suspend fun requestVerificationCode(email: String): AdultOtpChallenge { requestedEmail = email; return AdultOtpChallenge("sent", "987654") }
    override suspend fun verifyCode(email: String, code: String): Boolean { verifiedEmail = email; verifiedCode = code; return verifyResult }
    override suspend fun validateSession() = AdultAccount("adult-1", "adult@example.com", true)
}

private class AdultVmLinkRepository : LinkRepository {
    var listedProfiles: List<Profile> = emptyList()
    var claimedProfile: Profile? = null
    var claimFailure: Exception? = null
    var claimCode: String? = null
    var linkedProfile: Profile? = null
    override suspend fun profiles() = listedProfiles
    override suspend fun findProfile(code: String): Profile? { claimCode = code; claimFailure?.let { throw it }; return claimedProfile }
    override suspend fun link(profile: Profile) { linkedProfile = profile }
}

private class AdultCodeDeviceRepository : DeviceRepository {
    override val identity = MutableStateFlow(DeviceIdentity("installation-1", "device-1", "profile-1"))
    override suspend fun getOrCreateInstallationId() = "installation-1"
    override suspend fun getStoredDeviceId() = "device-1"
    override suspend fun registerDevice(profileId: String) = error("Not used")
    override suspend fun claimProfile(code: String) = error("Not used")
    override suspend fun createAdultProfileLinkCode() = AdultProfileLinkCode("728341", "2026-10-06T00:10:00Z", "ADULT_PROFILE_LINK")
}

@OptIn(ExperimentalCoroutinesApi::class)
class AdultViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `real auth uses entered email and OTP and exposes dev code only when enabled`() = runTest(dispatcher) {
        val auth = AdultVmAuthRepository()
        val session = AdultVmSessionStore()
        val viewModel = AdultAuthViewModel(auth, session, showDevCode = true)
        viewModel.email("adult@example.com")
        var sent = false
        viewModel.requestCode { sent = true }
        advanceUntilIdle()
        viewModel.code("987654")
        var verified = false
        viewModel.verify { verified = true }
        advanceUntilIdle()

        assertTrue(sent)
        assertEquals("adult@example.com", auth.requestedEmail)
        assertEquals("987654", viewModel.state.value.devCode)
        assertEquals("adult@example.com", auth.verifiedEmail)
        assertEquals("987654", auth.verifiedCode)
        assertTrue(verified)
        assertTrue(session.session.value.adultAuthenticated)
    }

    @Test fun `demo mock OTP remains available`() = runTest(dispatcher) {
        val session = AdultVmSessionStore()
        val viewModel = AdultAuthViewModel(MockAuthRepository(), session)
        viewModel.email("demo@example.com")
        viewModel.requestCode {}
        advanceUntilIdle()
        viewModel.code("123456")
        var verified = false
        viewModel.verify { verified = true }
        advanceUntilIdle()

        assertTrue(verified)
        assertTrue(session.session.value.adultAuthenticated)
    }

    @Test fun `empty adult profile list is represented and backend profiles replace it`() = runTest(dispatcher) {
        val repository = AdultVmLinkRepository()
        val viewModel = LinkDeviceViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.profiles.isEmpty())

        repository.listedProfiles = listOf(Profile("Backend", "fox", 9, id = "profile-1"))
        viewModel.refreshProfiles()
        advanceUntilIdle()
        assertEquals(listOf("Backend"), viewModel.state.value.profiles.map { it.name })
    }

    @Test fun `adult claim uses six digit code and revoked link shows neutral error`() = runTest(dispatcher) {
        val repository = AdultVmLinkRepository().apply { claimFailure = AdultLinkException(AdultLinkFailureReason.REVOKED_LINK, "revoked") }
        val viewModel = LinkDeviceViewModel(repository)
        advanceUntilIdle()
        viewModel.code("728341")
        viewModel.find()
        advanceUntilIdle()

        assertEquals("728341", repository.claimCode)
        assertEquals("No pudimos volver a vincular este perfil automáticamente.", viewModel.state.value.errorMessage)
        assertNull(repository.linkedProfile)
    }

    @Test fun `child link code state shows backend code and real expiry minutes`() = runTest(dispatcher) {
        val viewModel = AdultProfileLinkCodeViewModel(AdultCodeDeviceRepository()) { Instant.parse("2026-10-06T00:00:00Z") }
        advanceUntilIdle()

        assertEquals("728341", viewModel.state.value.code)
        assertEquals(10L, viewModel.state.value.validForMinutes)
        assertNull(viewModel.state.value.errorMessage)
    }
}
