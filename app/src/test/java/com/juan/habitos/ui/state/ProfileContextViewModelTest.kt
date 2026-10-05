package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.ProfileContext
import com.juan.habitos.domain.model.ProfileContextType
import com.juan.habitos.domain.repository.ProfileContextRepository
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
import java.time.LocalDate

private class TestProfileContextRepository(initial: List<ProfileContext> = emptyList()) : ProfileContextRepository {
    override val contexts = MutableStateFlow(initial)
    var refreshed = initial
    var active = emptyList<ProfileContext>()
    var failureOn: String? = null
    var refreshCalls = 0
    var activeCalls = 0
    var createCalls = 0
    var updateCalls = 0
    var deleteCalls = 0
    var updatedContext: ProfileContext? = null
    var deletedId: String? = null

    override suspend fun refresh(): List<ProfileContext> {
        refreshCalls++
        failIf("get")
        contexts.value = refreshed
        return refreshed
    }

    override suspend fun getActiveContexts(): List<ProfileContext> {
        activeCalls++
        failIf("active")
        return active
    }

    override suspend fun create(context: ProfileContext): ProfileContext {
        createCalls++
        failIf("create")
        val saved = context.copy(id = "created")
        contexts.value = contexts.value + saved
        return saved
    }

    override suspend fun update(context: ProfileContext): ProfileContext {
        updateCalls++
        failIf("update")
        updatedContext = context
        contexts.value = contexts.value.map { if (it.id == context.id) context else it }
        return context
    }

    override suspend fun delete(contextId: String) {
        deleteCalls++
        deletedId = contextId
        failIf("delete")
        contexts.value = contexts.value.filterNot { it.id == contextId }
    }

    private fun failIf(operation: String) { if (failureOn == operation) throw IOException("offline") }
}

