package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.BedtimeRange
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.SchoolSchedule
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

private class EditProfileTestRepository(initial: Profile?) : ProfileRepository {
    override val profile = MutableStateFlow(initial)
    override val onboardingComplete = MutableStateFlow(false)
    var saveCalls = 0
    var updateCalls = 0
    var updateFailure: Exception? = null
    var updateCandidate: Profile? = null

    override suspend fun save(profile: Profile) {
        saveCalls++
        this.profile.value = profile
    }

    override suspend fun update(profile: Profile): Profile {
        updateCalls++
        updateCandidate = profile
        updateFailure?.let { throw it }
        this.profile.value = profile
        return profile
    }

    override suspend fun create(profile: Profile): Profile = error("Not used")
    override suspend fun refresh(): Profile? = profile.value
    override suspend fun refreshSettings(): Profile? = profile.value
    override suspend fun updateSettings(profile: Profile): Profile = error("Not used")
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private fun editableProfile() = Profile(
    name = "Sofía",
    avatar = "🦊",
    age = 9,
    schoolSchedules = setOf(SchoolSchedule.MORNING, SchoolSchedule.AFTERNOON),
    bedtime = BedtimeRange.FROM_22_TO_23,
    schoolStart = "08:00",
    schoolEnd = "12:30",
    sleepStart = "22:00",
    sleepEnd = "07:00",
    id = "profile-123",
    active = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `loads current name age and avatar`() = runTest(dispatcher) {
        val profile = editableProfile()
        val viewModel = EditProfileViewModel(EditProfileTestRepository(profile), demoMode = false)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.loaded)
        assertEquals("Sofía", viewModel.state.value.name)
        assertEquals(9, viewModel.state.value.age)
        assertEquals("🦊", viewModel.state.value.avatar)
    }

    @Test fun `blank name and ages outside six to twelve are invalid`() = runTest(dispatcher) {
        val viewModel = EditProfileViewModel(EditProfileTestRepository(editableProfile()), demoMode = true)
        advanceUntilIdle()
        viewModel.name("   ")
        assertFalse(viewModel.state.value.valid)
        viewModel.name("Sofía")
        viewModel.age(5)
        assertFalse(viewModel.state.value.valid)
        viewModel.age(13)
        assertFalse(viewModel.state.value.valid)
        viewModel.age(6)
        assertTrue(viewModel.state.value.valid)
        viewModel.age(12)
        assertTrue(viewModel.state.value.valid)
    }

    @Test fun `remote save updates only editable fields and preserves the rest`() = runTest(dispatcher) {
        val original = editableProfile()
        val repository = EditProfileTestRepository(original)
        val viewModel = EditProfileViewModel(repository, demoMode = false)
        advanceUntilIdle()
        viewModel.name("  Juana  ")
        viewModel.age(10)
        viewModel.avatar("🐸")
        var completed = false
        viewModel.save { completed = true }
        advanceUntilIdle()

        val saved = repository.profile.value!!
        assertEquals(1, repository.updateCalls)
        assertEquals(0, repository.saveCalls)
        assertEquals("Juana", saved.name)
        assertEquals(10, saved.age)
        assertEquals("🐸", saved.avatar)
        assertEquals(original.id, saved.id)
        assertEquals(original.active, saved.active)
        assertEquals(original.schoolSchedules, saved.schoolSchedules)
        assertEquals(original.bedtime, saved.bedtime)
        assertEquals(original.schoolStart, saved.schoolStart)
        assertEquals(original.schoolEnd, saved.schoolEnd)
        assertEquals(original.sleepStart, saved.sleepStart)
        assertEquals(original.sleepEnd, saved.sleepEnd)
        assertTrue(viewModel.state.value.saved)
        assertTrue(completed)
    }

    @Test fun `remote failure preserves cache and keeps edits for retry`() = runTest(dispatcher) {
        val original = editableProfile()
        val repository = EditProfileTestRepository(original).apply { updateFailure = IOException("offline") }
        val viewModel = EditProfileViewModel(repository, demoMode = false)
        advanceUntilIdle()
        viewModel.name("Editado")
        viewModel.age(11)
        viewModel.avatar("🐨")
        var completed = false
        viewModel.save { completed = true }
        advanceUntilIdle()

        assertEquals(original, repository.profile.value)
        assertEquals("Editado", viewModel.state.value.name)
        assertEquals(11, viewModel.state.value.age)
        assertEquals("🐨", viewModel.state.value.avatar)
        assertFalse(viewModel.state.value.saved)
        assertFalse(viewModel.state.value.submitting)
        assertEquals("No pudimos guardar los cambios. Intentá nuevamente.", viewModel.state.value.errorMessage)
        assertFalse(completed)
    }

    @Test fun `double submit is blocked synchronously`() = runTest(dispatcher) {
        val repository = EditProfileTestRepository(editableProfile())
        val viewModel = EditProfileViewModel(repository, demoMode = false)
        advanceUntilIdle()
        viewModel.save()
        viewModel.save()
        advanceUntilIdle()
        assertEquals(1, repository.updateCalls)
    }

    @Test fun `demo mode saves locally and never invokes remote update`() = runTest(dispatcher) {
        val repository = EditProfileTestRepository(editableProfile())
        val viewModel = EditProfileViewModel(repository, demoMode = true)
        advanceUntilIdle()
        viewModel.name("Demo")
        viewModel.save()
        advanceUntilIdle()
        assertEquals(1, repository.saveCalls)
        assertEquals(0, repository.updateCalls)
        assertEquals("Demo", repository.profile.value?.name)
    }

    @Test fun `missing profile is handled without submitting`() = runTest(dispatcher) {
        val repository = EditProfileTestRepository(null)
        val viewModel = EditProfileViewModel(repository, demoMode = false)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.loaded)
        assertFalse(viewModel.state.value.valid)
        assertNotNull(viewModel.state.value.errorMessage)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(0, repository.updateCalls)
    }
}
