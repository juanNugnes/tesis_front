package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.juan.habitos.domain.model.ActivityRestriction
import com.juan.habitos.domain.model.ChildConsideration
import com.juan.habitos.domain.model.ChildInterest
import com.juan.habitos.domain.model.ChildPreferences
import com.juan.habitos.domain.model.ConsiderationDuration
import com.juan.habitos.domain.model.ConsiderationPreference
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
    override suspend fun requestAdultOtp(request: AdultOtpRequest): AdultOtpRequestResponse = error("Not used")
    override suspend fun verifyAdultOtp(request: AdultOtpVerifyRequest): AdultAccessTokenDto = error("Not used")
    override suspend fun getAdultMe(authorization: String): AdultDto = error("Not used")
    override suspend fun getAdultProfiles(authorization: String): List<ProfileDetailDto> = error("Not used")
    override suspend fun claimAdultProfileLink(authorization: String, request: AdultProfileLinkClaimRequest): AdultProfileLinkClaimResponse = error("Not used")
    override suspend fun createAdultProfileLinkCode(profileId: String, authorization: String): AdultProfileLinkCodeDto = error("Not used")
    var response = ActivityPreferencesDto()
    var failure: Exception? = null
    var getCalls = 0
    var putCalls = 0
    var requestedId: String? = null
    var authorization: String? = null
    var request: ActivityPreferencesRequest? = null

    override suspend fun getActivityPreferences(id: String, authorization: String): ActivityPreferencesDto {
        failure?.let { throw it }
        this.authorization = authorization
        getCalls++
        requestedId = id
        return response
    }

    override suspend fun updateActivityPreferences(id: String, authorization: String, request: ActivityPreferencesRequest): ActivityPreferencesDto {
        failure?.let { throw it }
        this.authorization = authorization
        putCalls++
        requestedId = id
        this.request = request
        return response
    }

    override suspend fun createProfile(request: CreateProfileRequest): ProfileBootstrapDto = error("Not used")
    override suspend fun getProfile(id: String, authorization: String): ProfileDetailDto = error("Not used")
    override suspend fun updateProfile(id: String, authorization: String, request: ProfileUpdateRequest): ProfileDetailDto = error("Not used")
    override suspend fun getProfileSettings(id: String, authorization: String): ProfileSettingsDto = error("Not used")
    override suspend fun updateProfileSettings(id: String, authorization: String, request: ProfileSettingsRequest): ProfileSettingsDto = error("Not used")
    override suspend fun createProfileContext(profileId: String, authorization: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun getProfileContexts(profileId: String, authorization: String): List<ProfileContextDto> = error("Not used")
    override suspend fun getActiveProfileContexts(profileId: String, authorization: String): List<ProfileContextDto> = error("Not used")
    override suspend fun updateProfileContext(profileId: String, contextId: String, authorization: String, request: ProfileContextRequest): ProfileContextDto = error("Not used")
    override suspend fun deleteProfileContext(profileId: String, contextId: String, authorization: String) = error("Not used")
    override suspend fun registerDevice(profileId: String, authorization: String, request: DeviceCreateRequest): retrofit2.Response<DeviceRegistrationDto> = error("Not used")
    override suspend fun createDeviceLinkCode(profileId: String): retrofit2.Response<DeviceLinkCodeDto> = error("Not used")
    override suspend fun claimDeviceLink(request: DeviceLinkClaimRequest): retrofit2.Response<DeviceLinkClaimResponse> = error("Not used")
    override suspend fun getRepresentativeVerification(profileId: String, authorization: String): RepresentativeVerificationDto = error("Not used")
    override suspend fun grantConsent(profileId: String, authorization: String, request: ConsentGrantRequest): ConsentDto = error("Not used")
    override suspend fun getCurrentConsent(profileId: String, authorization: String): ConsentDto = error("Not used")
    override suspend fun revokeConsent(profileId: String, consentId: String, authorization: String): ConsentDto = error("Not used")
    override suspend fun postUsage(id: String, usage: UsageDto) = usage
    override suspend fun getUsage(id: String) = UsageDto(id, 0, 0)
    override suspend fun postAnalysis(id: String) = AnalysisDto(id, "")
    override suspend fun latestAnalysis(id: String) = AnalysisDto(id, "")
}

