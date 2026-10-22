package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

internal class TestParentalScreenTimeRepository : ParentalScreenTimeRepository {
    val records = mutableMapOf<String, ParentalScreenTimeRecord>()
    val reads = mutableListOf<String>()
    val writes = mutableListOf<Pair<String, ParentalScreenTimeAnswers>>()
    var readFailure: Exception? = null
    var saveFailure: Exception? = null
    var persistBeforeFailure = false
    var profileAge = 9
    override suspend fun latest(profileId: String): ParentalScreenTimeRecord? { reads += profileId; readFailure?.let { throw it }; return records[profileId] }
    override suspend fun save(profileId: String, answers: ParentalScreenTimeAnswers): ParentalScreenTimeRecord {
        writes += profileId to answers
        val record = ParentalScreenTimeRecord("response-${writes.size}", profileId, (records[profileId]?.revision ?: 0) + 1, profileAge, answers)
        if (saveFailure == null || persistBeforeFailure) records[profileId] = record
        saveFailure?.let { throw it }
        return record
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ParentalScreenTimeViewModelTest {
    @Test fun `unpublished latest endpoint is not empty data and cannot trigger POST or lose edits`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository().apply { readFailure = ProfileFormException(ProfileFormFailure.ENDPOINT_UNAVAILABLE) }
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        advanceUntilIdle()
        assertFalse(vm.state.value.loaded)
        assertTrue(vm.state.value.error!!.contains("despliegue"))
        assertFalse(vm.state.value.error!!.contains("perfil ya no"))
        vm.sex(ParentalSexChoice.FEMALE); vm.weekday(ParentalTimeChoice.THREE_FOUR)
        vm.refresh(); advanceUntilIdle(); vm.save(); advanceUntilIdle()
        assertEquals(ParentalSexChoice.FEMALE, vm.state.value.selections.sex)
        assertTrue(repo.writes.isEmpty())
        repo.readFailure = null
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        vm.save(); advanceUntilIdle()
        assertEquals(1, repo.writes.size)
    }
    @Test fun `unloaded authentication failure permits local choices but never permits POST`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository().apply { readFailure = AdultAuthenticationRequiredException() }
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        advanceUntilIdle()
        assertTrue(vm.state.value.editable)
        assertTrue(vm.state.value.authenticationRequired)
        assertEquals(ParentalScreenTimeSelections(), vm.state.value.selections)
        vm.sex(ParentalSexChoice.MALE); vm.weekday(ParentalTimeChoice.THREE_FOUR); vm.weekend(ParentalTimeChoice.ONE_TWO)
        vm.save(); advanceUntilIdle()
        assertEquals(ParentalScreenTimeSelections(ParentalSexChoice.MALE, ParentalTimeChoice.THREE_FOUR, ParentalTimeChoice.ONE_TWO), vm.state.value.selections)
        assertTrue(repo.writes.isEmpty())
        repo.readFailure = null
        vm.refresh(); advanceUntilIdle()
        assertFalse(vm.state.value.authenticationRequired)
        assertTrue(vm.state.value.canSave)
    }

