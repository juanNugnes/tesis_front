package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.juan.habitos.domain.model.DeviceIdentity
import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.DeviceAuthenticationRequiredException
import com.juan.habitos.domain.repository.DeviceLinkException
import com.juan.habitos.domain.repository.DeviceLinkFailureReason
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import retrofit2.HttpException
import java.time.Instant

private class FakeDeviceIdentityStore(
    private val generatedId: String,
    initial: DeviceIdentity = DeviceIdentity(),
    initialCredentials: DeviceCredentials = DeviceCredentials(
        bootstrapToken = "bootstrap-token",
        bootstrapExpiresAt = "2099-01-01T00:00:00Z",
    ),
) : DeviceIdentityLocalStore {
    override val identity = MutableStateFlow(initial)
    override val credentials = MutableStateFlow(initialCredentials)
    var generationCalls = 0

    override suspend fun getOrCreateInstallationId(): String {
        identity.value.installationId?.let { return it }
        generationCalls++
        identity.value = identity.value.copy(installationId = generatedId)
        return generatedId
    }

    override suspend fun saveBootstrap(token: String, expiresAt: String) {
        credentials.value = credentials.value.copy(bootstrapToken = token, bootstrapExpiresAt = expiresAt)
    }
    override suspend fun clearBootstrap() {
        credentials.value = credentials.value.copy(bootstrapToken = null, bootstrapExpiresAt = null)
    }
    override suspend fun saveAuthenticatedLink(profileId: String, deviceId: String, accessToken: String) {
        identity.value = identity.value.copy(profileId = profileId, deviceId = deviceId)
        credentials.value = DeviceCredentials(accessToken = accessToken)
    }
    override suspend fun clearDeviceAccessToken() {
        credentials.value = credentials.value.copy(accessToken = null)
    }
}

private class DeviceTestProfileStore(initial: Profile? = null) : ProfileLocalStore {
    override val profile = MutableStateFlow(initial)
    override val onboardingComplete = MutableStateFlow(false)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class DeviceTestApi : HabitsApi {
    override suspend fun requestAdultOtp(request: AdultOtpRequest): AdultOtpRequestResponse = error("Not used")
    override suspend fun verifyAdultOtp(request: AdultOtpVerifyRequest): AdultAccessTokenDto = error("Not used")
    override suspend fun getAdultMe(authorization: String): AdultDto = error("Not used")
    override suspend fun getAdultProfiles(authorization: String): List<ProfileDetailDto> = error("Not used")
    override suspend fun claimAdultProfileLink(authorization: String, request: AdultProfileLinkClaimRequest): AdultProfileLinkClaimResponse = error("Not used")
    var adultLinkCodeProfileId: String? = null
    var adultLinkCodeAuthorization: String? = null
    var adultLinkCodeResponse = AdultProfileLinkCodeDto("728341", "2099-01-01T00:10:00Z", "ADULT_PROFILE_LINK")
    override suspend fun createAdultProfileLinkCode(profileId: String, authorization: String): AdultProfileLinkCodeDto {
        adultLinkCodeProfileId = profileId
        adultLinkCodeAuthorization = authorization
        return adultLinkCodeResponse
    }
    var registerResponse: Response<DeviceRegistrationDto> = Response.success(201, registrationDto())
    var claimResponse: Response<DeviceLinkClaimResponse> = Response.success(DeviceLinkClaimResponse(deviceDto(), profileDto(), "claim-jwt", expiresIn = 3600))
    var registerProfileId: String? = null
    var registerAuthorization: String? = null
    var registerRequest: DeviceCreateRequest? = null
    var claimRequest: DeviceLinkClaimRequest? = null

    override suspend fun registerDevice(profileId: String, authorization: String, request: DeviceCreateRequest): Response<DeviceRegistrationDto> {
        registerProfileId = profileId
        registerAuthorization = authorization
        registerRequest = request
        return registerResponse
    }
    override suspend fun claimDeviceLink(request: DeviceLinkClaimRequest): Response<DeviceLinkClaimResponse> {
        claimRequest = request
        return claimResponse
    }
    override suspend fun createDeviceLinkCode(profileId: String): Response<DeviceLinkCodeDto> = error("Not used")
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
    override suspend fun getRepresentativeVerification(profileId: String, authorization: String): RepresentativeVerificationDto = error("Not used")
    override suspend fun grantConsent(profileId: String, authorization: String, request: ConsentGrantRequest): ConsentDto = error("Not used")
    override suspend fun getCurrentConsent(profileId: String, authorization: String): ConsentDto = error("Not used")
    override suspend fun revokeConsent(profileId: String, consentId: String, authorization: String): ConsentDto = error("Not used")
    override suspend fun postUsage(id: String, usage: UsageDto): UsageDto = error("Not used")
    override suspend fun getUsage(id: String): UsageDto = error("Not used")
    override suspend fun postAnalysis(id: String): AnalysisDto = error("Not used")
    override suspend fun latestAnalysis(id: String): AnalysisDto = error("Not used")
}

private fun deviceDto(
    id: String = "device-1",
    profileId: String = "profile-1",
    installationId: String = "installation-1",
) = DeviceDto(id, profileId, installationId, "ANDROID", true)

private fun registrationDto(
    id: String = "device-1",
    profileId: String = "profile-1",
    installationId: String = "installation-1",
    accessToken: String = "device-jwt",
) = DeviceRegistrationDto(id, profileId, installationId, "ANDROID", true, accessToken = accessToken, expiresIn = 3600)

private fun profileDto(id: String = "profile-1") = ProfileDetailDto(id, "Juana", 9, "fox", true)

private fun <T> errorResponse(code: Int): Response<T> = Response.error(
    code,
    ResponseBody.create(MediaType.parse("application/json"), "{}"),
)

class DeviceRepositoryTest {
    @Test fun `installation id survives repository recreation and new storage gets another id`() = runTest {
        val sharedStore = FakeDeviceIdentityStore("installation-1")
        val api = DeviceTestApi()
        val firstRepository = RemoteDeviceRepository(sharedStore, DeviceTestProfileStore(), api)
        val restartedRepository = RemoteDeviceRepository(sharedStore, DeviceTestProfileStore(), api)

        assertEquals("installation-1", firstRepository.getOrCreateInstallationId())
        assertEquals("installation-1", firstRepository.getOrCreateInstallationId())
        assertEquals("installation-1", restartedRepository.getOrCreateInstallationId())
        assertEquals(1, sharedStore.generationCalls)

        val newInstall = RemoteDeviceRepository(FakeDeviceIdentityStore("installation-2"), DeviceTestProfileStore(), api)
        assertNotEquals(firstRepository.getOrCreateInstallationId(), newInstall.getOrCreateInstallationId())
    }

