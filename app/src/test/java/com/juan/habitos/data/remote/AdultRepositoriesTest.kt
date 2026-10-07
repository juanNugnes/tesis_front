package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.model.DeviceIdentity
import com.juan.habitos.domain.model.DeviceMode
import com.juan.habitos.domain.model.DeviceSession
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.AdultLinkException
import com.juan.habitos.domain.repository.AdultLinkFailureReason
import com.juan.habitos.domain.repository.DeviceSessionRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private class AdultTestSessionStore(token: String? = null) : DeviceSessionRepository {
    override val session = MutableStateFlow(DeviceSession(DeviceMode.ADULT, "adult@example.com", token != null))
    override val adultAccessToken = MutableStateFlow(token)
    val deviceIdentity = MutableStateFlow(DeviceIdentity("installation-1", "device-1", "profile-1"))
    val deviceCredentials = MutableStateFlow(DeviceCredentials(accessToken = "device-jwt"))
    override suspend fun setMode(mode: DeviceMode) { session.value = session.value.copy(mode = mode) }
    override suspend fun authenticateAdult(email: String) { session.value = session.value.copy(mode = DeviceMode.ADULT, adultEmail = email, adultAuthenticated = true) }
    override suspend fun addLinkedProfile(profileKey: String) { session.value = session.value.copy(linkedProfileKeys = session.value.linkedProfileKeys + profileKey) }
    override suspend fun saveAdultAccessToken(token: String) { adultAccessToken.value = token }
    override suspend fun clearAdultAuthentication() { adultAccessToken.value = null; session.value = session.value.copy(adultAuthenticated = false) }
}

