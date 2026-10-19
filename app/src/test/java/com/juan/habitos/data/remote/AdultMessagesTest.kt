package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.juan.habitos.domain.repository.*
import com.juan.habitos.ui.state.AdultMessagesUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

internal fun messageFixture(): AdultMessageHistory = AdultMessagesTest::class.java
    .getResourceAsStream("/adult_messages_history.fictitious.json")!!.bufferedReader().use {
        Gson().fromJson(it, AdultMessageHistory::class.java)
    }

class AdultMessagesTest {
    @Test fun `official fixture preserves text statistics identity and versions`() {
        val history = messageFixture()
        assertEquals(3, history.versions.size)
        val card = history.versions.first().card
        assertEquals("Más uso registrado en este teléfono", card.title)
        assertEquals("America/Argentina/Buenos_Aires", card.timeZone)
        assertEquals(140.0, card.statistics!!.recent, 0.0)
        assertEquals("CANDIDATE_NOT_DELIVERED", card.deliveryStatus)
        assertEquals(3, history.latestCards().single().version)
        assertTrue(AdultMessagesUiState(history = history).current.isEmpty())
        assertEquals("RESOLVED", AdultMessagesUiState(history = history).archived.single().card.status)
    }

    @Test fun `empty single multiple and unordered versions select one latest per identity`() {
        assertTrue(AdultMessageHistory(emptyList()).latestCards().isEmpty())
        val versions = messageFixture().versions
        assertEquals(versions.take(1), AdultMessageHistory(versions.take(1)).latestCards())
        val other = versions.first().copy(card = versions.first().card.copy(cardId = "other"))
        val result = AdultMessageHistory(listOf(versions.last(), other, versions.first(), versions[1])).latestCards()
        assertEquals(2, result.size)
        assertEquals(3, result.first { it.card.cardId != "other" }.version)
    }

    @Test fun `withdrawal and historical correction cannot resurrect old active version`() {
        val old = messageFixture().versions.first()
        for (status in listOf("WITHDRAWN", "CORRECTED_HISTORY", "EXPIRED", "SERIES_CHANGED", "RESOLVED")) {
            val correction = old.copy(version = 2, card = old.card.copy(status = status,
                updatedDate = "2026-03-01", correctedDates = listOf("2026-01-08"), statistics = null))
            val state = AdultMessagesUiState(history = AdultMessageHistory(listOf(correction, old)))
            assertTrue(state.current.isEmpty())
            assertEquals(correction, state.archived.single())
            assertEquals("2026-03-01", state.archived.single().card.updatedDate)
        }
        for (status in currentMessageStatuses) {
            val latest = old.copy(version = 2, card = old.card.copy(status = status))
            assertEquals(latest, AdultMessagesUiState(history = AdultMessageHistory(listOf(old, latest))).current.single())
        }
    }

    private class Credentials : AdultCredentialStore {
        override val adultAccessToken = MutableStateFlow<String?>("adult-token")
        override suspend fun saveAdultAccessToken(token: String) { adultAccessToken.value = token }
        override suspend fun clearAdultAuthentication() { adultAccessToken.value = null }
    }

    @Test fun `request uses adult credential and exact selected profile`() = runTest {
        var requested: Pair<String, String>? = null
        val repository = RemoteAdultMessagesRepository(object : AdultMessagesApi {
            override suspend fun history(profileId: String, authorization: String): AdultMessageHistory {
                requested = profileId to authorization
                return AdultMessageHistory(emptyList())
            }
        }, AdultRequestAuthorization(Credentials()))
        assertTrue(repository.history("child-id").versions.isEmpty())
        assertEquals("child-id" to "Bearer adult-token", requested)
        val method = AdultMessagesApi::class.java.methods.single { it.name == "history" }
        assertEquals("api/v1/profiles/{profileId}/adult-messages/history", method.getAnnotation(retrofit2.http.GET::class.java)!!.value)
    }

    @Test fun `device authorization is rejected before making request`() {
        val authorization = object : RequestAuthorization {
            override val principal = RequestPrincipal.DEVICE
            override suspend fun <T> execute(request: suspend (String) -> T): T = error("must not execute")
        }
        val api = object : AdultMessagesApi {
            override suspend fun history(profileId: String, authorization: String): AdultMessageHistory = error("must not execute")
        }
        assertThrows(IllegalArgumentException::class.java) { RemoteAdultMessagesRepository(api, authorization) }
    }

    @Test fun `http and network failures stay errors and 401 clears adult session`() = runTest {
        for (failure in listOf(401, 403, 500, 502, 503, 504).map { code ->
            HttpException(Response.error<Any>(code, ResponseBody.create(MediaType.parse("application/json"), "{}")))
        } + listOf(IOException("offline"), SocketTimeoutException("timeout"))) {
            val credentials = Credentials()
            val repository = RemoteAdultMessagesRepository(object : AdultMessagesApi {
                override suspend fun history(profileId: String, authorization: String): AdultMessageHistory = throw failure
            }, AdultRequestAuthorization(credentials))
            val error = runCatching { repository.history("child") }.exceptionOrNull()
            when ((failure as? HttpException)?.code()) {
                401 -> { assertTrue(error is AdultAuthenticationRequiredException); assertNull(credentials.adultAccessToken.value) }
                403 -> assertTrue(error is AdultAuthorizationException)
                else -> assertSame(failure, error)
            }
        }
    }

    @Test fun `foreign profile and duplicate versions are rejected`() = runTest {
        val history = messageFixture()
        var response = history
        val repository = RemoteAdultMessagesRepository(object : AdultMessagesApi {
            override suspend fun history(profileId: String, authorization: String) = response
        }, AdultRequestAuthorization(Credentials()))
        assertTrue(runCatching { repository.history("other-profile") }.isFailure)
        response = history.copy(versions = listOf(history.versions.first(), history.versions.first()))
        assertTrue(runCatching { repository.history(history.versions.first().card.profileId) }.isFailure)
    }

    @Test fun `demo does not request backend and real mode returns only backend content`() = runTest {
        var calls = 0
        val response = messageFixture()
        val api = object : AdultMessagesApi {
            override suspend fun history(profileId: String, authorization: String): AdultMessageHistory { calls++; return response }
        }
        val authorization = AdultRequestAuthorization(Credentials())
        assertTrue(adultMessagesRepositoryForMode(true, api, authorization).history("demo").versions.isEmpty())
        assertEquals(0, calls)
        assertEquals(response, adultMessagesRepositoryForMode(false, api, authorization).history(response.versions.first().card.profileId))
        assertEquals(1, calls)
    }
}