    @Test fun `device requests serialize snake case installation and android platform`() {
        val registerJson = Gson().toJson(DeviceCreateRequest("installation-1"))
        val claimJson = Gson().toJson(DeviceLinkClaimRequest("483921", "installation-1"))

        assertTrue(registerJson.contains("\"installation_id\":\"installation-1\""))
        assertTrue(registerJson.contains("\"platform\":\"ANDROID\""))
        assertTrue(claimJson.contains("\"code\":\"483921\""))
        assertTrue(claimJson.contains("\"installation_id\":\"installation-1\""))
        assertTrue(claimJson.contains("\"platform\":\"ANDROID\""))
    }

    @Test fun `201 device registration saves independent profile and device ids`() = runTest {
        val store = FakeDeviceIdentityStore("installation-1")
        val api = DeviceTestApi().apply { registerResponse = Response.success(201, registrationDto()) }
        val repository = RemoteDeviceRepository(store, DeviceTestProfileStore(), api)

        val registered = repository.registerDevice("profile-1")

        assertEquals("profile-1", api.registerProfileId)
        assertEquals("installation-1", api.registerRequest?.installationId)
        assertEquals("Bearer bootstrap-token", api.registerAuthorization)
        assertEquals("ANDROID", api.registerRequest?.platform)
        assertEquals("device-1", registered.id)
        assertEquals("profile-1", store.identity.value.profileId)
        assertEquals("device-1", store.identity.value.deviceId)
        assertEquals("installation-1", store.identity.value.installationId)
        assertEquals("device-jwt", store.credentials.value.accessToken)
        assertNull(store.credentials.value.bootstrapToken)
        assertEquals("device-1", repository.getStoredDeviceId())
    }

    @Test fun `200 idempotent device registration is also accepted and reused`() = runTest {
        val store = FakeDeviceIdentityStore("installation-1")
        val api = DeviceTestApi().apply { registerResponse = Response.success(200, registrationDto(id = "existing-device")) }
        val repository = RemoteDeviceRepository(store, DeviceTestProfileStore(), api)

        repository.registerDevice("profile-1")

        assertEquals("existing-device", store.identity.value.deviceId)
        assertEquals(1, store.generationCalls)
    }

    @Test fun `expired bootstrap is cleared and registration is not attempted`() = runTest {
        val store = FakeDeviceIdentityStore(
            generatedId = "installation-1",
            initialCredentials = DeviceCredentials(
                bootstrapToken = "expired-bootstrap",
                bootstrapExpiresAt = "2026-10-05T23:59:59Z",
            ),
        )
        val api = DeviceTestApi()
        val repository = RemoteDeviceRepository(
            store,
            DeviceTestProfileStore(),
            api,
            now = { Instant.parse("2026-10-06T00:00:00Z") },
        )

        try {
            repository.registerDevice("profile-1")
            fail("Expected expired bootstrap")
        } catch (exception: DeviceLinkException) {
            assertEquals(DeviceLinkFailureReason.BOOTSTRAP_EXPIRED, exception.reason)
        }
        assertNull(store.credentials.value.bootstrapToken)
        assertNull(store.credentials.value.bootstrapExpiresAt)
        assertNull(api.registerProfileId)
    }

