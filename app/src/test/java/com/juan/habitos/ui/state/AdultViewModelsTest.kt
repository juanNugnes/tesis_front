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
    override suspend fun authenticateAdult(email: String) {
        val currentMode = session.value.mode
        session.value = session.value.copy(
            mode = if (currentMode == DeviceMode.CHILD) DeviceMode.CHILD else DeviceMode.ADULT,
            adultEmail = email,
            adultAuthenticated = true,
        )
    }
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

private class AdultVmProfileStore(initial: Profile?) : ProfileLocalStore {
    override val profile = MutableStateFlow(initial)
    override val onboardingComplete = MutableStateFlow(true)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class AdultCodeDeviceRepository : DeviceRepository {
    var adultLinkCodeCalls = 0
    var adultLinkCodeFailure: Exception? = null
    override val identity = MutableStateFlow(DeviceIdentity("installation-1", "device-1", "profile-1"))
    override suspend fun getOrCreateInstallationId() = "installation-1"
    override suspend fun getStoredDeviceId() = "device-1"
    override suspend fun registerDevice(profileId: String) = error("Not used")
    override suspend fun claimProfile(code: String) = error("Not used")
    override suspend fun createAdultProfileLinkCode(): AdultProfileLinkCode {
        adultLinkCodeCalls++
        adultLinkCodeFailure?.let { throw it }
        return AdultProfileLinkCode("728341", "2026-10-06T00:10:00Z", "ADULT_PROFILE_LINK")
    }
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

    @Test fun `adult authentication entered from child mode preserves child device mode`() = runTest(dispatcher) {
        val session = AdultVmSessionStore().apply { setMode(DeviceMode.CHILD) }
        val viewModel = AdultAuthViewModel(AdultVmAuthRepository(), session)
        viewModel.email("adult@example.com")
        viewModel.requestCode {}
        advanceUntilIdle()
        viewModel.code("987654")
        viewModel.verify {}
        advanceUntilIdle()

        assertEquals(DeviceMode.CHILD, session.session.value.mode)
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

    @Test fun `same device monitoring reuses existing adult link without creating another code`() = runTest(dispatcher) {
        val profile = Profile("Juana", "fox", 9, id = "profile-1")
        val links = AdultVmLinkRepository().apply { listedProfiles = listOf(profile) }
        val device = AdultCodeDeviceRepository()
        val viewModel = SameDeviceMonitoringViewModel(AdultVmProfileStore(profile), device, links, demoMode = false)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.ready)
        assertEquals(0, device.adultLinkCodeCalls)
        assertNull(links.linkedProfile)
    }

    @Test fun `same device monitoring creates and claims current profile link when missing`() = runTest(dispatcher) {
        val profile = Profile("Juana", "fox", 9, id = "profile-1")
        val links = AdultVmLinkRepository().apply { claimedProfile = profile }
        val device = AdultCodeDeviceRepository()
        val viewModel = SameDeviceMonitoringViewModel(AdultVmProfileStore(profile), device, links, demoMode = false)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.ready)
        assertEquals(1, device.adultLinkCodeCalls)
        assertEquals("728341", links.claimCode)
        assertEquals(profile, links.linkedProfile)
    }

    @Test fun `same device monitoring requires real adult authentication before linking`() = runTest(dispatcher) {
        val profile = Profile("Juana", "fox", 9, id = "profile-1")
        val device = AdultCodeDeviceRepository()
        val authenticationRequiredLinks = object : LinkRepository {
            override suspend fun profiles(): List<Profile> = throw AdultAuthenticationRequiredException()
            override suspend fun findProfile(code: String): Profile? = null
            override suspend fun link(profile: Profile) = Unit
        }
        val viewModel = SameDeviceMonitoringViewModel(AdultVmProfileStore(profile), device, authenticationRequiredLinks, demoMode = false)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.adultAuthenticationRequired)
        assertFalse(viewModel.state.value.ready)
        assertEquals(0, device.adultLinkCodeCalls)
    }

    @Test fun `same device monitoring reports adult authorization errors without bypassing backend`() = runTest(dispatcher) {
        val profile = Profile("Juana", "fox", 9, id = "profile-1")
        val forbiddenLinks = object : LinkRepository {
            override suspend fun profiles(): List<Profile> = throw AdultAuthorizationException()
            override suspend fun findProfile(code: String): Profile? = null
            override suspend fun link(profile: Profile) = Unit
        }
        val viewModel = SameDeviceMonitoringViewModel(AdultVmProfileStore(profile), AdultCodeDeviceRepository(), forbiddenLinks, demoMode = false)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.adultAuthenticationRequired)
        assertTrue(viewModel.state.value.errorMessage!!.contains("no tiene permiso"))
    }

    @Test fun `same device monitoring preserves child link when device bearer is unavailable`() = runTest(dispatcher) {
        val profile = Profile("Juana", "fox", 9, id = "profile-1")
        val links = AdultVmLinkRepository()
        val device = AdultCodeDeviceRepository().apply {
            adultLinkCodeFailure = DeviceAuthenticationRequiredException(DeviceAuthenticationFailureReason.EXPIRED_OR_INVALID)
        }
        val viewModel = SameDeviceMonitoringViewModel(AdultVmProfileStore(profile), device, links, demoMode = false)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.ready)
        assertTrue(viewModel.state.value.errorMessage!!.contains("volver a vincularse"))
        assertNull(links.claimCode)
    }

    @Test fun `same device monitoring demo does not call real link services`() = runTest(dispatcher) {
        val device = AdultCodeDeviceRepository()
        val links = AdultVmLinkRepository()
        val viewModel = SameDeviceMonitoringViewModel(AdultVmProfileStore(null), device, links, demoMode = true)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.ready)
        assertEquals(0, device.adultLinkCodeCalls)
        assertNull(links.claimCode)
    }
}
