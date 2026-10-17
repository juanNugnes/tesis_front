package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.AdultManagedDevice
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.AdultDeviceNotFoundException
import com.juan.habitos.domain.repository.AdultDeviceRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

private class TestAdultDeviceRepository : AdultDeviceRepository {
    val byProfile = mutableMapOf<String, List<AdultManagedDevice>>()
    var listFailure: Exception? = null
    var deactivateFailure: Exception? = null
    var listCalls = 0
    var deactivateCalls = 0
    var lastProfileId: String? = null
    var lastDeviceId: String? = null

    override suspend fun devices(profileId: String): List<AdultManagedDevice> {
        listCalls++
        lastProfileId = profileId
        listFailure?.let { throw it }
        return byProfile[profileId].orEmpty()
    }

    override suspend fun deactivate(profileId: String, deviceId: String): AdultManagedDevice {
        deactivateCalls++
        lastProfileId = profileId
        lastDeviceId = deviceId
        deactivateFailure?.let { throw it }
        val updated = byProfile[profileId].orEmpty().first { it.id == deviceId }.copy(active = false)
        byProfile[profileId] = byProfile[profileId].orEmpty().map { if (it.id == deviceId) updated else it }
        return updated
    }
}

private fun managedDevice(
    id: String,
    active: Boolean = true,
    profileId: String = "profile-1",
    platform: String = "ANDROID",
) = AdultManagedDevice(
    id = id,
    profileId = profileId,
    platform = platform,
    active = active,
    lastSeenAt = "2026-10-16T18:25:00Z",
)

@OptIn(ExperimentalCoroutinesApi::class)
class LinkedDevicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `successful list exposes active inactive and neutral duplicate labels`() = runTest(dispatcher) {
        val repository = TestAdultDeviceRepository().apply {
            byProfile["profile-1"] = listOf(managedDevice("device-secret-1"), managedDevice("device-secret-2", active = false))
        }
        val viewModel = LinkedDevicesViewModel(repository, "profile-1", demoMode = false)
        advanceUntilIdle()

        assertEquals(listOf("Dispositivo Android 1", "Dispositivo Android 2"), viewModel.state.value.devices.map { it.title })
        assertEquals(listOf(true, false), viewModel.state.value.devices.map { it.active })
        assertTrue(viewModel.state.value.devices.first().canDeactivate)
        assertFalse(viewModel.state.value.devices.last().canDeactivate)
    }

    @Test fun `empty list has completed loading state`() = runTest(dispatcher) {
        val viewModel = LinkedDevicesViewModel(TestAdultDeviceRepository(), "profile-1", demoMode = false)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.loading)
        assertTrue(viewModel.state.value.devices.isEmpty())
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test fun `cancel confirmation never calls repository`() = runTest(dispatcher) {
        val repository = TestAdultDeviceRepository().apply { byProfile["profile-1"] = listOf(managedDevice("device-1")) }
        val viewModel = LinkedDevicesViewModel(repository, "profile-1", demoMode = false)
        advanceUntilIdle()

        viewModel.requestDeactivate("device-1")
        assertEquals("device-1", viewModel.state.value.confirmationDeviceId)
        viewModel.cancelDeactivate()
        advanceUntilIdle()

        assertNull(viewModel.state.value.confirmationDeviceId)
        assertEquals(0, repository.deactivateCalls)
    }

    @Test fun `confirmed deactivation is single flight and keeps inactive device visible`() = runTest(dispatcher) {
        val repository = TestAdultDeviceRepository().apply { byProfile["profile-1"] = listOf(managedDevice("device-1")) }
        val viewModel = LinkedDevicesViewModel(repository, "profile-1", demoMode = false)
        advanceUntilIdle()

        viewModel.requestDeactivate("device-1")
        viewModel.confirmDeactivate()
        viewModel.confirmDeactivate()
        advanceUntilIdle()

        assertEquals(1, repository.deactivateCalls)
        assertFalse(viewModel.state.value.devices.single().active)
        assertFalse(viewModel.state.value.devices.single().canDeactivate)
        assertNotNull(viewModel.state.value.successMessage)
        assertNull(viewModel.state.value.confirmationDeviceId)
    }

    @Test fun `network 401 403 and 404 produce distinct safe states`() = runTest(dispatcher) {
        val network = TestAdultDeviceRepository().apply { listFailure = IOException("offline token=secret") }
        val networkVm = LinkedDevicesViewModel(network, "profile-1", false)
        advanceUntilIdle()
        assertEquals("No pudimos cargar los dispositivos. Revisá la conexión e intentá nuevamente.", networkVm.state.value.errorMessage)

        val expired = TestAdultDeviceRepository().apply { listFailure = AdultAuthenticationRequiredException() }
        val expiredVm = LinkedDevicesViewModel(expired, "profile-1", false)
        advanceUntilIdle()
        assertTrue(expiredVm.state.value.adultAuthenticationRequired)

        val forbidden = TestAdultDeviceRepository().apply { listFailure = AdultAuthorizationException() }
        val forbiddenVm = LinkedDevicesViewModel(forbidden, "profile-1", false)
        advanceUntilIdle()
        assertEquals("No tenés permiso para administrar los dispositivos de este perfil.", forbiddenVm.state.value.errorMessage)

        val missing = TestAdultDeviceRepository().apply {
            byProfile["profile-1"] = listOf(managedDevice("device-1"))
            deactivateFailure = AdultDeviceNotFoundException()
        }
        val missingVm = LinkedDevicesViewModel(missing, "profile-1", false)
        advanceUntilIdle()
        missingVm.requestDeactivate("device-1")
        missingVm.confirmDeactivate()
        advanceUntilIdle()
        assertEquals("El dispositivo ya no está disponible. Actualizá el listado.", missingVm.state.value.errorMessage)
        assertTrue(missingVm.state.value.devices.single().active)
    }

    @Test fun `profile change starts isolated state and never shows previous devices`() = runTest(dispatcher) {
        val repository = TestAdultDeviceRepository().apply {
            byProfile["profile-1"] = listOf(managedDevice("device-one", profileId = "profile-1"))
            byProfile["profile-2"] = listOf(managedDevice("device-two", profileId = "profile-2", platform = "IOS"))
        }
        val first = LinkedDevicesViewModel(repository, "profile-1", false)
        advanceUntilIdle()
        val second = LinkedDevicesViewModel(repository, "profile-2", false)
        advanceUntilIdle()

        assertEquals("Dispositivo Android", first.state.value.devices.single().title)
        assertEquals("Dispositivo iOS", second.state.value.devices.single().title)
        assertEquals("profile-2", repository.lastProfileId)
    }

    @Test fun `demo data is read only and identifiers are absent from visible values`() = runTest(dispatcher) {
        val repository = TestAdultDeviceRepository().apply {
            byProfile["profile-1"] = listOf(managedDevice("internal-device-id"))
        }
        val viewModel = LinkedDevicesViewModel(repository, "profile-1", demoMode = true)
        advanceUntilIdle()
        val item = viewModel.state.value.devices.single()
        val visibleText = listOf(item.title, item.lastActivity, if (item.active) "Activo" else "Inactivo").joinToString(" ")

        assertTrue(viewModel.state.value.demoMode)
        assertFalse(item.canDeactivate)
        viewModel.requestDeactivate("internal-device-id")
        viewModel.confirmDeactivate()
        advanceUntilIdle()
        assertEquals(0, repository.deactivateCalls)
        assertFalse(visibleText.contains("internal-device-id"))
        assertFalse(visibleText.contains("installation", ignoreCase = true))
    }
}
