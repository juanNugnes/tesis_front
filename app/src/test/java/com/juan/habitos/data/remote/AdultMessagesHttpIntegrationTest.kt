package com.juan.habitos.data.remote

import com.google.gson.GsonBuilder
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.AdultCredentialStore
import com.juan.habitos.ui.state.AdultMessagesUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

class AdultMessagesHttpIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var credentials: Credentials
    private lateinit var repository: RemoteAdultMessagesRepository

    @Before fun setUp() {
        server = MockWebServer()
        credentials = Credentials()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
            .build()
            .create(AdultMessagesApi::class.java)
        repository = RemoteAdultMessagesRepository(api, AdultRequestAuthorization(credentials))
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `real HTTP response preserves the contract and is presented without demo values`() = runTest {
        server.enqueue(jsonResponse(fixtureJson()))

        val history = repository.history(PROFILE_ID)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/profiles/$PROFILE_ID/adult-messages/history", request.path)
        assertEquals("Bearer adult-token", request.getHeader("Authorization"))

        assertEquals(3, history.versions.size)
        val opening = history.versions.first()
        assertEquals(1, opening.version)
        with(opening.card) {
            assertEquals(PROFILE_ID, profileId)
            assertEquals("6b28c17a7070a40f7609476b4d2a286a3dc4bd772455dafe69051f6db8963991", cardId)
            assertEquals("series-fd8c54d19fb71f46a236f1debc3f397a6356814035cbb6d29d378de63cc5c8b3", seriesId)
            assertEquals("America/Argentina/Buenos_Aires", timeZone)
            assertEquals("INCREASE", direction)
            assertEquals("ACTIVE", status)
            assertTrue(title.isNotBlank())
            assertTrue(text.contains("2026-01-19"))
            assertTrue(recommendation.isNotBlank())
            assertEquals("2026-02-02", evaluationDate)
            assertEquals("2026-01-05", openingReferenceStart)
            assertEquals("2026-01-19", openingRecentStart)
            assertEquals("2026-01-19", comparisonRecentStart)
            assertEquals(140.0, requireNotNull(statistics).recent, 0.0)
            assertEquals(100.0, requireNotNull(statistics).reference, 0.0)
            assertEquals("uso registrado en este teléfono", measurement)
            assertTrue(measurementDetail.isNotBlank())
            assertEquals("adult-messages-internal-candidate-v1", configurationId)
            assertEquals("internal_candidate_not_family_validated", scope)
            assertEquals("CANDIDATE_NOT_DELIVERED", deliveryStatus)
        }

        val state = AdultMessagesUiState(profileId = PROFILE_ID, history = history)
        val displayed = state.archived.single().card
        assertEquals(history.versions.last().card.title, displayed.title)
        assertEquals(history.versions.last().card.text, displayed.text)
        assertEquals(history.versions.last().card.recommendation, displayed.recommendation)
        assertFalse(state.demo)
        assertNull(state.demoLabel)
    }

    @Test fun `empty HTTP history stays empty and never becomes demo content`() = runTest {
        server.enqueue(jsonResponse("""{"versions":[]}"""))

        val state = AdultMessagesUiState(profileId = "empty-profile", history = repository.history("empty-profile"))

        assertTrue(state.current.isEmpty())
        assertTrue(state.archived.isEmpty())
        assertFalse(state.demo)
        assertNull(state.demoLabel)
        assertEquals("/api/v1/profiles/empty-profile/adult-messages/history", server.takeRequest().path)
    }

    @Test fun `network 401 and 403 failures keep their distinct semantics`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(runCatching { repository.history(PROFILE_ID) }.exceptionOrNull() is IOException)

        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(runCatching { repository.history(PROFILE_ID) }.exceptionOrNull() is AdultAuthenticationRequiredException)
        assertNull(credentials.adultAccessToken.value)

        credentials.adultAccessToken.value = "adult-token"
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        assertTrue(runCatching { repository.history(PROFILE_ID) }.exceptionOrNull() is AdultAuthorizationException)
        assertEquals("adult-token", credentials.adultAccessToken.value)
    }

    private fun fixtureJson(): String = requireNotNull(javaClass.getResource("/adult_messages_history.fictitious.json")).readText()

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private class Credentials : AdultCredentialStore {
        override val adultAccessToken = MutableStateFlow<String?>("adult-token")
        override suspend fun saveAdultAccessToken(token: String) { adultAccessToken.value = token }
        override suspend fun clearAdultAuthentication() { adultAccessToken.value = null }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-increase_return"
    }
}
