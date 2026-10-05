package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.ChildPreferences
import com.juan.habitos.domain.model.DeviceIdentity
import com.juan.habitos.domain.model.DeviceProfileClaim
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.ProfileContext
import com.juan.habitos.domain.model.RegisteredDevice
import com.juan.habitos.domain.repository.ChildPreferencesRepository
import com.juan.habitos.domain.repository.DeviceLinkException
import com.juan.habitos.domain.repository.DeviceLinkFailureReason
import com.juan.habitos.domain.repository.DeviceRepository
import com.juan.habitos.domain.repository.ProfileContextRepository
import com.juan.habitos.domain.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.io.IOException

private class ClaimDeviceRepository : DeviceRepository {
    override val identity = MutableStateFlow(DeviceIdentity(installationId = "installation-1"))
    var failure: Exception? = null
    var claimCalls = 0
    override suspend fun getOrCreateInstallationId() = "installation-1"
    override suspend fun getStoredDeviceId(): String? = identity.value.deviceId
    override suspend fun registerDevice(profileId: String): RegisteredDevice = error("Not used")
    override suspend fun claimProfile(code: String): DeviceProfileClaim {
        claimCalls++
        failure?.let { throw it }
        val profile = Profile("Juana", "fox", 9, id = "profile-1")
        val device = RegisteredDevice("device-1", "profile-1", "installation-1", "ANDROID", true)
        identity.value = DeviceIdentity("installation-1", "device-1", "profile-1")
        return DeviceProfileClaim(device, profile)
    }
}

private class ClaimProfileRepository : ProfileRepository {
    override val profile = MutableStateFlow<Profile?>(Profile("Juana", "fox", 9, id = "profile-1"))
    override val onboardingComplete = MutableStateFlow(false)
    var settingsRefreshCalls = 0
    var settingsFailure: Exception? = null
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
    override suspend fun create(profile: Profile): Profile = error("Not used")
    override suspend fun refresh(): Profile? = profile.value
    override suspend fun update(profile: Profile): Profile = profile
    override suspend fun refreshSettings(): Profile? {
        settingsRefreshCalls++
        settingsFailure?.let { throw it }
        return profile.value
    }
    override suspend fun updateSettings(profile: Profile): Profile = error("Not used")
}

private class ClaimPreferencesRepository : ChildPreferencesRepository {
    override val preferences = MutableStateFlow(ChildPreferences())
    var refreshCalls = 0
    var failure: Exception? = null
    override suspend fun save(preferences: ChildPreferences) { this.preferences.value = preferences }
    override suspend fun refresh(): ChildPreferences {
        refreshCalls++
        failure?.let { throw it }
        return preferences.value
    }
    override suspend fun update(preferences: ChildPreferences): ChildPreferences = error("Not used")
}

private class ClaimContextRepository : ProfileContextRepository {
    override val contexts = MutableStateFlow<List<ProfileContext>>(emptyList())
    var refreshCalls = 0
    var failure: Exception? = null
    override suspend fun refresh(): List<ProfileContext> {
        refreshCalls++
        failure?.let { throw it }
        return contexts.value
    }
    override suspend fun getActiveContexts(): List<ProfileContext> = emptyList()
    override suspend fun create(context: ProfileContext): ProfileContext = error("Not used")
    override suspend fun update(context: ProfileContext): ProfileContext = error("Not used")
    override suspend fun delete(contextId: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceProfileLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `only exactly six digits enable claim`() {
        val vm = viewModel()
        for (invalid in listOf("", "12345", "1234567", "12a456")) {
            vm.code(invalid)
            assertFalse(vm.state.value.valid)
        }
        vm.code("483921")
        assertTrue(vm.state.value.valid)
    }

    @Test fun `successful claim refreshes profile data blocks double submit and navigates once`() = runTest(dispatcher) {
        val device = ClaimDeviceRepository()
        val profile = ClaimProfileRepository()
        val preferences = ClaimPreferencesRepository()
        val contexts = ClaimContextRepository()
        val vm = DeviceProfileLinkViewModel(device, profile, preferences, contexts)
        var navigations = 0
        vm.code("483921")

        vm.submit { navigations++ }
        vm.submit { navigations++ }
        advanceUntilIdle()
        vm.submit { navigations++ }

        assertEquals(1, device.claimCalls)
        assertEquals(1, profile.settingsRefreshCalls)
        assertEquals(1, preferences.refreshCalls)
        assertEquals(1, contexts.refreshCalls)
        assertTrue(profile.onboardingComplete.value)
        assertEquals("profile-1", device.identity.value.profileId)
        assertEquals("device-1", device.identity.value.deviceId)
        assertEquals("installation-1", device.identity.value.installationId)
        assertTrue(vm.state.value.success)
        assertFalse(vm.state.value.submitting)
        assertEquals(1, navigations)
    }

    @Test fun `410 shows invalid or expired message`() = runTest(dispatcher) {
        val device = ClaimDeviceRepository().apply { failure = DeviceLinkException(DeviceLinkFailureReason.INVALID_OR_EXPIRED_CODE, "410") }
        val vm = viewModel(device)
        vm.code("483921")
        vm.submit {}
        advanceUntilIdle()

        assertEquals("El código no es válido o venció. Pedí uno nuevo e intentá nuevamente.", vm.state.value.errorMessage)
        assertFalse(vm.state.value.success)
    }

    @Test fun `409 shows controlled conflict message`() = runTest(dispatcher) {
        val device = ClaimDeviceRepository().apply { failure = DeviceLinkException(DeviceLinkFailureReason.INSTALLATION_CONFLICT, "409") }
        val vm = viewModel(device)
        vm.code("483921")
        vm.submit {}
        advanceUntilIdle()

        assertEquals("No pudimos vincular este dispositivo con ese perfil.", vm.state.value.errorMessage)
        assertNull(device.identity.value.deviceId)
    }

    @Test fun `activity preferences refresh failure never invalidates successful claim`() = runTest(dispatcher) {
        val device = ClaimDeviceRepository()
        val profile = ClaimProfileRepository()
        val preferences = ClaimPreferencesRepository().apply { failure = IOException("offline") }
        val contexts = ClaimContextRepository()
        val vm = DeviceProfileLinkViewModel(device, profile, preferences, contexts)
        var linked = false
        vm.code("483921")
        vm.submit { linked = true }
        advanceUntilIdle()

        assertTrue(linked)
        assertTrue(vm.state.value.success)
        assertEquals("profile-1", device.identity.value.profileId)
        assertEquals("device-1", device.identity.value.deviceId)
        assertTrue(profile.onboardingComplete.value)
        assertEquals(1, contexts.refreshCalls)
    }

    @Test fun `profile context refresh failure never asks for code again`() = runTest(dispatcher) {
        val device = ClaimDeviceRepository()
        val profile = ClaimProfileRepository()
        val contexts = ClaimContextRepository().apply { failure = IOException("offline") }
        val vm = DeviceProfileLinkViewModel(device, profile, ClaimPreferencesRepository(), contexts)
        vm.code("483921")
        vm.submit {}
        advanceUntilIdle()

        assertTrue(vm.state.value.success)
        assertNull(vm.state.value.errorMessage)
        assertEquals("483921", vm.state.value.code)
        assertEquals("profile-1", device.identity.value.profileId)
        assertTrue(profile.onboardingComplete.value)
    }

    private fun viewModel(device: ClaimDeviceRepository = ClaimDeviceRepository()) = DeviceProfileLinkViewModel(
        device,
        ClaimProfileRepository(),
        ClaimPreferencesRepository(),
        ClaimContextRepository(),
    )
}