    @Test fun `forbidden and connection errors remain distinct from no previous response`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository().apply { readFailure = AdultAuthorizationException() }
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        advanceUntilIdle()
        assertTrue(vm.state.value.forbidden)
        assertFalse(vm.state.value.authenticationRequired)
        assertFalse(vm.state.value.loaded)
        vm.weekday(ParentalTimeChoice.SEVEN_PLUS)
        assertFalse(vm.state.value.canSave)
        repo.readFailure = java.io.IOException("offline")
        vm.refresh(); advanceUntilIdle()
        assertFalse(vm.state.value.forbidden)
        assertTrue(vm.state.value.error!!.contains("conexión"))
        repo.readFailure = null
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.record)
        assertNull(vm.state.value.error)
        assertEquals(ParentalTimeChoice.SEVEN_PLUS, vm.state.value.selections.weekday)
    }

    @Test fun `reconciliation preserves newer edits when earlier POST was already persisted`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository().apply { saveFailure = java.io.IOException("lost"); persistBeforeFailure = true }
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        advanceUntilIdle()
        vm.sex(ParentalSexChoice.MALE); vm.save(); advanceUntilIdle()
        vm.sex(ParentalSexChoice.FEMALE)
        vm.refresh(); advanceUntilIdle()
        assertEquals(ParentalSexChoice.FEMALE, vm.state.value.selections.sex)
        assertTrue(vm.state.value.canSave)
        assertEquals(1, repo.writes.size)
    }
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `opening empty form never preselects answers or creates revision`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository()
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        vm.sex(ParentalSexChoice.MALE) // ignored while initial query is unresolved
        advanceUntilIdle()
        assertEquals(9, vm.state.value.age)
        assertEquals(ParentalScreenTimeSelections(), vm.state.value.selections)
        repeat(10) { vm.state.value; vm.save() }
        advanceUntilIdle()
        assertEquals(listOf("child"), repo.reads)
        assertTrue(repo.writes.isEmpty())
        assertFalse(vm.state.value.canSave)
    }

    @Test fun `single choices map exact dataset codes and category codes without minutes`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository()
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        advanceUntilIdle()
        vm.sex(ParentalSexChoice.MALE); vm.sex(ParentalSexChoice.FEMALE)
        vm.weekday(ParentalTimeChoice.UNDER_ONE); vm.weekday(ParentalTimeChoice.SEVEN_PLUS)
        vm.weekend(ParentalTimeChoice.THREE_FOUR)
        vm.save(); vm.save()
        assertTrue(vm.state.value.saving)
        vm.sex(ParentalSexChoice.MALE) // do not overwrite data while saving
        advanceUntilIdle()
        assertEquals(listOf("child" to ParentalScreenTimeAnswers(2, 5, 3)), repo.writes)
        assertEquals(1, ParentalSexChoice.MALE.code)
        assertEquals(2, ParentalSexChoice.FEMALE.code)
        assertEquals(listOf(1, 2, 3, 4, 5, null), ParentalTimeChoice.entries.map { it.code })
        assertFalse(vm.state.value.canSave)
    }

    @Test fun `explicit unavailable and omitted answers send null without fabricated selections on reload`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository()
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        advanceUntilIdle()
        vm.sex(ParentalSexChoice.UNAVAILABLE); vm.weekday(ParentalTimeChoice.UNAVAILABLE)
        vm.save(); advanceUntilIdle()
        assertEquals(ParentalScreenTimeAnswers(null, null, null), repo.writes.single().second)
        assertEquals(ParentalSexChoice.UNAVAILABLE, vm.state.value.selections.sex)
        vm.refresh(); advanceUntilIdle(); vm.save(); advanceUntilIdle()
        assertEquals(ParentalScreenTimeSelections(), vm.state.value.selections)
        assertEquals(1, repo.writes.size)
    }

    @Test fun `existing answers restore edit and unchanged normalized answers never post`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository().apply { records["old-child"] = ParentalScreenTimeRecord("old", "old-child", 3, 9, ParentalScreenTimeAnswers(1, 2, null)) }
        val vm = ParentalScreenTimeViewModel(repo, "old-child", 9)
        advanceUntilIdle()
        assertEquals(ParentalSexChoice.MALE, vm.state.value.selections.sex)
        assertEquals(ParentalTimeChoice.ONE_TWO, vm.state.value.selections.weekday)
        assertNull(vm.state.value.selections.weekend)
        vm.weekend(ParentalTimeChoice.UNAVAILABLE); vm.save(); advanceUntilIdle()
        assertTrue(repo.writes.isEmpty())
        vm.weekday(ParentalTimeChoice.FIVE_SIX); vm.save(); advanceUntilIdle()
        assertEquals(ParentalScreenTimeAnswers(1, 4, null), repo.writes.single().second)
    }

    @Test fun `age is read from profile and changing it requires an explicit save not opening a form`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository().apply { records["child"] = ParentalScreenTimeRecord("old", "child", 1, 8, ParentalScreenTimeAnswers(2, 1, 2)) }
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        advanceUntilIdle()
        assertEquals(9, vm.state.value.age)
        assertEquals(8, vm.state.value.record!!.age)
        assertTrue(repo.writes.isEmpty())
        assertFalse(vm.state.value.canSave)
        vm.weekend(ParentalTimeChoice.THREE_FOUR)
        vm.save(); advanceUntilIdle()
        assertEquals(9, vm.state.value.record!!.age)
    }

    @Test fun `lost POST response is reconciled by GET and never blindly resubmitted`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository().apply { saveFailure = java.io.IOException("response lost"); persistBeforeFailure = true }
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        advanceUntilIdle()
        vm.sex(ParentalSexChoice.MALE); vm.save(); advanceUntilIdle()
        assertTrue(vm.state.value.needsReload)
        repeat(5) { vm.save() }; advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        vm.refresh(); vm.refresh(); advanceUntilIdle()
        assertFalse(vm.state.value.needsReload)
        assertFalse(vm.state.value.canSave)
        assertEquals("Hábitos de pantallas guardados.", vm.state.value.message)
        assertEquals(1, repo.writes.size)
    }

    @Test fun `failed save preserves edits and retries only after confirming latest data`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository().apply { saveFailure = java.io.IOException("offline") }
        val vm = ParentalScreenTimeViewModel(repo, "child", 9)
        advanceUntilIdle()
        vm.weekend(ParentalTimeChoice.SEVEN_PLUS); vm.save(); advanceUntilIdle()
        assertEquals(ParentalTimeChoice.SEVEN_PLUS, vm.state.value.selections.weekend)
        repo.readFailure = java.io.IOException("still offline")
        vm.refresh(); advanceUntilIdle(); vm.save(); advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        repo.readFailure = null; repo.saveFailure = null
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        vm.save(); advanceUntilIdle()
        assertEquals(ParentalTimeChoice.SEVEN_PLUS, vm.state.value.selections.weekend)
        assertEquals(2, repo.writes.size)
    }

    @Test fun `authorization errors have local messages and do not permit saving unloaded data`() = runTest(dispatcher) {
        for (error in listOf(AdultAuthenticationRequiredException(), AdultAuthorizationException(), java.io.IOException("offline"))) {
            val repo = TestParentalScreenTimeRepository().apply { readFailure = error }
            val vm = ParentalScreenTimeViewModel(repo, "child", 9)
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.canSave)
            vm.save(); advanceUntilIdle()
            assertTrue(repo.writes.isEmpty())
        }
    }

    @Test fun `profiles keep independent answers and an old async result cannot populate another profile`() = runTest(dispatcher) {
        val repo = TestParentalScreenTimeRepository().apply { records["a"] = ParentalScreenTimeRecord("a1", "a", 1, 9, ParentalScreenTimeAnswers(1, 5, 4)) }
        val a = ParentalScreenTimeViewModel(repo, "a", 9)
        val b = ParentalScreenTimeViewModel(repo, "b", 10)
        advanceUntilIdle()
        assertEquals(ParentalTimeChoice.SEVEN_PLUS, a.state.value.selections.weekday)
        assertEquals(ParentalScreenTimeSelections(), b.state.value.selections)
        assertEquals("b", b.state.value.profileId)
        assertTrue(repo.writes.isEmpty())
    }
}
