package com.juan.habitos.data.remote

import com.juan.habitos.data.device.F12HistoricalRequestCollector
import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.model.DeviceIdentity
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class F12HistoricalSyncTest {
    @Test fun processedDaySurvivesCoordinatorRestartWithoutRecaptureOrDuplicatePost() = runTest {
        val profiles = Profiles()
        val identity = Identity()
        val store = MemoryOutbox()
        var captures = 0
        var posts = 0
        val collector = F12HistoricalRequestCollector { profileId, _, day, _ ->
            captures++
            request("request-$profileId-$day")
        }
        val api = api { name, _ ->
            if (name == "postHistoricalSubmission") {
                posts++
                Response.success(response("PROCESSED", "request-profile-1-2026-10-05", evaluationId = "eval-1"))
            } else error("Unexpected $name")
        }
        val first = coordinator(true, profiles, identity, collector, api, store).synchronize()
        val restarted = coordinator(true, profiles, identity, collector, api, store).synchronize()

        assertEquals(F12SyncStatus.SYNCED, first.status)
        assertEquals("eval-1", first.analyticsEvaluationId)
        assertEquals(F12SyncStatus.SYNCED, restarted.status)
        assertEquals(1, captures)
        assertEquals(1, posts)
        val saved = store.load("profile-1").entries.single()
        assertEquals(HistoricalOutboxState.PROCESSED, saved.state)
        assertEquals("request-profile-1-2026-10-05", saved.request.requestId)
    }

    @Test fun technicalErrorKeepsFrozenRequestAndUsesBackendRetryEndpoint() = runTest {
        val store = MemoryOutbox()
        var postRequestId: String? = null
        var retrySubmissionId: String? = null
        val api = api { name, args ->
            when (name) {
                "postHistoricalSubmission" -> {
                    postRequestId = (args[2] as HistoricalSubmissionRequest).requestId
                    val json = responseJson("TECHNICAL_ERROR", "request-1", retryable = true)
                    Response.error<HistoricalSubmissionResponse>(503,
                        ResponseBody.create(MediaType.parse("application/json"), json))
                }
                "retryHistoricalSubmission" -> {
                    retrySubmissionId = args[1] as String
                    Response.success(response("PROCESSED", "request-1", evaluationId = "eval-recovered"))
                }
                else -> error("Unexpected $name")
            }
        }
        val collector = F12HistoricalRequestCollector { _, _, _, _ -> request("request-1") }
        val sync = coordinator(true, Profiles(), Identity(), collector, api, store)

        assertEquals(F12SyncStatus.RETRYABLE_FAILURE, sync.synchronize().status)
        val original = store.load("profile-1").entries.single().request
        val recovered = sync.synchronize()

        assertEquals(F12SyncStatus.SYNCED, recovered.status)
        assertEquals("request-1", postRequestId)
        assertEquals("submission-1", retrySubmissionId)
        assertEquals(original, store.load("profile-1").entries.single().request)
        assertEquals("eval-recovered", recovered.analyticsEvaluationId)
    }

    @Test fun disabledOrMismatchedProfileNeverCapturesOrCallsBackend() = runTest {
        var captures = 0
        val collector = F12HistoricalRequestCollector { _, _, _, _ -> captures++; request("unexpected") }
        val noApi = api { name, _ -> error("Unexpected $name") }
        assertEquals(F12SyncStatus.DISABLED,
            coordinator(false, Profiles(), Identity(), collector, noApi, MemoryOutbox()).synchronize().status)

        val profiles = Profiles().apply { profile.value = Profile("Other", "fox", 9, id = "profile-2") }
        assertEquals(F12SyncStatus.MISSING_CONFIGURATION,
            coordinator(true, profiles, Identity(), collector, noApi, MemoryOutbox()).synchronize().status)
        assertEquals(0, captures)
    }

    @Test fun forbiddenConsentPausesFrozenEntryAndExplicitAuthorizationResumesIt() = runTest {
        var calls = 0
        val api = api { name, _ ->
            assertEquals("postHistoricalSubmission", name)
            calls++
            if (calls == 1) Response.error<HistoricalSubmissionResponse>(403,
                ResponseBody.create(MediaType.parse("application/json"), "{}"))
            else Response.success(response("PROCESSED", "request-consent", evaluationId = "eval-consent"))
        }
        val store = MemoryOutbox()
        val sync = coordinator(true, Profiles(), Identity(),
            F12HistoricalRequestCollector { _, _, _, _ -> request("request-consent") }, api, store)

        assertEquals(F12SyncStatus.AUTHORIZATION_PAUSED, sync.synchronize().status)
        val blocked = store.load("profile-1").entries.single()
        assertEquals(HistoricalOutboxState.AUTHORIZATION_BLOCKED, blocked.state)
        sync.resumeAuthorization("profile-1")
        assertEquals(blocked.request, store.load("profile-1").entries.single().request)
        assertEquals(F12SyncStatus.SYNCED, sync.synchronize().status)
        assertEquals(2, calls)
    }

    @Test fun unauthorizedDeviceClearsCredentialAndDoesNotRegenerateEvidence() = runTest {
        val identity = Identity()
        var captures = 0
        val api = api { name, _ ->
            assertEquals("postHistoricalSubmission", name)
            Response.error<HistoricalSubmissionResponse>(401,
                ResponseBody.create(MediaType.parse("application/json"), "{}"))
        }
        val store = MemoryOutbox()
        val sync = coordinator(true, Profiles(), identity,
            F12HistoricalRequestCollector { _, _, _, _ -> captures++; request("request-auth") }, api, store)

        assertEquals(F12SyncStatus.AUTHORIZATION_PAUSED, sync.synchronize().status)
        assertNull(identity.credentials.value.accessToken)
        assertEquals(1, captures)
        assertEquals("request-auth", store.load("profile-1").entries.single().request.requestId)
    }

    private fun coordinator(
        enabled: Boolean, profiles: Profiles, identity: Identity, collector: F12HistoricalRequestCollector,
        api: HabitsApi, store: MemoryOutbox,
    ) = F12HistoricalSyncCoordinator(
        enabled, profiles, identity, collector,
        HistoricalSubmissionRepository(api, DeviceAuthorization()), store,
        zoneId = { ZoneId.of("UTC") }, now = { Instant.parse("2026-10-06T12:00:00Z") },
    )

    private fun request(id: String) = HistoricalSubmissionRequest(id, "UTC", "capture-test-v1",
        HistoricalRepresentationDto("rep-1", "daily_use_duration_minutes_v2", "series-1", "DAILY_USE_DURATION",
            "2026-10-05T00:00:00Z", "2026-10-06T00:00:00Z", 60.0, "minutes", "OBSERVED",
            HistoricalCoverageDto(null, "daily_capture_completeness_not_quantified"), emptyList(),
            HistoricalProvenanceDto("android-usage-events", "f12-backend-bridge-v1-candidate",
                "Interactive screen duration on assigned device; user attribution unverified", "v2", "fingerprint"),
            "2026-10-06T00:01:00Z"))
    private fun response(status: String, requestId: String, evaluationId: String? = null, retryable: Boolean = false) =
        HistoricalSubmissionResponse("submission-1", requestId, "profile-1", "device-1", "device-1",
            "2026-10-05", "UTC", "capture-test-v1", HistoricalSubmissionStatus.valueOf(status), 1,
            "2026-10-06T00:02:00Z", evaluationId, false, retryable, null, null,
            "2026-10-06T00:02:00Z", "2026-10-06T00:02:00Z")
    private fun responseJson(status: String, requestId: String, retryable: Boolean) =
        "{\"id\":\"submission-1\",\"request_id\":\"" + requestId + "\",\"profile_id\":\"profile-1\"," +
            "\"device_id\":\"device-1\",\"assignment_id\":\"device-1\",\"local_date\":\"2026-10-05\"," +
            "\"time_zone\":\"UTC\",\"measurement_version\":\"capture-test-v1\",\"status\":\"" + status + "\"," +
            "\"attempts\":1,\"last_attempt_at\":\"2026-10-06T00:02:00Z\",\"analytics_evaluation_id\":null," +
            "\"analytics_replayed\":null,\"retryable\":" + retryable + ",\"last_error_code\":\"HISTORICAL_HTTP_503\"," +
            "\"last_error_message\":\"unavailable\",\"created_at\":\"2026-10-06T00:02:00Z\",\"updated_at\":\"2026-10-06T00:02:00Z\"}"

    private class Profiles : ProfileLocalStore {
        override val profile = MutableStateFlow<Profile?>(Profile("Child", "fox", 9, id = "profile-1"))
        override val onboardingComplete = MutableStateFlow(true)
        override suspend fun save(profile: Profile) { this.profile.value = profile }
        override suspend fun completeOnboarding() { onboardingComplete.value = true }
    }
    private class Identity : DeviceIdentityLocalStore {
        override val identity = MutableStateFlow(DeviceIdentity("installation", "device-1", "profile-1"))
        override val credentials = MutableStateFlow(DeviceCredentials(accessToken = "device-token"))
        override suspend fun getOrCreateInstallationId() = "installation"
        override suspend fun saveBootstrap(token: String, expiresAt: String) = Unit
        override suspend fun clearBootstrap() = Unit
        override suspend fun saveAuthenticatedLink(profileId: String, deviceId: String, accessToken: String) = Unit
        override suspend fun clearDeviceAccessToken() { credentials.value = DeviceCredentials() }
    }
    private class MemoryOutbox : HistoricalOutboxStore {
        private val values = mutableMapOf<String, HistoricalOutbox>()
        override suspend fun load(profileId: String) = values[profileId] ?: HistoricalOutbox()
        override suspend fun update(profileId: String, transform: (HistoricalOutbox) -> HistoricalOutbox) =
            transform(load(profileId)).also { values[profileId] = it }
    }
    private class DeviceAuthorization : RequestAuthorization {
        override val principal = RequestPrincipal.DEVICE
        override suspend fun <T> execute(request: suspend (String) -> T) = request("Bearer device-token")
    }

    @Suppress("UNCHECKED_CAST")
    private fun api(handler: (String, Array<out Any?>) -> Any?): HabitsApi = Proxy.newProxyInstance(
        HabitsApi::class.java.classLoader, arrayOf(HabitsApi::class.java),
    ) { _, method, arguments -> handler(method.name, arguments.orEmpty()) } as HabitsApi
}
