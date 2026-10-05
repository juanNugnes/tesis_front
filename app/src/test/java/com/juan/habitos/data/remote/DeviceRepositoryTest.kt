package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.juan.habitos.domain.model.DeviceIdentity
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
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

private class FakeDeviceIdentityStore(
    private val generatedId: String,
    initial: DeviceIdentity = DeviceIdentity(),
) : DeviceIdentityLocalStore {
    override val identity = MutableStateFlow(initial)
    var generationCalls = 0

    override suspend fun getOrCreateInstallationId(): String {
        identity.value.installationId?.let { return it }
        generationCalls++
        identity.value = identity.value.copy(installationId = generatedId)
        return generatedId
    }

    override suspend fun saveLink(profileId: String, deviceId: String) {
        identity.value = identity.value.copy(profileId = profileId, deviceId = deviceId)
    }
}

private class DeviceTestProfileStore(initial: Profile? = null) : ProfileLocalStore {
    override val profile = MutableStateFlow(initial)
    override val onboardingComplete = MutableStateFlow(false)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class DeviceTestApi : HabitsApi {
    var registerResponse: Response<DeviceDto> = Response.success(201, deviceDto())
    var claimResponse: Response<DeviceLinkClaimResponse> = Response.success(DeviceLinkClaimResponse(deviceDto(), profileDto()))
    var registerProfileId: String? = null
    var registerRequest: DeviceCreateRequest? = null
    var claimRequest: DeviceLinkClaimRequest? = null

    override suspend fun registerDevice(profileId: String, request: DeviceCreateRequest): Response<DeviceDto> {
        registerProfileId = profileId
        registerRequest = request
        return registerResponse
    }
    override suspend fun claimDeviceLink(request: DeviceLinkClaimRequest): Response<DeviceLinkClaimResponse> {
        claimRequest = request
        return claimResponse
    }
    override suspend fun createDeviceLinkCode(profileId: String): Response<DeviceLinkCodeDto> = error("Not used")
    override suspend fun createProfile(request: CreateProfileRequest): ProfileDetailDto = error("Not used")
    override suspend fun getProfile(id: String): ProfileDetailDto = error("Not used")
    override suspend fun updateProfile(id: String, request: ProfileUpdateRequest): ProfileDetailDto = error("Not used")
    override suspend fun getProfileSettings(id: String): ProfileSettingsDto = error("Not used")
    override suspend fun updateProfileSettings(id: String, request: ProfileSettingsRequest): ProfileSettingsDto = error("Not used")
    override suspend fun getActivityPreferences(id: String): ActivityPreferencesDto = error("Not used")
    override suspend fun updateActivityPreferences(id: String, request: ActivityPreferencesRequest): ActivityPreferencesDto = error("Not used")
    override suspend fun createProfileContext(profileId: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun getProfileContexts(profileId: String): List<ProfileContextDto> = error("Not used")
    override suspend fun getActiveProfileContexts(profileId: String): List<ProfileContextDto> = error("Not used")
    override suspend fun updateProfileContext(profileId: String, contextId: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun deleteProfileContext(profileId: String, contextId: String) = error("Not used")
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
        val api = DeviceTestApi().apply { registerResponse = Response.success(201, deviceDto()) }
        val repository = RemoteDeviceRepository(store, DeviceTestProfileStore(), api)

        val registered = repository.registerDevice("profile-1")

        assertEquals("profile-1", api.registerProfileId)
        assertEquals("installation-1", api.registerRequest?.installationId)
        assertEquals("ANDROID", api.registerRequest?.platform)
        assertEquals("device-1", registered.id)
        assertEquals("profile-1", store.identity.value.profileId)
        assertEquals("device-1", store.identity.value.deviceId)
        assertEquals("installation-1", store.identity.value.installationId)
        assertEquals("device-1", repository.getStoredDeviceId())
    }

    @Test fun `200 idempotent device registration is also accepted and reused`() = runTest {
        val store = FakeDeviceIdentityStore("installation-1")
        val api = DeviceTestApi().apply { registerResponse = Response.success(200, deviceDto(id = "existing-device")) }
        val repository = RemoteDeviceRepository(store, DeviceTestProfileStore(), api)

        repository.registerDevice("profile-1")

        assertEquals("existing-device", store.identity.value.deviceId)
        assertEquals(1, store.generationCalls)
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
