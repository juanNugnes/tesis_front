package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.ProfileContext
import com.juan.habitos.domain.model.ProfileContextType
import com.juan.habitos.domain.repository.ProfileContextLocalStore
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

private class FakeProfileContextStore(initial: List<ProfileContext> = emptyList()) : ProfileContextLocalStore {
    override val contexts = MutableStateFlow(initial)
    override suspend fun save(contexts: List<ProfileContext>) { this.contexts.value = contexts }
}

private class FakeContextProfileStore(initial: Profile?) : ProfileLocalStore {
    override val profile = MutableStateFlow(initial)
    override val onboardingComplete = MutableStateFlow(false)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class FakeProfileContextApi : HabitsApi {
    var contextsResponse: List<ProfileContextDto> = emptyList()
    var activeResponse: List<ProfileContextDto> = emptyList()
    var createResponse: ProfileContextDto = contextDto("created")
    var updateResponse: ProfileContextDto = contextDto("updated")
    var failureOn: String? = null
    var getCalls = 0
    var activeCalls = 0
    var createCalls = 0
    var updateCalls = 0
    var deleteCalls = 0
    var profileId: String? = null
    var contextId: String? = null
    var request: ProfileContextRequest? = null

    override suspend fun getProfileContexts(profileId: String): List<ProfileContextDto> {
        getCalls++
        this.profileId = profileId
        failIf("get")
        return contextsResponse
    }

    override suspend fun getActiveProfileContexts(profileId: String): List<ProfileContextDto> {
        activeCalls++
        this.profileId = profileId
        failIf("active")
        return activeResponse
    }

    override suspend fun createProfileContext(profileId: String, request: ProfileContextRequest): ProfileContextDto {
        createCalls++
        this.profileId = profileId
        this.request = request
        failIf("create")
        return createResponse
    }

    override suspend fun updateProfileContext(profileId: String, contextId: String, request: ProfileContextRequest): ProfileContextDto {
        updateCalls++
        this.profileId = profileId
        this.contextId = contextId
        this.request = request
        failIf("update")
        return updateResponse
    }

    override suspend fun deleteProfileContext(profileId: String, contextId: String) {
        deleteCalls++
        this.profileId = profileId
        this.contextId = contextId
        failIf("delete")
    }
    override suspend fun registerDevice(profileId: String, request: DeviceCreateRequest): retrofit2.Response<DeviceDto> = error("Not used")
    override suspend fun createDeviceLinkCode(profileId: String): retrofit2.Response<DeviceLinkCodeDto> = error("Not used")
    override suspend fun claimDeviceLink(request: DeviceLinkClaimRequest): retrofit2.Response<DeviceLinkClaimResponse> = error("Not used")

    private fun failIf(operation: String) { if (failureOn == operation) throw IOException("offline") }

    override suspend fun createProfile(request: CreateProfileRequest): ProfileDetailDto = error("Not used")
    override suspend fun getProfile(id: String): ProfileDetailDto = error("Not used")
    override suspend fun updateProfile(id: String, request: ProfileUpdateRequest): ProfileDetailDto = error("Not used")
    override suspend fun getProfileSettings(id: String): ProfileSettingsDto = error("Not used")
    override suspend fun updateProfileSettings(id: String, request: ProfileSettingsRequest): ProfileSettingsDto = error("Not used")
    override suspend fun getActivityPreferences(id: String): ActivityPreferencesDto = error("Not used")
    override suspend fun updateActivityPreferences(id: String, request: ActivityPreferencesRequest): ActivityPreferencesDto = error("Not used")
    override suspend fun postUsage(id: String, usage: UsageDto) = usage
    override suspend fun getUsage(id: String) = UsageDto(id, 0, 0)
    override suspend fun postAnalysis(id: String) = AnalysisDto(id, "")
    override suspend fun latestAnalysis(id: String) = AnalysisDto(id, "")
}

private fun contextDto(
    id: String,
    type: String = "VACATION",
    startDate: String = "2026-10-01",
    endDate: String? = "2026-10-07",
    description: String? = "Viaje familiar",
) = ProfileContextDto(id, "profile-123", type, startDate, endDate, description)

private fun context(
    id: String? = "context-1",
    type: ProfileContextType = ProfileContextType.VACATION,
    startDate: LocalDate = LocalDate.of(2026, 10, 1),
    endDate: LocalDate? = LocalDate.of(2026, 10, 7),
    description: String? = "Viaje familiar",
) = ProfileContext(id, type, startDate, endDate, description)

class ProfileContextRepositoryTest {
    @Test fun `all context types map explicitly in both directions`() {
        val backendTypes = listOf("VACATION", "ILLNESS", "SCHOOL_ACTIVITY", "ROUTINE_CHANGE", "OTHER")
        ProfileContextType.entries.zip(backendTypes).forEach { (domainType, backendType) ->
            assertEquals(backendType, context(type = domainType).toProfileContextRequest().type)
            assertEquals(domainType, contextDto("id", type = backendType).toDomainContext().type)
        }
    }