private fun viewModelContext(
    id: String = "context-1",
    type: ProfileContextType = ProfileContextType.VACATION,
    start: LocalDate = LocalDate.of(2026, 10, 1),
    end: LocalDate? = LocalDate.of(2026, 10, 7),
) = ProfileContext(id, type, start, end, "Detalle")

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileContextViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial load shows cache then refreshes contexts and active ids`() = runTest(dispatcher) {
        val cached = viewModelContext(id = "cached")
        val remote = viewModelContext(id = "remote", type = ProfileContextType.SCHOOL_ACTIVITY)
        val repository = TestProfileContextRepository(listOf(cached)).apply {
            refreshed = listOf(remote)
            active = listOf(remote)
        }

        val viewModel = ProfileContextViewModel(repository, demoMode = false)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.loaded)
        assertFalse(viewModel.state.value.refreshing)
        assertEquals(listOf("remote"), viewModel.state.value.contexts.map(ProfileContext::id))
        assertEquals(setOf("remote"), viewModel.state.value.activeContextIds)
        assertEquals(1, repository.refreshCalls)
        assertEquals(1, repository.activeCalls)
    }

    @Test fun `demo initial load uses cache without remote refresh`() = runTest(dispatcher) {
        val cached = viewModelContext()
        val repository = TestProfileContextRepository(listOf(cached))
        val viewModel = ProfileContextViewModel(repository, demoMode = true)
        advanceUntilIdle()
        assertEquals(listOf(cached), viewModel.state.value.contexts)
        assertEquals(0, repository.refreshCalls)
        assertEquals(0, repository.activeCalls)
    }

    @Test fun `create closes form and appends saved context`() = runTest(dispatcher) {
        val repository = TestProfileContextRepository()
        val viewModel = ProfileContextViewModel(repository, demoMode = true)
        advanceUntilIdle()
        viewModel.beginCreate()
        viewModel.type(ProfileContextType.OTHER)
        viewModel.startDate(LocalDate.of(2026, 10, 3))
        viewModel.endDate(null)
        viewModel.description("Detalle opcional")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.createCalls)
        assertEquals("created", viewModel.state.value.contexts.single().id)
        assertEquals(ProfileContextType.OTHER, viewModel.state.value.contexts.single().type)
        assertNull(viewModel.state.value.form)
        assertTrue(viewModel.state.value.saved)
    }

    @Test fun `edit replaces existing context instead of adding another`() = runTest(dispatcher) {
        val original = viewModelContext()
        val repository = TestProfileContextRepository(listOf(original))
        val viewModel = ProfileContextViewModel(repository, demoMode = true)
        advanceUntilIdle()
        viewModel.beginEdit(original)
        viewModel.type(ProfileContextType.ROUTINE_CHANGE)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.updateCalls)
        assertEquals("context-1", repository.updatedContext?.id)
        assertEquals(1, viewModel.state.value.contexts.size)
        assertEquals(ProfileContextType.ROUTINE_CHANGE, viewModel.state.value.contexts.single().type)
    }

    @Test fun `delete removes context only after repository success`() = runTest(dispatcher) {
        val item = viewModelContext()
        val repository = TestProfileContextRepository(listOf(item))
        val viewModel = ProfileContextViewModel(repository, demoMode = true)
        advanceUntilIdle()
        viewModel.delete("context-1")
        advanceUntilIdle()

        assertEquals(1, repository.deleteCalls)
        assertEquals("context-1", repository.deletedId)
        assertTrue(viewModel.state.value.contexts.isEmpty())
        assertNull(viewModel.state.value.deletingId)
    }

    @Test fun `get error keeps cached list and exposes friendly message`() = runTest(dispatcher) {
        val cached = viewModelContext()
        val repository = TestProfileContextRepository(listOf(cached)).apply { failureOn = "get" }
        val viewModel = ProfileContextViewModel(repository, demoMode = false)
        advanceUntilIdle()
        assertEquals(listOf(cached), viewModel.state.value.contexts)
        assertEquals("No pudimos actualizar los contextos.", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test fun `post error keeps form and cached list for retry`() = runTest(dispatcher) {
        val cached = viewModelContext()
        val repository = TestProfileContextRepository(listOf(cached)).apply { failureOn = "create" }
        val viewModel = ProfileContextViewModel(repository, demoMode = true)
        advanceUntilIdle()
        viewModel.beginCreate()
        viewModel.save()
        advanceUntilIdle()
        assertEquals(listOf(cached), viewModel.state.value.contexts)
        assertNotNull(viewModel.state.value.form)
        assertEquals("No pudimos guardar el contexto. Intentá nuevamente.", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.submitting)
    }

    @Test fun `put error keeps previous item and edit form`() = runTest(dispatcher) {
        val original = viewModelContext()
        val repository = TestProfileContextRepository(listOf(original)).apply { failureOn = "update" }
        val viewModel = ProfileContextViewModel(repository, demoMode = true)
        advanceUntilIdle()
        viewModel.beginEdit(original)
        viewModel.type(ProfileContextType.ILLNESS)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(original, viewModel.state.value.contexts.single())
        assertEquals(ProfileContextType.ILLNESS, viewModel.state.value.form?.type)
        assertEquals("No pudimos guardar el contexto. Intentá nuevamente.", viewModel.state.value.errorMessage)
    }

    @Test fun `delete error keeps item and allows retry`() = runTest(dispatcher) {
        val original = viewModelContext()
        val repository = TestProfileContextRepository(listOf(original)).apply { failureOn = "delete" }
        val viewModel = ProfileContextViewModel(repository, demoMode = true)
        advanceUntilIdle()
        viewModel.delete("context-1")
        advanceUntilIdle()
        assertEquals(listOf(original), viewModel.state.value.contexts)
        assertNull(viewModel.state.value.deletingId)
        assertEquals("No pudimos eliminar el contexto. Intentá nuevamente.", viewModel.state.value.errorMessage)
    }

    @Test fun `double submit is blocked synchronously`() = runTest(dispatcher) {
        val repository = TestProfileContextRepository()
        val viewModel = ProfileContextViewModel(repository, demoMode = true)
        advanceUntilIdle()
        viewModel.beginCreate()
        viewModel.save()
        viewModel.save()
        advanceUntilIdle()
        assertEquals(1, repository.createCalls)
    }

    @Test fun `end before start is rejected while null and same day are valid`() = runTest(dispatcher) {
        val repository = TestProfileContextRepository()
        val viewModel = ProfileContextViewModel(repository, demoMode = true)
        advanceUntilIdle()
        viewModel.beginCreate()
        val start = LocalDate.of(2026, 10, 10)
        viewModel.startDate(start)
        viewModel.endDate(start.minusDays(1))
        assertFalse(viewModel.state.value.form!!.valid)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(0, repository.createCalls)

        viewModel.endDate(start)
        assertTrue(viewModel.state.value.form!!.valid)
        viewModel.endDate(null)
        assertTrue(viewModel.state.value.form!!.valid)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(1, repository.createCalls)
        assertNull(viewModel.state.value.contexts.single().endDate)
    }
}
