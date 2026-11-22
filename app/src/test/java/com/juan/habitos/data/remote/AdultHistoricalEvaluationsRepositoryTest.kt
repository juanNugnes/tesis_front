package com.juan.habitos.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private class HistoricalEvaluationsTestApi : AdultHistoricalEvaluationsApi {
    var requestedProfileId: String? = null
    var authorization: String? = null
    var response: List<AdultHistoricalSubmissionDto> = emptyList()

    override suspend fun list(
        profileId: String,
        authorization: String,
    ): List<AdultHistoricalSubmissionDto> {
        requestedProfileId = profileId
        this.authorization = authorization
        return response
    }
}

private class HistoricalEvaluationsTestAuthorization(
    override val principal: RequestPrincipal,
) : RequestAuthorization {
    override suspend fun <T> execute(request: suspend (authorization: String) -> T): T =
        request("Bearer adult-token")
}

class AdultHistoricalEvaluationsRepositoryTest {
    @Test fun `retrofit contract performs the historical submissions GET`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AdultHistoricalEvaluationsApi::class.java)
            val repository = RemoteAdultHistoricalEvaluationsRepository(
                api,
                HistoricalEvaluationsTestAuthorization(RequestPrincipal.ADULT),
            )

            repository.list("profile-123")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/profiles/profile-123/historical-submissions", request.path)
            assertEquals("Bearer adult-token", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `query uses selected profile and adult bearer`() = runTest {
        val api = HistoricalEvaluationsTestApi().apply {
            response = listOf(
                AdultHistoricalSubmissionDto(
                    localDate = "2026-11-20",
                    status = "PROCESSED",
                    analyticsEvaluation = AdultHistoricalEvaluationDto("NO_CHANGE"),
                ),
            )
        }
        val repository = RemoteAdultHistoricalEvaluationsRepository(
            api,
            HistoricalEvaluationsTestAuthorization(RequestPrincipal.ADULT),
        )

        val result = repository.list("profile-authorized")

        assertEquals("profile-authorized", api.requestedProfileId)
        assertEquals("Bearer adult-token", api.authorization)
        assertEquals("NO_CHANGE", result.single().analyticsEvaluation?.outcome)
    }

    @Test fun `repository cannot be constructed with device authorization`() {
        try {
            RemoteAdultHistoricalEvaluationsRepository(
                HistoricalEvaluationsTestApi(),
                HistoricalEvaluationsTestAuthorization(RequestPrincipal.DEVICE),
            )
            fail("Expected adult authorization validation")
        } catch (_: IllegalArgumentException) {
            // Expected: the adult-only query cannot use a device principal.
        }
    }

    @Test fun `empty backend response remains empty`() = runTest {
        val repository = RemoteAdultHistoricalEvaluationsRepository(
            HistoricalEvaluationsTestApi(),
            HistoricalEvaluationsTestAuthorization(RequestPrincipal.ADULT),
        )

        assertEquals(emptyList<AdultHistoricalSubmissionDto>(), repository.list("profile-without-results"))
    }
}