    @Test fun `mapper preserves nullable fields and ISO single day and date ranges`() {
        val singleDay = context(endDate = LocalDate.of(2026, 10, 1), description = null).toProfileContextRequest()
        assertEquals("2026-10-01", singleDay.startDate)
        assertEquals("2026-10-01", singleDay.endDate)
        assertNull(singleDay.description)

        val openEnded = context(endDate = null, description = null).toProfileContextRequest()
        assertNull(openEnded.endDate)
        assertNull(contextDto("id", endDate = null, description = null).toDomainContext().endDate)

        val json = Gson().toJson(context().toProfileContextRequest())
        assertTrue(json.contains("\"start_date\":\"2026-10-01\""))
        assertTrue(json.contains("\"end_date\":\"2026-10-07\""))
        assertFalse(json.contains("startDate"))
    }

    @Test fun `refresh with profile id calls api and replaces cache`() = runTest {
        val cached = listOf(context(id = "old"))
        val store = FakeProfileContextStore(cached)
        val api = FakeProfileContextApi().apply {
            contextsResponse = listOf(contextDto("new", type = "SCHOOL_ACTIVITY", startDate = "2026-10-03", endDate = null, description = "Campamento"))
        }
        val repository = repository(store, api)

        val result = repository.refresh()

        assertEquals(1, api.getCalls)
        assertEquals("profile-123", api.profileId)
        assertEquals("new", result.single().id)
        assertEquals(ProfileContextType.SCHOOL_ACTIVITY, store.contexts.value.single().type)
    }

    @Test fun `refresh error preserves cache`() = runTest {
        val cached = listOf(context())
        val store = FakeProfileContextStore(cached)
        val api = FakeProfileContextApi().apply { failureOn = "get" }
        val repository = repository(store, api)

        try { repository.refresh(); fail("Expected GET failure") } catch (_: IOException) {}

        assertEquals(cached, store.contexts.value)
    }

    @Test fun `missing profile id and demo mode never call api`() = runTest {
        val cached = listOf(context())
        val missingIdStore = FakeProfileContextStore(cached)
        val missingIdApi = FakeProfileContextApi()
        val missingIdRepository = CachedProfileContextRepository(missingIdStore, FakeContextProfileStore(Profile("Sofía", "🦊", 9)), missingIdApi, true)
        assertEquals(cached, missingIdRepository.refresh())
        val localCreated = missingIdRepository.create(context(id = null))
        assertNotNull(localCreated.id)
        assertEquals(0, missingIdApi.getCalls + missingIdApi.createCalls)

        val demoStore = FakeProfileContextStore(cached)
        val demoApi = FakeProfileContextApi()
        val demoRepository = CachedProfileContextRepository(demoStore, FakeContextProfileStore(Profile("Sofía", "🦊", 9, id = "profile-123")), demoApi, false)
        demoRepository.refresh()
        demoRepository.getActiveContexts()
        demoRepository.create(context(id = null))
        demoRepository.update(context())
        demoRepository.delete("context-1")
        assertEquals(0, demoApi.getCalls + demoApi.activeCalls + demoApi.createCalls + demoApi.updateCalls + demoApi.deleteCalls)
    }