    @Test fun `401 clears only device JWT and preserves identity and profile cache`() = runTest {
        val identity = DeviceIdentity("installation-1", "device-1", "profile-1")
        val profile = Profile("Juana", "fox", 9, id = "profile-1")
        val store = FakeDeviceIdentityStore(
            generatedId = "unused",
            initial = identity,
            initialCredentials = DeviceCredentials(accessToken = "device-jwt"),
        )
        val profileStore = DeviceTestProfileStore(profile)

        try {
            deviceAuthenticatedRequest<Unit>(store) { throw HttpException(errorResponse<Unit>(401)) }
            fail("Expected authentication-required failure")
        } catch (_: DeviceAuthenticationRequiredException) {
            // Expected.
        }

        assertNull(store.credentials.value.accessToken)
        assertEquals(identity, store.identity.value)
        assertEquals(profile, profileStore.profile.value)
    }

    @Test fun `403 preserves device JWT and identity`() = runTest {
        val identity = DeviceIdentity("installation-1", "device-1", "profile-1")
        val store = FakeDeviceIdentityStore(
            generatedId = "unused",
            initial = identity,
            initialCredentials = DeviceCredentials(accessToken = "device-jwt"),
        )

        try {
            deviceAuthenticatedRequest<Unit>(store) { throw HttpException(errorResponse<Unit>(403)) }
            fail("Expected forbidden failure")
        } catch (exception: HttpException) {
            assertEquals(403, exception.code())
        }

        assertEquals("device-jwt", store.credentials.value.accessToken)
        assertEquals(identity, store.identity.value)
    }

    @Test fun `409 registration does not overwrite previous ids`() = runTest {
        val previous = DeviceIdentity("installation-1", "device-old", "profile-old")
        val store = FakeDeviceIdentityStore("unused", previous)
        val api = DeviceTestApi().apply { registerResponse = errorResponse(409) }
        val repository = RemoteDeviceRepository(store, DeviceTestProfileStore(), api)

        try {
            repository.registerDevice("profile-new")
            fail("Expected conflict")
        } catch (exception: DeviceLinkException) {
            assertEquals(DeviceLinkFailureReason.INSTALLATION_CONFLICT, exception.reason)
        }
        assertEquals(previous, store.identity.value)
    }

    @Test fun `successful claim keeps installation and persists profile device and profile ids`() = runTest {
        val store = FakeDeviceIdentityStore("installation-1")
        val profileStore = DeviceTestProfileStore()
        val api = DeviceTestApi()
        val repository = RemoteDeviceRepository(store, profileStore, api)

        val claim = repository.claimProfile("483921")

        assertEquals("483921", api.claimRequest?.code)
        assertEquals("installation-1", api.claimRequest?.installationId)
        assertEquals("ANDROID", api.claimRequest?.platform)
        assertEquals("profile-1", claim.profile.id)
        assertEquals("profile-1", profileStore.profile.value?.id)
        assertEquals("profile-1", store.identity.value.profileId)
        assertEquals("device-1", store.identity.value.deviceId)
        assertEquals("installation-1", store.identity.value.installationId)
        assertEquals("claim-jwt", store.credentials.value.accessToken)
    }

    @Test fun `adult profile link code uses local profile id and device bearer`() = runTest {
        val store = FakeDeviceIdentityStore(
            generatedId = "unused",
            initial = DeviceIdentity("installation-1", "device-1", "profile-1"),
            initialCredentials = DeviceCredentials(accessToken = "device-jwt"),
        )
        val api = DeviceTestApi()
        val repository = RemoteDeviceRepository(store, DeviceTestProfileStore(), api)

        val result = repository.createAdultProfileLinkCode()

        assertEquals("profile-1", api.adultLinkCodeProfileId)
        assertEquals("Bearer device-jwt", api.adultLinkCodeAuthorization)
        assertEquals("728341", result.code)
        assertEquals("2099-01-01T00:10:00Z", result.expiresAt)
        assertEquals("ADULT_PROFILE_LINK", result.purpose)
    }

    @Test fun `410 and 409 claims map to controlled reasons without storing a profile`() = runTest {
        for ((status, reason) in listOf(410 to DeviceLinkFailureReason.INVALID_OR_EXPIRED_CODE, 409 to DeviceLinkFailureReason.INSTALLATION_CONFLICT)) {
            val store = FakeDeviceIdentityStore("installation-1")
            val profileStore = DeviceTestProfileStore()
            val api = DeviceTestApi().apply { claimResponse = errorResponse(status) }
            val repository = RemoteDeviceRepository(store, profileStore, api)
            try {
                repository.claimProfile("483921")
                fail("Expected HTTP $status")
            } catch (exception: DeviceLinkException) {
                assertEquals(reason, exception.reason)
            }
            assertNull(profileStore.profile.value)
            assertNull(store.identity.value.deviceId)
            assertNull(store.identity.value.profileId)
            assertEquals("installation-1", store.identity.value.installationId)
        }
    }
}
