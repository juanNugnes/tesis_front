package com.juan.habitos.data.remote

import com.google.gson.GsonBuilder
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class HistoricalSubmissionsHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: HistoricalSubmissionRepository

    @Before fun setUp() {
        server = MockWebServer()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
            .build().create(HabitsApi::class.java)
        repository = HistoricalSubmissionRepository(api, DeviceAuthorization())
    }
    @After fun tearDown() = server.shutdown()

    @Test fun postUsesExactProfileRouteDeviceBearerAndPreservesProcessedReceipt() = runTest {
        server.enqueue(json(200, responseJson("PROCESSED", evaluationId = "eval-1")))
        val result = repository.deliver(entry()) as HistoricalDeliveryResult.Received
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/profiles/profile-1/historical-submissions", recorded.path)
        assertEquals("Bearer device-token", recorded.getHeader("Authorization"))
        val body = GsonBuilder().create().fromJson(recorded.body.readUtf8(), HistoricalSubmissionRequest::class.java)
        assertEquals("request-1", body.requestId)
        assertEquals("capture-test-v1", body.measurementVersion)
        assertEquals("eval-1", result.response.analyticsEvaluationId)
        assertEquals(HistoricalSubmissionStatus.PROCESSED, result.response.status)
    }

    @Test fun retryableTechnicalReceiptUsesStableServerRetryRoute() = runTest {
        server.enqueue(json(200, responseJson("PROCESSED", evaluationId = "eval-retry")))
        val technical = response("TECHNICAL_ERROR", retryable = true)
        val result = repository.deliver(entry().copy(submissionId = "submission-1", response = technical))
        assertTrue(result is HistoricalDeliveryResult.Received)
        assertEquals("/api/v1/profiles/profile-1/historical-submissions/submission-1/retry", server.takeRequest().path)
    }

    @Test fun httpFailuresAreClassifiedWithoutChangingRequestIdentity() = runTest {
        for ((code, expected) in listOf(
            401 to HistoricalDeliveryResult.AuthenticationRequired,
            403 to HistoricalDeliveryResult.Forbidden,
            409 to HistoricalDeliveryResult.ContractRejected(409),
            422 to HistoricalDeliveryResult.ContractRejected(422),
            503 to HistoricalDeliveryResult.RetryableTransport(503),
        )) {
            server.enqueue(json(code, "{}"))
            assertEquals(expected, repository.deliver(entry()))
            assertTrue(server.takeRequest().body.readUtf8().contains("request-1"))
        }
    }

    private fun entry() = HistoricalOutboxEntry("profile-1", "2026-10-05", request())
    private fun request() = HistoricalSubmissionRequest("request-1", "UTC", "capture-test-v1",
        HistoricalRepresentationDto("rep-1", "daily_use_duration_minutes_v2", "series-1", "DAILY_USE_DURATION",
            "2026-10-05T00:00:00Z", "2026-10-06T00:00:00Z", 60.0, "minutes", "OBSERVED",
            HistoricalCoverageDto(null, "daily_capture_completeness_not_quantified"), emptyList(),
            HistoricalProvenanceDto("android-usage-events", "f12-backend-bridge-v1-candidate",
                "Interactive screen duration on assigned device; user attribution unverified", "v2", "fingerprint"),
            "2026-10-06T00:01:00Z"))
    private fun response(status: String, retryable: Boolean = false) = GsonBuilder().create()
        .fromJson(responseJson(status, retryable = retryable), HistoricalSubmissionResponse::class.java)
    private fun responseJson(status: String, evaluationId: String? = null, retryable: Boolean = false): String {
        val evaluation = evaluationId?.let { "\"" + it + "\"" } ?: "null"
        return "{\"id\":\"submission-1\",\"request_id\":\"request-1\",\"profile_id\":\"profile-1\"," +
            "\"device_id\":\"device-1\",\"assignment_id\":\"device-1\",\"local_date\":\"2026-10-05\"," +
            "\"time_zone\":\"UTC\",\"measurement_version\":\"capture-test-v1\",\"status\":\"" + status + "\"," +
            "\"attempts\":1,\"last_attempt_at\":\"2026-10-06T00:02:00Z\",\"analytics_evaluation_id\":" + evaluation + "," +
            "\"analytics_replayed\":false,\"retryable\":" + retryable + ",\"last_error_code\":null,\"last_error_message\":null," +
            "\"created_at\":\"2026-10-06T00:02:00Z\",\"updated_at\":\"2026-10-06T00:02:00Z\"}"
    }
    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
    private class DeviceAuthorization : RequestAuthorization {
        override val principal = RequestPrincipal.DEVICE
        override suspend fun <T> execute(request: suspend (String) -> T) = request("Bearer device-token")
    }
}
