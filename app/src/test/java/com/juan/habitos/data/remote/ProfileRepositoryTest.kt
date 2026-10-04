package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.SchoolSchedule
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

private class FakeProfileLocalStore(initial: Profile? = null) : ProfileLocalStore {
    override val profile = MutableStateFlow(initial)
    override val onboardingComplete = MutableStateFlow(false)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class FakeHabitsApi : HabitsApi {
    var createRequest: CreateProfileRequest? = null
    var updateRequest: ProfileUpdateRequest? = null
    var settingsRequest: ProfileSettingsRequest? = null
    var requestedId: String? = null
    var settingsRequestedId: String? = null
    var settingsGetCalls = 0
    var settingsPutCalls = 0
    var failure: Exception? = null
    lateinit var response: ProfileDetailDto
    lateinit var settingsResponse: ProfileSettingsDto

    override suspend fun createProfile(request: CreateProfileRequest): ProfileDetailDto {
        failure?.let { throw it }
        createRequest = request
        return response
    }

    override suspend fun getProfile(id: String): ProfileDetailDto {
        failure?.let { throw it }
        requestedId = id
        return response
    }

    override suspend fun updateProfile(id: String, request: ProfileUpdateRequest): ProfileDetailDto {
        failure?.let { throw it }
        requestedId = id
        updateRequest = request
        return response
    }

    override suspend fun getProfileSettings(id: String): ProfileSettingsDto {
        failure?.let { throw it }
        settingsGetCalls++
        settingsRequestedId = id
        return settingsResponse
    }

    override suspend fun updateProfileSettings(id: String, request: ProfileSettingsRequest): ProfileSettingsDto {
        failure?.let { throw it }
        settingsPutCalls++
        settingsRequestedId = id
        settingsRequest = request
        return settingsResponse
    }

    override suspend fun getActivityPreferences(id: String) = ActivityPreferencesDto()
    override suspend fun updateActivityPreferences(id: String, request: ActivityPreferencesRequest) = ActivityPreferencesDto()
    override suspend fun createProfileContext(profileId: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun getProfileContexts(profileId: String): List<ProfileContextDto> = error("Not used")
    override suspend fun getActiveProfileContexts(profileId: String): List<ProfileContextDto> = error("Not used")
    override suspend fun updateProfileContext(profileId: String, contextId: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun deleteProfileContext(profileId: String, contextId: String) = error("Not used")

    override suspend fun postUsage(id: String, usage: UsageDto) = usage
    override suspend fun getUsage(id: String) = UsageDto(id, 0, 0)
    override suspend fun postAnalysis(id: String) = AnalysisDto(id, "")
    override suspend fun latestAnalysis(id: String) = AnalysisDto(id, "")
}

class ProfileRepositoryTest {
    private fun settings(
        profileId: String = "profile-123",
        schoolStart: String = "08:00:00",
        schoolEnd: String = "12:30:00",
        sleepStart: String = "22:00:00",
        sleepEnd: String = "07:00:00",
    ) = ProfileSettingsDto(
        profileId = profileId,
        schoolStart = schoolStart,
        schoolEnd = schoolEnd,
        sleepStart = sleepStart,
        sleepEnd = sleepEnd,
    )

    private fun detail(
        id: String = "profile-123",
        alias: String = "Juana",
        age: Int = 9,
        avatar: String = "frog",
        active: Boolean = true,
        profileSettings: ProfileSettingsDto? = settings(profileId = id),
    ) = ProfileDetailDto(
        id = id,
        alias = alias,
        age = age,
        avatar = avatar,
        active = active,
        settings = profileSettings,
    )

    @Test fun `domain maps name avatar and selected schedules to create contract`() {
        val request = Profile(
            name = "Juana",
            avatar = "🐸",
            age = 10,
            schoolSchedules = setOf(SchoolSchedule.MORNING, SchoolSchedule.AFTERNOON),
            sleepStart = "22:00",
            sleepEnd = "07:00",
        ).toCreateProfileRequest()

        assertEquals("Juana", request.alias)
        assertEquals(10, request.age)
        assertEquals("frog", request.avatar)
        assertTrue(request.active)
        assertEquals("07:00", request.settings?.schoolStart)
        assertEquals("18:00", request.settings?.schoolEnd)
        assertEquals("22:00", request.settings?.sleepStart)
        assertEquals("07:00", request.settings?.sleepEnd)
        assertNotEquals("test android", request.alias)
        val json = Gson().toJson(request)
        assertTrue(json.contains("\"school_start\":\"07:00\""))
        assertTrue(json.contains("\"sleep_start\":\"22:00\""))
        assertTrue(json.contains("\"sleep_end\":\"07:00\""))
        assertFalse(json.contains("schoolStart"))
    }

    @Test fun `create mapper never fabricates missing sleep end`() {
        val request = Profile("Juana", "🐸", 10, sleepStart = "22:00").toCreateProfileRequest()
        assertEquals("22:00", request.settings?.sleepStart)
        assertNull(request.settings?.sleepEnd)
    }

    @Test fun `profile detail deserializes backend field names and seconds`() {
        val json = """
            {
              "id":"profile-123","alias":"Juana","age":9,"avatar":"frog","active":true,
              "created_at":"2026-09-28T10:00:00Z","updated_at":"2026-09-28T10:00:00Z",
              "settings":{"profile_id":"profile-123","school_start":"08:00:00","school_end":"12:30:00","sleep_start":"22:00:00","sleep_end":"07:00:00","created_at":"2026-09-28T10:00:00Z","updated_at":"2026-09-28T10:00:00Z"}
            }
        """.trimIndent()

        val dto = Gson().fromJson(json, ProfileDetailDto::class.java)
        val profile = dto.toDomain()

        assertEquals("Juana", profile.name)
        assertEquals("🐸", profile.avatar)
        assertEquals("profile-123", profile.id)
        assertTrue(profile.active)
        assertEquals("08:00", profile.schoolStart)
        assertEquals("12:30", profile.schoolEnd)
        assertEquals("22:00", profile.sleepStart)
        assertEquals("07:00", profile.sleepEnd)
    }

    @Test fun `create stores the remote response including id`() = runTest {
        val local = FakeProfileLocalStore()
        val api = FakeHabitsApi().apply { response = detail() }
        val repository = CachedProfileRepository(local, api)

        val created = repository.create(Profile("Juana", "🐸", 9, schoolSchedules = setOf(SchoolSchedule.MORNING)))

        assertEquals("Juana", api.createRequest?.alias)
        assertEquals("profile-123", created.id)
        assertEquals(created, local.profile.value)
    }

    @Test fun `refresh uses stored id and replaces the local cache`() = runTest {
        val local = FakeProfileLocalStore(Profile("Anterior", "🦊", 8, id = "profile-123"))
        val api = FakeHabitsApi().apply { response = detail(alias = "Actualizada", age = 10) }
        val repository = CachedProfileRepository(local, api)

        val refreshed = repository.refresh()

        assertEquals("profile-123", api.requestedId)
        assertEquals("Actualizada", refreshed?.name)
        assertEquals(10, local.profile.value?.age)
    }

    @Test fun `update sends profile fields and replaces the local cache`() = runTest {
        val local = FakeProfileLocalStore(Profile("Juana", "🐸", 9, id = "profile-123"))
        val api = FakeHabitsApi().apply { response = detail(alias = "Juanita", age = 10, avatar = "fox", active = false) }
        val repository = CachedProfileRepository(local, api)

        val updated = repository.update(Profile("Juanita", "🦊", 10, id = "profile-123", active = false))

        assertEquals("profile-123", api.requestedId)
        assertEquals("Juanita", api.updateRequest?.alias)
        assertEquals("fox", api.updateRequest?.avatar)
        assertEquals(false, api.updateRequest?.active)
        assertEquals("Juanita", updated.name)
        assertFalse(local.profile.value!!.active)
    }

    @Test fun `basic profile update preserves id active schedules and legacy fields`() = runTest {
        val cached = Profile(
            name = "Anterior",
            avatar = "🦊",
            age = 8,
            schoolSchedules = setOf(SchoolSchedule.MORNING, SchoolSchedule.AFTERNOON),
            bedtime = com.juan.habitos.domain.model.BedtimeRange.FROM_22_TO_23,
            schoolStart = "08:00",
            schoolEnd = "12:30",
            sleepStart = "22:00",
            sleepEnd = "07:00",
            id = "profile-123",
            active = false,
        )
        val local = FakeProfileLocalStore(cached)
        val api = FakeHabitsApi().apply {
            response = detail(alias = "Sofía", age = 10, avatar = "frog", active = false, profileSettings = null)
        }
        val repository = CachedProfileRepository(local, api)

        val updated = repository.update(cached.copy(name = "Sofía", age = 10, avatar = "🐸"))

        assertEquals("profile-123", api.requestedId)
        assertEquals("Sofía", api.updateRequest?.alias)
        assertEquals(10, api.updateRequest?.age)
        assertEquals("frog", api.updateRequest?.avatar)
        assertEquals(false, api.updateRequest?.active)
        assertEquals("profile-123", updated.id)
        assertFalse(updated.active)
        assertEquals(cached.schoolSchedules, updated.schoolSchedules)
        assertEquals(cached.bedtime, updated.bedtime)
        assertEquals("08:00", updated.schoolStart)
        assertEquals("12:30", updated.schoolEnd)
        assertEquals("22:00", updated.sleepStart)
        assertEquals("07:00", updated.sleepEnd)
        assertEquals(updated, local.profile.value)
    }

    @Test fun `failed basic profile update preserves previous cache`() = runTest {
        val cached = Profile("Anterior", "🦊", 8, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00", id = "profile-123", active = false)
        val local = FakeProfileLocalStore(cached)
        val api = FakeHabitsApi().apply { response = detail(); failure = IOException("offline") }
        val repository = CachedProfileRepository(local, api)

        try { repository.update(cached.copy(name = "Editado")); fail("Expected PUT failure") } catch (_: IOException) {}

        assertEquals(cached, local.profile.value)
    }

    @Test fun `basic profile update without remote id falls back to local cache`() = runTest {
        val cached = Profile("Anterior", "🦊", 8, active = false)
        val local = FakeProfileLocalStore(cached)
        val api = FakeHabitsApi()
        val repository = CachedProfileRepository(local, api)
        val edited = cached.copy(name = "Local", age = 9, avatar = "🐸")

        val updated = repository.update(edited)

        assertEquals(edited, updated)
        assertEquals(edited, local.profile.value)
        assertNull(api.updateRequest)
    }

    @Test fun `remote failure never deletes the cached profile`() = runTest {
        val cached = Profile("Local", "🦊", 9, id = "profile-123")
        val local = FakeProfileLocalStore(cached)
        val api = FakeHabitsApi().apply { response = detail(); failure = IOException("offline") }
        val repository = CachedProfileRepository(local, api)

        try {
            repository.refresh()
            fail("Expected the remote error")
        } catch (_: IOException) {
            assertEquals(cached, local.profile.value)
        }
    }

    @Test fun `refresh settings uses stored profile id normalizes seconds and updates cache`() = runTest {
        val cached = Profile("Local", "🦊", 9, schoolStart = "07:30", schoolEnd = "11:30", sleepStart = "21:30", sleepEnd = "06:30", id = "profile-123")
        val local = FakeProfileLocalStore(cached)
        val api = FakeHabitsApi().apply { settingsResponse = settings() }
        val repository = CachedProfileRepository(local, api)

        val refreshed = repository.refreshSettings()

        assertEquals("profile-123", api.settingsRequestedId)
        assertEquals(1, api.settingsGetCalls)
        assertEquals("08:00", refreshed?.schoolStart)
        assertEquals("12:30", local.profile.value?.schoolEnd)
        assertEquals("22:00", local.profile.value?.sleepStart)
        assertEquals("07:00", local.profile.value?.sleepEnd)
        assertEquals("profile-123", local.profile.value?.id)
        assertEquals("Local", local.profile.value?.name)
    }

    @Test fun `update settings sends all exact fields and saves normalized response`() = runTest {
        val cached = Profile("Local", "🦊", 9, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00", id = "profile-123", active = false)
        val local = FakeProfileLocalStore(cached)
        val api = FakeHabitsApi().apply { settingsResponse = settings(schoolStart = "09:15:00", schoolEnd = "13:45:00", sleepStart = "23:10:00", sleepEnd = "07:20:00") }
        val repository = CachedProfileRepository(local, api)
        val edited = cached.copy(schoolStart = "09:15", schoolEnd = "13:45", sleepStart = "23:10", sleepEnd = "07:20")

        val updated = repository.updateSettings(edited)

        assertEquals("profile-123", api.settingsRequestedId)
        assertEquals(1, api.settingsPutCalls)
        assertEquals("09:15", api.settingsRequest?.schoolStart)
        assertEquals("13:45", api.settingsRequest?.schoolEnd)
        assertEquals("23:10", api.settingsRequest?.sleepStart)
        assertEquals("07:20", api.settingsRequest?.sleepEnd)
        val json = Gson().toJson(api.settingsRequest)
        assertTrue(json.contains("\"school_start\":\"09:15\""))
        assertTrue(json.contains("\"school_end\":\"13:45\""))
        assertTrue(json.contains("\"sleep_start\":\"23:10\""))
        assertTrue(json.contains("\"sleep_end\":\"07:20\""))
        assertEquals("09:15", updated.schoolStart)
        assertEquals("07:20", local.profile.value?.sleepEnd)
        assertEquals("profile-123", local.profile.value?.id)
        assertEquals("Local", local.profile.value?.name)
        assertFalse(local.profile.value!!.active)
    }

    @Test fun `failed settings requests preserve previous cache`() = runTest {
        val cached = Profile("Local", "🦊", 9, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00", id = "profile-123")
        val local = FakeProfileLocalStore(cached)
        val api = FakeHabitsApi().apply { failure = IOException("offline") }
        val repository = CachedProfileRepository(local, api)

        try {
            repository.refreshSettings()
            fail("Expected GET settings to fail")
        } catch (_: IOException) {
            // Expected.
        }
        assertEquals(cached, local.profile.value)
        try {
            repository.updateSettings(cached.copy(schoolStart = "09:00"))
            fail("Expected PUT settings to fail")
        } catch (_: IOException) {
            // Expected.
        }
        assertEquals(cached, local.profile.value)
    }

    @Test fun `settings without remote id stay local and never call api`() = runTest {
        val cached = Profile("Local", "🦊", 9, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00")
        val local = FakeProfileLocalStore(cached)
        val api = FakeHabitsApi()
        val repository = CachedProfileRepository(local, api)

        assertEquals(cached, repository.refreshSettings())
        val updated = repository.updateSettings(cached.copy(schoolStart = "09:00"))

        assertEquals("09:00", updated.schoolStart)
        assertEquals("09:00", local.profile.value?.schoolStart)
        assertEquals(0, api.settingsGetCalls)
        assertEquals(0, api.settingsPutCalls)
    }
}