class ActivityPreferencesRepositoryTest {
    private fun repository(
        local: ChildPreferencesLocalStore,
        profileStore: ProfileLocalStore,
        api: HabitsApi,
        credentials: TestDeviceCredentialStore = TestDeviceCredentialStore(),
    ) = CachedChildPreferencesRepository(local, profileStore, api, credentials)
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
            "UPPER_BODY_MOBILITY", "LOWER_BODY_MOBILITY", "VISUAL", "AUDITORY", "SOUND_SENSITIVITY",
        )
        val preferences = ChildPreferences(
            considerationsAnswered = true,
            considerations = ChildConsideration.entries.associateWith { ConsiderationPreference() },
        )
        val request = preferences.toActivityPreferencesRequest()
        assertEquals(expected, request.considerations.map { it.type }.toSet())
        val restored = ActivityPreferencesDto(
            considerationsAnswered = true,
            considerations = expected.map { ActivityConsiderationDto(it, "PERMANENT", null) },
        ).toDomainPreferences()
        assertEquals(ChildConsideration.entries.toSet(), restored.considerations.keys)
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

    @Test fun `omitted question sends answered false and empty considerations`() {
        val request = ChildPreferences().toActivityPreferencesRequest()
        assertFalse(request.considerationsAnswered)
        assertTrue(request.considerations.isEmpty())
    }

    @Test fun `explicit none sends answered true and empty considerations`() {
        val request = ChildPreferences(considerationsAnswered = true).toActivityPreferencesRequest()
        assertTrue(request.considerationsAnswered)
        assertTrue(request.considerations.isEmpty())
    }

    @Test fun `permanent consideration sends null valid until`() {
        val request = ChildPreferences(
            considerationsAnswered = true,
            considerations = mapOf(ChildConsideration.VISUAL to ConsiderationPreference(ConsiderationDuration.PERMANENT)),
        ).toActivityPreferencesRequest()
        assertEquals(ActivityConsiderationDto("VISUAL", "PERMANENT", null), request.considerations.single())
    }

    @Test fun `temporary consideration sends its ISO date`() {
        val request = ChildPreferences(
            considerationsAnswered = true,
            considerations = mapOf(
                ChildConsideration.LOWER_BODY_MOBILITY to ConsiderationPreference(
                    ConsiderationDuration.TEMPORARY,
                    LocalDate.of(2026, 11, 20),
                )
            ),
        ).toActivityPreferencesRequest()
        assertEquals(
            ActivityConsiderationDto("LOWER_BODY_MOBILITY", "TEMPORARY", "2026-11-20"),
            request.considerations.single(),
        )
    }

    @Test fun `multiple considerations including both mobility areas remain independent`() {
        val preferences = ChildPreferences(
            considerationsAnswered = true,
            considerations = mapOf(
                ChildConsideration.UPPER_BODY_MOBILITY to ConsiderationPreference(),
                ChildConsideration.LOWER_BODY_MOBILITY to ConsiderationPreference(ConsiderationDuration.TEMPORARY, LocalDate.of(2026, 11, 20)),
                ChildConsideration.AUDITORY to ConsiderationPreference(),
            ),
        )
        val request = preferences.toActivityPreferencesRequest()
        val restored = ActivityPreferencesDto(
            considerationsAnswered = true,
            considerations = request.considerations,
        ).toDomainPreferences()
        assertEquals(preferences.considerations, restored.considerations)
        assertTrue(ChildConsideration.UPPER_BODY_MOBILITY in restored.considerations)
        assertTrue(ChildConsideration.LOWER_BODY_MOBILITY in restored.considerations)
    }

    @Test fun `remote notes longer than ui limit are not silently truncated`() {
        val note = "x".repeat(300)
        val restored = ActivityPreferencesDto(additionalNotes = note).toDomainPreferences()
        assertEquals(note, restored.additionalNotes)
        assertEquals(note, restored.toActivityPreferencesRequest().additionalNotes)
    }

    @Test fun `request serializes backend snake case contract`() {
        val request = ChildPreferences(
            considerationsAnswered = true,
            considerations = mapOf(
                ChildConsideration.VISUAL to ConsiderationPreference(ConsiderationDuration.TEMPORARY, LocalDate.of(2026, 10, 20))
            ),
            activitiesToAvoid = setOf(ActivityRestriction.SOUNDS),
            additionalNotes = "Texto",
        ).toActivityPreferencesRequest()
        val json = Gson().toJson(request)
        assertTrue(json.contains("\"considerations_answered\":true"))
        assertTrue(json.contains("\"type\":\"VISUAL\""))
        assertTrue(json.contains("\"duration_type\":\"TEMPORARY\""))
        assertTrue(json.contains("\"activities_to_avoid\":[\"LOUD_SOUNDS\"]"))
        assertTrue(json.contains("\"additional_notes\":\"Texto\""))
        assertTrue(json.contains("\"valid_until\":\"2026-10-20\""))
        assertFalse(json.contains("adaptation_type"))
    }

    @Test fun `refresh with profile id updates cache and accepts neutral response`() = runTest {
        val cached = ChildPreferences(interests = setOf(ChildInterest.MUSIC), activitiesToAvoid = setOf(ActivityRestriction.SOUNDS))
        val local = FakeChildPreferencesStore(cached)
        val profile = Profile("Sofía", "🦋", 8, id = "profile-123")
        val profileStore = FakeActivityProfileStore(profile)
        val api = FakeActivityPreferencesApi()
        val repository = repository(local, profileStore, api)

        val refreshed = repository.refresh()

        assertEquals(1, api.getCalls)
        assertEquals("profile-123", api.requestedId)
        assertEquals("Bearer device-token", api.authorization)
        assertTrue(refreshed.interests.isEmpty())
        assertFalse(refreshed.considerationsAnswered)
        assertTrue(refreshed.considerations.isEmpty())
        assertEquals(setOf(ActivityRestriction.NONE), refreshed.activitiesToAvoid)
        assertEquals(refreshed, local.preferences.value)
        assertEquals(profile, profileStore.profile.value)
    }

    @Test fun `failed or incompatible refresh preserves cache`() = runTest {
        val cached = ChildPreferences(interests = setOf(ChildInterest.MUSIC), activitiesToAvoid = setOf(ActivityRestriction.SOUNDS))
        val local = FakeChildPreferencesStore(cached)
        val profileStore = FakeActivityProfileStore(Profile("Sofía", "🦋", 8, id = "profile-123"))
        val api = FakeActivityPreferencesApi().apply { failure = IOException("offline") }
        val repository = repository(local, profileStore, api)
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
            considerationsAnswered = true,
            considerations = mapOf(
                ChildConsideration.SOUND_SENSITIVITY to ConsiderationPreference(
                    ConsiderationDuration.TEMPORARY,
                    LocalDate.of(2026, 10, 20),
                )
            ),
            activitiesToAvoid = setOf(ActivityRestriction.RUN_JUMP),
            additionalNotes = "Temporal",
        )
        val local = FakeChildPreferencesStore(previous)
        val profile = Profile("Sofía", "🦋", 8, id = "profile-123")
        val profileStore = FakeActivityProfileStore(profile)
        val api = FakeActivityPreferencesApi().apply {
            response = ActivityPreferencesDto(
                interests = listOf("DRAWING_CREATING", "BUILDING"),
                considerationsAnswered = true,
                considerations = listOf(ActivityConsiderationDto("SOUND_SENSITIVITY", "TEMPORARY", "2026-10-20")),
                activitiesToAvoid = listOf("RUNNING", "JUMPING"),
                additionalNotes = "Temporal",
            )
        }
        val repository = repository(local, profileStore, api)

        val updated = repository.update(edited)

        assertEquals(1, api.putCalls)
        assertEquals("profile-123", api.requestedId)
        assertEquals("Bearer device-token", api.authorization)
        assertEquals(setOf("DRAWING_CREATING", "BUILDING"), api.request!!.interests.toSet())
        assertTrue(api.request!!.considerationsAnswered)
        assertEquals(ActivityConsiderationDto("SOUND_SENSITIVITY", "TEMPORARY", "2026-10-20"), api.request!!.considerations.single())
        assertEquals(setOf("RUNNING", "JUMPING"), api.request!!.activitiesToAvoid.toSet())
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
        val repository = repository(local, profileStore, api)
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

    @Test fun `adult preferences GET and PUT use only Adult bearer`() = runTest {
        val local = FakeChildPreferencesStore()
        val profileStore = FakeActivityProfileStore(Profile("Sofía", "fox", 9, id = "profile-123"))
        val api = FakeActivityPreferencesApi()
        val repository = CachedChildPreferencesRepository(local, profileStore, api, AdultRequestAuthorization(TestAdultCredentialStore("adult-token")))

        repository.refresh()
        assertEquals("Bearer adult-token", api.authorization)
        repository.update(ChildPreferences(activitiesToAvoid = setOf(ActivityRestriction.NONE)))
        assertEquals("Bearer adult-token", api.authorization)
        assertNotEquals("Bearer device-token", api.authorization)
    }
}
