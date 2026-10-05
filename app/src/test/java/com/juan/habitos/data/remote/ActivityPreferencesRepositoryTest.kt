package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.juan.habitos.domain.model.ActivityRestriction
import com.juan.habitos.domain.model.AdaptationType
import com.juan.habitos.domain.model.ChildConsideration
import com.juan.habitos.domain.model.ChildInterest
import com.juan.habitos.domain.model.ChildPreferences
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.ChildPreferencesLocalStore
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

private class FakeChildPreferencesStore(initial: ChildPreferences = ChildPreferences()) : ChildPreferencesLocalStore {
    override val preferences = MutableStateFlow(initial)
    override suspend fun save(preferences: ChildPreferences) { this.preferences.value = preferences }
}

private class FakeActivityProfileStore(initial: Profile?) : ProfileLocalStore {
    override val profile = MutableStateFlow(initial)
    override val onboardingComplete = MutableStateFlow(false)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class FakeActivityPreferencesApi : HabitsApi {
    var response = ActivityPreferencesDto()
    var failure: Exception? = null
    var getCalls = 0
    var putCalls = 0
    var requestedId: String? = null
    var request: ActivityPreferencesRequest? = null

    override suspend fun getActivityPreferences(id: String): ActivityPreferencesDto {
        failure?.let { throw it }
        getCalls++
        requestedId = id
        return response
    }

    override suspend fun updateActivityPreferences(id: String, request: ActivityPreferencesRequest): ActivityPreferencesDto {
        failure?.let { throw it }
        putCalls++
        requestedId = id
        this.request = request
        return response
    }

    override suspend fun createProfile(request: CreateProfileRequest): ProfileDetailDto = error("Not used")
    override suspend fun getProfile(id: String): ProfileDetailDto = error("Not used")
    override suspend fun updateProfile(id: String, request: ProfileUpdateRequest): ProfileDetailDto = error("Not used")
    override suspend fun getProfileSettings(id: String): ProfileSettingsDto = error("Not used")
    override suspend fun updateProfileSettings(id: String, request: ProfileSettingsRequest): ProfileSettingsDto = error("Not used")
    override suspend fun createProfileContext(profileId: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun getProfileContexts(profileId: String): List<ProfileContextDto> = error("Not used")
    override suspend fun getActiveProfileContexts(profileId: String): List<ProfileContextDto> = error("Not used")
    override suspend fun updateProfileContext(profileId: String, contextId: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun deleteProfileContext(profileId: String, contextId: String) = error("Not used")
    override suspend fun registerDevice(profileId: String, request: DeviceCreateRequest): retrofit2.Response<DeviceDto> = error("Not used")
    override suspend fun createDeviceLinkCode(profileId: String): retrofit2.Response<DeviceLinkCodeDto> = error("Not used")
    override suspend fun claimDeviceLink(request: DeviceLinkClaimRequest): retrofit2.Response<DeviceLinkClaimResponse> = error("Not used")
    override suspend fun postUsage(id: String, usage: UsageDto) = usage
    override suspend fun getUsage(id: String) = UsageDto(id, 0, 0)
    override suspend fun postAnalysis(id: String) = AnalysisDto(id, "")
    override suspend fun latestAnalysis(id: String) = AnalysisDto(id, "")
}

class ActivityPreferencesRepositoryTest {
    @Test fun `all interests use explicit backend names and round trip`() {
        val expected = setOf(
            "DRAWING_CREATING", "READING_STORIES", "MUSIC", "GAMES_CHALLENGES", "OUTDOORS",
            "MOVEMENT", "BUILDING", "WRITING", "IMAGINATIVE_PLAY",
        )
        val request = ChildPreferences(interests = ChildInterest.entries.toSet()).toActivityPreferencesRequest()
        assertEquals(expected, request.interests.toSet())
        val restored = ActivityPreferencesDto(interests = expected.toList()).toDomainPreferences()
        assertEquals(ChildInterest.entries.toSet(), restored.interests)
    }

    @Test fun `all considerations use explicit backend names and round trip`() {
        val expected = setOf(
            "MOBILITY", "VISION", "HEARING", "ATTENTION_CONCENTRATION", "ARMS_HANDS", "LEGS",
            "COMMUNICATION", "SENSORY_SENSITIVITY", "TEMPORARY_PHYSICAL_DISCOMFORT", "OTHER",
        )
        val request = ChildPreferences(considerations = ChildConsideration.entries.toSet()).toActivityPreferencesRequest()
        assertEquals(expected, request.considerations.toSet())
        val restored = ActivityPreferencesDto(considerations = expected.toList()).toDomainPreferences()
        assertEquals(ChildConsideration.entries.toSet(), restored.considerations)
    }

    @Test fun `restrictions map run jump none and every direct value`() {
        val concrete = ActivityRestriction.entries.filterNot { it == ActivityRestriction.NONE }.toSet()
        val expected = setOf(
            "RUNNING", "JUMPING", "PROLONGED_STANDING", "ARMS_HANDS_USE", "VISUAL_PRECISION",
            "LOUD_SOUNDS", "HIGH_STIMULATION", "PROLONGED_CONCENTRATION",
        )
        val request = ChildPreferences(activitiesToAvoid = concrete).toActivityPreferencesRequest()
        assertEquals(expected, request.activitiesToAvoid.toSet())
        val restored = ActivityPreferencesDto(activitiesToAvoid = expected.toList()).toDomainPreferences()
        assertEquals(concrete, restored.activitiesToAvoid)
        assertTrue(ChildPreferences(activitiesToAvoid = setOf(ActivityRestriction.NONE)).toActivityPreferencesRequest().activitiesToAvoid.isEmpty())
        assertEquals(setOf(ActivityRestriction.NONE), ActivityPreferencesDto(activitiesToAvoid = emptyList()).toDomainPreferences().activitiesToAvoid)
    }

    @Test fun `single running or jumping is rejected without widening semantics`() {
        assertThrows(IncompatibleActivityPreferencesException::class.java) {
            ActivityPreferencesDto(activitiesToAvoid = listOf("RUNNING")).toDomainPreferences()
        }
        assertThrows(IncompatibleActivityPreferencesException::class.java) {
            ActivityPreferencesDto(activitiesToAvoid = listOf("JUMPING")).toDomainPreferences()
        }
    }

    @Test fun `adaptation controls ISO valid until without clearing other fields`() {
        val date = LocalDate.of(2026, 10, 20)
        val base = ChildPreferences(
            interests = setOf(ChildInterest.MUSIC),
            considerations = setOf(ChildConsideration.HEARING),
            activitiesToAvoid = setOf(ActivityRestriction.SOUNDS),
            additionalNotes = "Texto",
            validUntil = date,
        )
        assertEquals("2026-10-20", base.copy(adaptationType = AdaptationType.TEMPORARY).toActivityPreferencesRequest().validUntil)
        assertNull(base.copy(adaptationType = AdaptationType.NONE).toActivityPreferencesRequest().validUntil)
        assertNull(base.copy(adaptationType = AdaptationType.PERMANENT).toActivityPreferencesRequest().validUntil)
        val restored = ActivityPreferencesDto(adaptationType = "TEMPORARY", validUntil = "2026-10-20").toDomainPreferences()
        assertEquals(date, restored.validUntil)
        assertNull(ActivityPreferencesDto(adaptationType = "NONE", validUntil = "2026-10-20").toDomainPreferences().validUntil)
        assertNull(ActivityPreferencesDto(adaptationType = "PERMANENT", validUntil = "2026-10-20").toDomainPreferences().validUntil)
    }

    @Test fun `remote notes longer than ui limit are not silently truncated`() {
        val note = "x".repeat(300)
        val restored = ActivityPreferencesDto(additionalNotes = note).toDomainPreferences()
        assertEquals(note, restored.additionalNotes)
        assertEquals(note, restored.toActivityPreferencesRequest().additionalNotes)
    }

    @Test fun `request serializes backend snake case contract`() {
        val request = ChildPreferences(
            adaptationType = AdaptationType.TEMPORARY,
            activitiesToAvoid = setOf(ActivityRestriction.SOUNDS),
            additionalNotes = "Texto",
            validUntil = LocalDate.of(2026, 10, 20),
        ).toActivityPreferencesRequest()
        val json = Gson().toJson(request)
        assertTrue(json.contains("\"adaptation_type\":\"TEMPORARY\""))
        assertTrue(json.contains("\"activities_to_avoid\":[\"LOUD_SOUNDS\"]"))
        assertTrue(json.contains("\"additional_notes\":\"Texto\""))
        assertTrue(json.contains("\"valid_until\":\"2026-10-20\""))
        assertFalse(json.contains("adaptationType"))
    }

    @Test fun `refresh with profile id updates cache and accepts neutral response`() = runTest {
        val cached = ChildPreferences(interests = setOf(ChildInterest.MUSIC), activitiesToAvoid = setOf(ActivityRestriction.SOUNDS))
        val local = FakeChildPreferencesStore(cached)
        val profile = Profile("Sofía", "🦋", 8, id = "profile-123")
        val profileStore = FakeActivityProfileStore(profile)
        val api = FakeActivityPreferencesApi()
        val repository = CachedChildPreferencesRepository(local, profileStore, api)

        val refreshed = repository.refresh()

        assertEquals(1, api.getCalls)
        assertEquals("profile-123", api.requestedId)
        assertTrue(refreshed.interests.isEmpty())
        assertEquals(AdaptationType.NONE, refreshed.adaptationType)
        assertEquals(setOf(ActivityRestriction.NONE), refreshed.activitiesToAvoid)
        assertEquals(refreshed, local.preferences.value)
        assertEquals(profile, profileStore.profile.value)
    }

    @Test fun `failed or incompatible refresh preserves cache`() = runTest {
        val cached = ChildPreferences(interests = setOf(ChildInterest.MUSIC), activitiesToAvoid = setOf(ActivityRestriction.SOUNDS))
        val local = FakeChildPreferencesStore(cached)
        val profileStore = FakeActivityProfileStore(Profile("Sofía", "🦋", 8, id = "profile-123"))
        val api = FakeActivityPreferencesApi().apply { failure = IOException("offline") }
        val repository = CachedChildPreferencesRepository(local, profileStore, api)
        try { repository.refresh(); fail("Expected GET failure") } catch (_: IOException) {}
        assertEquals(cached, local.preferences.value)

        api.failure = null
        api.response = ActivityPreferencesDto(activitiesToAvoid = listOf("RUNNING"))
        try { repository.refresh(); fail("Expected incompatible response") } catch (_: IncompatibleActivityPreferencesException) {}
        assertEquals(cached, local.preferences.value)
    }

    @Test fun `update sends full backend payload and stores response`() = runTest {
        val previous = ChildPreferences(interests = setOf(ChildInterest.STORIES), activitiesToAvoid = setOf(ActivityRestriction.NONE))
        val edited = ChildPreferences(
            interests = setOf(ChildInterest.DRAW_CREATE, ChildInterest.BUILD),
            adaptationType = AdaptationType.TEMPORARY,
            considerations = setOf(ChildConsideration.ATTENTION),
            activitiesToAvoid = setOf(ActivityRestriction.RUN_JUMP),
            additionalNotes = "Temporal",
            validUntil = LocalDate.of(2026, 10, 20),
        )
        val local = FakeChildPreferencesStore(previous)
        val profile = Profile("Sofía", "🦋", 8, id = "profile-123")
        val profileStore = FakeActivityProfileStore(profile)
        val api = FakeActivityPreferencesApi().apply {
            response = ActivityPreferencesDto(
                interests = listOf("DRAWING_CREATING", "BUILDING"),
                adaptationType = "TEMPORARY",
                considerations = listOf("ATTENTION_CONCENTRATION"),
                activitiesToAvoid = listOf("RUNNING", "JUMPING"),
                additionalNotes = "Temporal",
                validUntil = "2026-10-20",
            )
        }
        val repository = CachedChildPreferencesRepository(local, profileStore, api)

        val updated = repository.update(edited)

        assertEquals(1, api.putCalls)
        assertEquals("profile-123", api.requestedId)
        assertEquals(setOf("DRAWING_CREATING", "BUILDING"), api.request!!.interests.toSet())
        assertEquals(setOf("RUNNING", "JUMPING"), api.request!!.activitiesToAvoid.toSet())
        assertEquals("2026-10-20", api.request!!.validUntil)
        assertEquals(edited, updated)
        assertEquals(edited, local.preferences.value)
        assertEquals(profile, profileStore.profile.value)
    }

    @Test fun `failed update preserves cache and missing profile id stays local`() = runTest {
        val previous = ChildPreferences(interests = setOf(ChildInterest.STORIES), activitiesToAvoid = setOf(ActivityRestriction.NONE))
        val edited = previous.copy(interests = setOf(ChildInterest.MUSIC))
        val local = FakeChildPreferencesStore(previous)
        val profileStore = FakeActivityProfileStore(Profile("Sofía", "🦋", 8, id = "profile-123"))
        val api = FakeActivityPreferencesApi().apply { failure = IOException("offline") }
        val repository = CachedChildPreferencesRepository(local, profileStore, api)
        try { repository.update(edited); fail("Expected PUT failure") } catch (_: IOException) {}
        assertEquals(previous, local.preferences.value)

        api.failure = null
        profileStore.profile.value = profileStore.profile.value!!.copy(id = null)
        assertEquals(previous, repository.refresh())
        assertEquals(edited, repository.update(edited))
        assertEquals(edited, local.preferences.value)
        assertEquals(0, api.getCalls)
        assertEquals(0, api.putCalls)
    }
}
