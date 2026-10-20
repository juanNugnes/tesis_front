package com.juan.habitos.data.remote

import com.juan.habitos.BuildConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AdultMessagesDemoModeTest {
    private val authorization = object : RequestAuthorization {
        override val principal = RequestPrincipal.ADULT
        override suspend fun <T> execute(request: suspend (String) -> T): T = request("Bearer adult")
    }

    @Test fun `independent messages flag requires debug and never changes global demo selection`() = runTest {
        for (globalDemo in listOf(false, true)) for (isDebug in listOf(false, true)) for (messagesDemo in listOf(false, true)) {
            var apiCalls = 0
            var mockCalls = 0
            val real = AdultMessageHistory(emptyList())
            val api = object : AdultMessagesApi {
                override suspend fun history(profileId: String, authorization: String): AdultMessageHistory {
                    apiCalls++
                    return real
                }
            }
            val repository = adultMessagesRepositoryForMode(globalDemo, api, authorization,
                adultMessagesDemo = messagesDemo, isDebug = isDebug,
                demoMessages = {
                    mockCalls++
                    AdultMessagesRepository { AdultMessageHistory(emptyList()) }
                })
            repository.history("child")

            val mockEnabled = isDebug && messagesDemo
            assertEquals(if (mockEnabled) 1 else 0, mockCalls)
            assertEquals(if (!mockEnabled && !globalDemo) 1 else 0, apiCalls)
        }
    }

    @Test fun `real errors never fall back to simulated history`() = runTest {
        var mockCalls = 0
        val offline = java.io.IOException("offline")
        val repository = adultMessagesRepositoryForMode(false, object : AdultMessagesApi {
            override suspend fun history(profileId: String, authorization: String): AdultMessageHistory = throw offline
        }, authorization, adultMessagesDemo = false, isDebug = true, demoMessages = {
            mockCalls++
            error("must not create mock")
        })
        assertSame(offline, runCatching { repository.history("child") }.exceptionOrNull())
        assertEquals(0, mockCalls)
    }

    @Test fun `compiled flag can only be enabled in debug`() {
        assertTrue(!BuildConfig.ADULT_MESSAGES_DEMO_MODE || BuildConfig.DEBUG)
    }
}
