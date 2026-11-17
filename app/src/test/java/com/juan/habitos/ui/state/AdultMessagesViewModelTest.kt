package com.juan.habitos.ui.state

import com.juan.habitos.data.remote.*
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AdultMessagesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Profiles : ProfileRepository {
        override val profile = MutableStateFlow<Profile?>(Profile("Child", "fox", 9, id = "one"))
        override val onboardingComplete = MutableStateFlow(true)
        override suspend fun save(profile: Profile) { this.profile.value = profile }
        override suspend fun completeOnboarding() = Unit
        override suspend fun create(profile: Profile) = profile
        override suspend fun refresh() = profile.value
        override suspend fun update(profile: Profile) = profile
        override suspend fun refreshSettings() = profile.value
        override suspend fun updateSettings(profile: Profile) = profile
    }

    @Test fun `profile change clears messages while loading and after failed request`() = runTest(dispatcher) {
        val profiles = Profiles()
        val second = CompletableDeferred<AdultMessageHistory>()
        val repository = AdultMessagesRepository { id ->
            if (id == "one") messageFixture() else second.await()
        }
        val vm = AdultMessagesViewModel(profiles, repository)
        advanceUntilIdle()
        assertNotNull(vm.state.value.history)
        profiles.profile.value = profiles.profile.value!!.copy(id = "two")
        runCurrent()
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.history)
        second.completeExceptionally(java.io.IOException("offline"))
        advanceUntilIdle()
        assertEquals("two", vm.state.value.profileId)
        assertNull(vm.state.value.history)
        assertNotNull(vm.state.value.error)
        profiles.profile.value = null
        advanceUntilIdle()
        assertNull(vm.state.value.profileId)
        assertNull(vm.state.value.history)
    }

    @Test fun `obsolete request cannot replace newly selected profile`() = runTest(dispatcher) {
        val profiles = Profiles()
        val pending = CompletableDeferred<AdultMessageHistory>()
        val vm = AdultMessagesViewModel(profiles, AdultMessagesRepository { id ->
            if (id == "one") pending.await() else AdultMessageHistory(emptyList())
        })
        runCurrent()
        profiles.profile.value = profiles.profile.value!!.copy(id = "two")
        advanceUntilIdle()
        pending.complete(messageFixture())
        advanceUntilIdle()
        assertEquals("two", vm.state.value.profileId)
        assertTrue(vm.state.value.history!!.versions.isEmpty())
    }

    @Test fun `retry loads again and demo status is explicit`() = runTest(dispatcher) {
        var calls = 0
        val vm = AdultMessagesViewModel(Profiles(), AdultMessagesRepository {
            if (++calls == 1) throw java.net.SocketTimeoutException()
            AdultMessageHistory(emptyList())
        }, demo = true)
        advanceUntilIdle()
        assertTrue(vm.state.value.retryable)
        assertTrue(vm.state.value.demo)
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.history!!.versions.isEmpty())
    }

    @Test fun `authentication and authorization failures provide distinct non retry states`() = runTest(dispatcher) {
        for (failure in listOf(AdultAuthenticationRequiredException(), AdultAuthorizationException())) {
            val vm = AdultMessagesViewModel(Profiles(), AdultMessagesRepository { throw failure })
            advanceUntilIdle()
            assertFalse(vm.state.value.retryable)
            assertNotNull(vm.state.value.error)
            assertNull(vm.state.value.history)
        }
    }

    @Test fun `dashboard presentation predicate suppresses remote history without changing authentication`() = runTest(dispatcher) {
        val profiles = Profiles().apply {
            profile.value = Profile("Santiago", "fox", 8, id = "demo-profile")
        }
        var calls = 0
        val vm = AdultMessagesViewModel(
            profiles,
            AdultMessagesRepository { calls++; messageFixture() },
            suppressRemoteForProfile = { it.name == "Santiago" && it.age == 8 },
        )

        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals("demo-profile", vm.state.value.profileId)
        assertNull(vm.state.value.history)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.authenticationRequired)

        profiles.profile.value = Profile("Perfil real", "fox", 8, id = "real-profile")
        advanceUntilIdle()
        assertEquals(1, calls)
        assertNotNull(vm.state.value.history)
    }

    @Test fun `service retry policy respects backend detail`() {
        fun failure(body: String) = retrofit2.HttpException(retrofit2.Response.error<Any>(
            503, okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"), body),
        ))
        assertFalse(adultMessageRetryable(failure("""{"detail":{"code":"contract","retryable":false}}""")))
        assertTrue(adultMessageRetryable(failure("""{"detail":{"code":"offline","retryable":true}}""")))
        assertTrue(adultMessageRetryable(failure("{}")))
        assertFalse(adultMessageRetryable(IllegalArgumentException("foreign profile")))
    }
}