private class AdultTestProfileStore(initial: Profile? = null) : ProfileLocalStore {
    override val profile = MutableStateFlow(initial)
    override val onboardingComplete = MutableStateFlow(false)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class AdultTestApi : HabitsApi {
    var otpRequest: AdultOtpRequest? = null
    var otpVerifyRequest: AdultOtpVerifyRequest? = null
    var otpResponse = AdultOtpRequestResponse("sent", "987654")
    var tokenResponse = AdultAccessTokenDto("adult-jwt", expiresIn = 3600)
    var verifyFailure: Exception? = null
    var meAuthorization: String? = null
    var meFailure: Exception? = null
    var profilesAuthorization: String? = null
    var profilesCalls = 0
    var profilesResponse: List<ProfileDetailDto> = emptyList()
    var claimAuthorization: String? = null
    var claimRequest: AdultProfileLinkClaimRequest? = null
    var claimFailure: Exception? = null
    var claimResponse = AdultProfileLinkClaimResponse(profileDetail(), AdultProfileLinkDto("link-1", "profile-1", true, "2026-10-06T00:00:00Z"))

    override suspend fun requestAdultOtp(request: AdultOtpRequest): AdultOtpRequestResponse { otpRequest = request; return otpResponse }
    override suspend fun verifyAdultOtp(request: AdultOtpVerifyRequest): AdultAccessTokenDto { otpVerifyRequest = request; verifyFailure?.let { throw it }; return tokenResponse }
    override suspend fun getAdultMe(authorization: String): AdultDto { meAuthorization = authorization; meFailure?.let { throw it }; return AdultDto("adult-1", "adult@example.com", true) }
    override suspend fun getAdultProfiles(authorization: String): List<ProfileDetailDto> { profilesAuthorization = authorization; profilesCalls++; return profilesResponse }
    override suspend fun claimAdultProfileLink(authorization: String, request: AdultProfileLinkClaimRequest): AdultProfileLinkClaimResponse { claimAuthorization = authorization; claimRequest = request; claimFailure?.let { throw it }; return claimResponse }
    override suspend fun createAdultProfileLinkCode(profileId: String, authorization: String): AdultProfileLinkCodeDto = error("Not used")
    override suspend fun createProfile(request: CreateProfileRequest): ProfileBootstrapDto = error("Not used")
    override suspend fun getProfile(id: String, authorization: String): ProfileDetailDto = error("Not used")
    override suspend fun updateProfile(id: String, authorization: String, request: ProfileUpdateRequest): ProfileDetailDto = error("Not used")
    override suspend fun getProfileSettings(id: String, authorization: String): ProfileSettingsDto = error("Not used")
    override suspend fun updateProfileSettings(id: String, authorization: String, request: ProfileSettingsRequest): ProfileSettingsDto = error("Not used")
    override suspend fun getActivityPreferences(id: String, authorization: String): ActivityPreferencesDto = error("Not used")
    override suspend fun updateActivityPreferences(id: String, authorization: String, request: ActivityPreferencesRequest): ActivityPreferencesDto = error("Not used")
    override suspend fun createProfileContext(profileId: String, authorization: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun getProfileContexts(profileId: String, authorization: String): List<ProfileContextDto> = error("Not used")
    override suspend fun getActiveProfileContexts(profileId: String, authorization: String): List<ProfileContextDto> = error("Not used")
    override suspend fun updateProfileContext(profileId: String, contextId: String, authorization: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun deleteProfileContext(profileId: String, contextId: String, authorization: String) = error("Not used")
    override suspend fun registerDevice(profileId: String, authorization: String, request: DeviceCreateRequest): Response<DeviceRegistrationDto> = error("Not used")
    override suspend fun createDeviceLinkCode(profileId: String): Response<DeviceLinkCodeDto> = error("Not used")
    override suspend fun claimDeviceLink(request: DeviceLinkClaimRequest): Response<DeviceLinkClaimResponse> = error("Not used")
    override suspend fun getRepresentativeVerification(profileId: String, authorization: String): RepresentativeVerificationDto = error("Not used")
    override suspend fun grantConsent(profileId: String, authorization: String, request: ConsentGrantRequest): ConsentDto = error("Not used")
    override suspend fun getCurrentConsent(profileId: String, authorization: String): ConsentDto = error("Not used")
    override suspend fun revokeConsent(profileId: String, consentId: String, authorization: String): ConsentDto = error("Not used")
    override suspend fun postUsage(id: String, usage: UsageDto): UsageDto = error("Not used")
    override suspend fun getUsage(id: String): UsageDto = error("Not used")
    override suspend fun postAnalysis(id: String): AnalysisDto = error("Not used")
    override suspend fun latestAnalysis(id: String): AnalysisDto = error("Not used")
}

private fun profileDetail(id: String = "profile-1", alias: String = "Juana") = ProfileDetailDto(id, alias, 9, "fox", true)
private fun adultHttpException(code: Int) = HttpException(Response.error<Unit>(code, ResponseBody.create(MediaType.parse("application/json"), "{}")))

class AdultRepositoriesTest {
    @Test fun `OTP request and verify use entered values and persist real token`() = runTest {
        val api = AdultTestApi()
        val store = AdultTestSessionStore()
        val repository = RemoteAdultAuthRepository(api, store)

        val challenge = repository.requestVerificationCode("adult@example.com")
        val verified = repository.verifyCode("adult@example.com", "987654")

        assertEquals("adult@example.com", api.otpRequest?.email)
        assertEquals("987654", api.otpVerifyRequest?.code)
        assertEquals("987654", challenge.devCode)
        assertTrue(verified)
        assertEquals("adult-jwt", store.adultAccessToken.value)
    }

    @Test fun `non demo repository does not accept fixed mock OTP`() = runTest {
        val api = AdultTestApi().apply { verifyFailure = adultHttpException(401) }
        val store = AdultTestSessionStore()
        val repository = RemoteAdultAuthRepository(api, store)

        assertFalse(repository.verifyCode("adult@example.com", "123456"))
        assertEquals("123456", api.otpVerifyRequest?.code)
        assertNull(store.adultAccessToken.value)
    }

    @Test fun `adult me uses Adult bearer`() = runTest {
        val api = AdultTestApi()
        val store = AdultTestSessionStore("adult-jwt")

        val adult = RemoteAdultAuthRepository(api, store).validateSession()

        assertEquals("Bearer adult-jwt", api.meAuthorization)
        assertEquals("adult-1", adult.id)
        assertEquals("adult@example.com", adult.email)
    }

    @Test fun `adult 401 clears only adult token and keeps child identity token and profile`() = runTest {
        val cached = Profile("Juana", "fox", 9, id = "profile-1")
        val profileStore = AdultTestProfileStore(cached)
        val store = AdultTestSessionStore("adult-jwt")
        val identity = store.deviceIdentity.value
        val deviceCredentials = store.deviceCredentials.value
        val api = AdultTestApi().apply { meFailure = adultHttpException(401) }

        try { RemoteAdultAuthRepository(api, store).validateSession(); fail("Expected adult auth failure") }
        catch (_: AdultAuthenticationRequiredException) {}

        assertNull(store.adultAccessToken.value)
        assertFalse(store.session.value.adultAuthenticated)
        assertEquals(identity, store.deviceIdentity.value)
        assertEquals(deviceCredentials, store.deviceCredentials.value)
        assertEquals(cached, profileStore.profile.value)
    }

    @Test fun `adult 403 preserves adult token`() = runTest {
        val store = AdultTestSessionStore("adult-jwt")
        val api = AdultTestApi().apply { meFailure = adultHttpException(403) }

        try { RemoteAdultAuthRepository(api, store).validateSession(); fail("Expected forbidden") }
        catch (_: AdultAuthorizationException) {}

        assertEquals("adult-jwt", store.adultAccessToken.value)
        assertTrue(store.session.value.adultAuthenticated)
    }

    @Test fun `adult profiles use Adult bearer and empty response stays empty`() = runTest {
        val api = AdultTestApi()
        val store = AdultTestSessionStore("adult-jwt")
        val repository = RemoteLinkRepository(api, store, AdultTestProfileStore())

        assertTrue(repository.profiles().isEmpty())
        assertEquals("Bearer adult-jwt", api.profilesAuthorization)

        api.profilesResponse = listOf(profileDetail(alias = "Backend"))
        assertEquals(listOf("Backend"), repository.profiles().map { it.name })
    }

    @Test fun `adult claim sends only code refreshes profiles and stores selected backend profile`() = runTest {
        val api = AdultTestApi().apply { profilesResponse = listOf(profileDetail()) }
        val store = AdultTestSessionStore("adult-jwt")
        val profileStore = AdultTestProfileStore()
        val repository = RemoteLinkRepository(api, store, profileStore)

        val claimed = repository.findProfile("728341")

        assertEquals("Bearer adult-jwt", api.claimAuthorization)
        assertEquals("728341", api.claimRequest?.code)
        val json = Gson().toJson(api.claimRequest)
        assertFalse(json.contains("adult_id"))
        assertFalse(json.contains("profile_id"))
        assertEquals(1, api.profilesCalls)
        assertEquals("profile-1", claimed?.id)
        assertEquals("profile-1", profileStore.profile.value?.id)
    }

    @Test fun `revoked link maps 409 and does not reactivate locally`() = runTest {
        val api = AdultTestApi().apply { claimFailure = adultHttpException(409) }
        val store = AdultTestSessionStore("adult-jwt")
        val profileStore = AdultTestProfileStore()
        val repository = RemoteLinkRepository(api, store, profileStore)

        try { repository.findProfile("728341"); fail("Expected revoked link") }
        catch (exception: AdultLinkException) { assertEquals(AdultLinkFailureReason.REVOKED_LINK, exception.reason) }

        assertNull(profileStore.profile.value)
        assertTrue(store.session.value.linkedProfileKeys.isEmpty())
        assertEquals(0, api.profilesCalls)
    }

    @Test fun `invalid expired or used code maps to one neutral failure`() = runTest {
        for (status in listOf(400, 404, 410, 422)) {
            val api = AdultTestApi().apply { claimFailure = adultHttpException(status) }
            val repository = RemoteLinkRepository(api, AdultTestSessionStore("adult-jwt"), AdultTestProfileStore())

            try { repository.findProfile("728341"); fail("Expected invalid code for HTTP $status") }
            catch (exception: AdultLinkException) { assertEquals(AdultLinkFailureReason.INVALID_OR_EXPIRED_CODE, exception.reason) }
        }
    }
}
