package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.AutomaticOfferSettings
import com.juan.habitos.domain.repository.AutomaticOfferSettingsRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticOfferSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `loads and saves both adult controls without duplicate writes`() = runTest(dispatcher) {
        val repository = SettingsRepository()
        val vm = AutomaticOfferSettingsViewModel(repository, "profile")
        advanceUntilIdle()
        vm.automaticEnabled(true)
        vm.sharedPhone(true)
        vm.save()
        vm.save()
        advanceUntilIdle()

        assertEquals(1, repository.saveCalls)
        assertEquals(AutomaticOfferSettings(true, true), repository.value)
        assertTrue(vm.state.value.saved)
        assertFalse(vm.state.value.hasChanges)
    }

    @Test fun `failed save keeps adult changes pending`() = runTest(dispatcher) {
        val repository = SettingsRepository().apply { failure = IOException("offline") }
        val vm = AutomaticOfferSettingsViewModel(repository, "profile")
        advanceUntilIdle()
        vm.automaticEnabled(true)
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.hasChanges)
        assertFalse(vm.state.value.saved)
        assertNotNull(vm.state.value.error)
    }

    private class SettingsRepository : AutomaticOfferSettingsRepository {
        var value = AutomaticOfferSettings(false, false)
        var saveCalls = 0
        var failure: Exception? = null
        override suspend fun load(profileId: String) = value
        override suspend fun save(profileId: String, settings: AutomaticOfferSettings): AutomaticOfferSettings {
            saveCalls++
            failure?.let { throw it }
            return settings.also { value = it }
        }
    }
}
