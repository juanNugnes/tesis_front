package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.ActivitySelection
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
    var response = ActivitySuggestionsResponseDto(context = "voluntary", activities = emptyList())
    var selectionResponse = ActivitySelectionResponseDto()
    var countResponse = ActivitySelectionCountDto(0)
    var failure: Exception? = null
    var countFailure: Exception? = null
    val calls = mutableListOf<Pair<String, List<Any?>>>()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        return when (method.name) {
            "getActivitySuggestions" -> {
                calls += method.name to args.orEmpty().toList().dropLast(1)
                failure?.let { throw it }
                response
            }
            "createActivitySelection" -> {
                calls += method.name to args.orEmpty().toList().dropLast(1)
                failure?.let { throw it }
                selectionResponse
            }
            "getActivitySelectionCount" -> {
                calls += method.name to args.orEmpty().toList().dropLast(1)
                countFailure?.let { throw it }
                countResponse
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
            response = ActivitySuggestionsResponseDto("offer-1", "voluntary", (1..4).map(::suggestionDto))
        }
        val repository = repository(handler)

        val result = repository.suggestions()

        assertEquals("offer-1", result.offerId)
        assertEquals(listOf("activity-1", "activity-2", "activity-3"), result.activities.map { it.id })
        val args = handler.calls.single().second
        assertEquals("profile-1", args[0])
        assertEquals("Bearer device-jwt", args[1])
        assertEquals("voluntary", (args[2] as ActivitySuggestionsRequest).context)
    }

    @Test fun `response with one activity remains selectable`() = runTest {
        val handler = SuggestionApiHandler().apply {
            response = ActivitySuggestionsResponseDto("offer-1", "voluntary", listOf(suggestionDto(1)))
        }

        val result = repository(handler).suggestions()

        assertEquals(1, result.activities.size)
        assertEquals("Idea 1", result.activities.single().title)
    }

    @Test fun `empty response stays empty without fallback`() = runTest {
        val handler = SuggestionApiHandler()

        val result = repository(handler).suggestions()

        assertTrue(result.activities.isEmpty())
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

    @Test fun `selection uses current profile Device bearer and exact payload`() = runTest {
        val handler = SuggestionApiHandler()
        val selection = ActivitySelection(
            selectionId = "selection-1",
            offerId = "offer-1",
            activityId = "activity-2",
            selectedAtEpochMillis = 1_234_567_890L,
        )

        repository(handler).registerSelection(selection)

        val args = handler.calls.single().second
        assertEquals("profile-1", args[0])
        assertEquals("Bearer device-jwt", args[1])
        assertEquals(
            ActivitySelectionRequest("selection-1", "offer-1", "activity-2", 1_234_567_890L),
            args[2],
        )
    }

    @Test fun `selection count uses current profile Device bearer and exact day bounds`() = runTest {
        val handler = SuggestionApiHandler().apply { countResponse = ActivitySelectionCountDto(4) }

        val result = repository(handler).selectionCount(1_700_000_000_000L, 1_700_082_800_000L)

        assertEquals(4, result)
        val args = handler.calls.single().second
        assertEquals("profile-1", args[0])
        assertEquals("Bearer device-jwt", args[1])
        assertEquals(1_700_000_000_000L, args[2])
        assertEquals(1_700_082_800_000L, args[3])
    }

    @Test fun `selection count HTTP error is propagated`() = runTest {
        val handler = SuggestionApiHandler().apply { countFailure = suggestionHttpException(503) }
        var status: Int? = null

        try {
            repository(handler).selectionCount(10L, 20L)
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