    @Test fun `active endpoint uses persisted profile id without local recalculation`() = runTest {
        val api = FakeProfileContextApi().apply { activeResponse = listOf(contextDto("active", endDate = null)) }
        val result = repository(FakeProfileContextStore(), api).getActiveContexts()
        assertEquals(1, api.activeCalls)
        assertEquals("profile-123", api.profileId)
        assertEquals(listOf("active"), result.map(ProfileContext::id))
    }

    @Test fun `create sends request and caches response only after success`() = runTest {
        val previous = listOf(context(id = "old"))
        val store = FakeProfileContextStore(previous)
        val api = FakeProfileContextApi().apply { createResponse = contextDto("new", type = "OTHER", endDate = null, description = null) }
        val repository = repository(store, api)
        val candidate = context(id = null, type = ProfileContextType.OTHER, endDate = null, description = null)

        val created = repository.create(candidate)

        assertEquals(1, api.createCalls)
        assertEquals("profile-123", api.profileId)
        assertEquals("OTHER", api.request?.type)
        assertNull(api.request?.endDate)
        assertEquals("new", created.id)
        assertTrue(store.contexts.value.any { it.id == "new" })

        val stable = store.contexts.value
        api.failureOn = "create"
        try { repository.create(candidate); fail("Expected POST failure") } catch (_: IOException) {}
        assertEquals(stable, store.contexts.value)
    }

    @Test fun `update uses context id and replaces only matching cached item after success`() = runTest {
        val original = context(id = "context-1")
        val untouched = context(id = "context-2", type = ProfileContextType.ILLNESS)
        val store = FakeProfileContextStore(listOf(original, untouched))
        val api = FakeProfileContextApi().apply { updateResponse = contextDto("context-1", type = "ROUTINE_CHANGE", description = "Nuevo horario") }
        val repository = repository(store, api)

        repository.update(original.copy(type = ProfileContextType.ROUTINE_CHANGE, description = "Nuevo horario"))

        assertEquals("profile-123", api.profileId)
        assertEquals("context-1", api.contextId)
        assertEquals("ROUTINE_CHANGE", api.request?.type)
        assertEquals(ProfileContextType.ROUTINE_CHANGE, store.contexts.value.first { it.id == "context-1" }.type)
        assertEquals(untouched, store.contexts.value.first { it.id == "context-2" })

        val stable = store.contexts.value
        api.failureOn = "update"
        try { repository.update(original); fail("Expected PUT failure") } catch (_: IOException) {}
        assertEquals(stable, store.contexts.value)
    }

    @Test fun `delete uses both ids and removes cache only after success`() = runTest {
        val first = context(id = "context-1")
        val second = context(id = "context-2", type = ProfileContextType.OTHER)
        val store = FakeProfileContextStore(listOf(first, second))
        val api = FakeProfileContextApi()
        val repository = repository(store, api)

        repository.delete("context-1")

        assertEquals("profile-123", api.profileId)
        assertEquals("context-1", api.contextId)
        assertEquals(listOf("context-2"), store.contexts.value.map(ProfileContext::id))

        val stable = store.contexts.value
        api.failureOn = "delete"
        try { repository.delete("context-2"); fail("Expected DELETE failure") } catch (_: IOException) {}
        assertEquals(stable, store.contexts.value)
    }

    private fun repository(store: FakeProfileContextStore, api: FakeProfileContextApi) = CachedProfileContextRepository(
        store,
        FakeContextProfileStore(Profile("Sofía", "🦊", 9, id = "profile-123")),
        api,
        remoteEnabled = true,
    )
}
