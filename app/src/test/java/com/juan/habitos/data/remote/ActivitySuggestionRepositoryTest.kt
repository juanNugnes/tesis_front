package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

private class SuggestionProfileStore : ProfileLocalStore {
    override val profile = MutableStateFlow<Profile?>(Profile("Sofía", "🦊", 9, id = "profile-1"))
    override val onboardingComplete = MutableStateFlow(true)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class SuggestionApiHandler : InvocationHandler {
    var response = ActivitySuggestionsResponseDto("voluntary", emptyList())
    var failure: Exception? = null
    val calls = mutableListOf<List<Any?>>()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        return when (method.name) {
            "getActivitySuggestions" -> {
                calls += args.orEmpty().toList().dropLast(1)
                failure?.let { throw it }
                response
            }
            "toString" -> "SuggestionApi"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected API call: ${method.name}")
        }
    }

    fun api(): HabitsApi = Proxy.newProxyInstance(
        HabitsApi::class.java.classLoader,
        arrayOf(HabitsApi::class.java),
        this,
    ) as HabitsApi
}

private fun suggestionDto(index: Int) = ActivitySuggestionDto(
    activityId = "activity-$index",
    title = "Idea $index",
    description = "Descripción $index",
    category = "CREATIVE",
    activityLevel = "LOW",
    bedtimeSuitable = false,
    requiresOtherPerson = false,
    mayRequireDeviceInteraction = false,
)

@Suppress("DEPRECATION")
private fun suggestionHttpException(code: Int) = HttpException(
    Response.error<Any>(code, ResponseBody.create(MediaType.parse("application/json"), "{}")),
)

class ActivitySuggestionRepositoryTest {
    @Test fun `response with activities uses current profile Device bearer voluntary context and keeps at most three`() = runTest {
        val handler = SuggestionApiHandler().apply {
            response = ActivitySuggestionsResponseDto("voluntary", (1..4).map(::suggestionDto))
        }
        val repository = repository(handler)

        val result = repository.suggestions()

        assertEquals(listOf("activity-1", "activity-2", "activity-3"), result.map { it.id })
        val args = handler.calls.single()
        assertEquals("profile-1", args[0])
        assertEquals("Bearer device-jwt", args[1])
        assertEquals("voluntary", (args[2] as ActivitySuggestionsRequest).context)
    }

    @Test fun `response with one activity remains selectable`() = runTest {
        val handler = SuggestionApiHandler().apply {
            response = ActivitySuggestionsResponseDto("voluntary", listOf(suggestionDto(1)))
        }

        val result = repository(handler).suggestions()

        assertEquals(1, result.size)
        assertEquals("Idea 1", result.single().title)
    }

    @Test fun `empty response stays empty without fallback`() = runTest {
        val handler = SuggestionApiHandler()

        val result = repository(handler).suggestions()

        assertTrue(result.isEmpty())
    }

    @Test fun `HTTP error is propagated instead of returning hardcoded activities`() = runTest {
        val handler = SuggestionApiHandler().apply { failure = suggestionHttpException(503) }
        var status: Int? = null

        try {
            repository(handler).suggestions()
        } catch (exception: HttpException) {
            status = exception.code()
        }

        assertEquals(503, status)
    }

    private fun repository(handler: SuggestionApiHandler) = RemoteActivitySuggestionRepository(
        profileStore = SuggestionProfileStore(),
        api = handler.api(),
        authorization = DeviceRequestAuthorization(
            TestDeviceCredentialStore(DeviceCredentials(accessToken = "device-jwt"))
        ),
    )
}
