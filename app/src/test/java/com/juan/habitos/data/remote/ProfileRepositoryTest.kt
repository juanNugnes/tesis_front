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
    var requestedId: String? = null
    var failure: Exception? = null
    lateinit var response: ProfileDetailDto

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

    override suspend fun postUsage(id: String, usage: UsageDto) = usage
    override suspend fun getUsage(id: String) = UsageDto(id, 0, 0)
    override suspend fun postAnalysis(id: String) = AnalysisDto(id, "")
    override suspend fun latestAnalysis(id: String) = AnalysisDto(id, "")
}

class ProfileRepositoryTest {
    private fun detail(
        id: String = "profile-123",
        alias: String = "Juana",
        age: Int = 9,
        avatar: String = "frog",
        active: Boolean = true,
    ) = ProfileDetailDto(
        id = id,
        alias = alias,
        age = age,
        avatar = avatar,
        active = active,
        settings = ProfileSettingsDto(
            profileId = id,
            schoolStart = "08:00:00",
            schoolEnd = "12:30:00",
            sleepStart = "22:00:00",
            sleepEnd = "07:00:00",
        ),
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
}
