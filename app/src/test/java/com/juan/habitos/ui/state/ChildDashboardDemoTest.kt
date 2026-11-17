package com.juan.habitos.ui.state

import com.juan.habitos.data.mock.dashboardDemoFixture
import com.juan.habitos.domain.model.ActivitySelection
import com.juan.habitos.domain.model.ActivitySuggestion
import com.juan.habitos.domain.model.ActivitySuggestionOffer
import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.domain.model.DailyUsage
import com.juan.habitos.domain.model.DashboardPeriod
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.Recommendation
import com.juan.habitos.domain.model.UsageMetricStatus
import com.juan.habitos.domain.model.UsageOverview
import com.juan.habitos.domain.repository.ActivitySuggestionRepository
import com.juan.habitos.domain.repository.ProfileRepository
import com.juan.habitos.domain.repository.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ChildDemoProfileRepository(initial: Profile) : ProfileRepository {
    override val profile = MutableStateFlow<Profile?>(initial)
    override val onboardingComplete = MutableStateFlow(true)
    var writes = 0
    override suspend fun save(profile: Profile) { writes++; this.profile.value = profile }
    override suspend fun completeOnboarding() = Unit
    override suspend fun create(profile: Profile) = profile.also { writes++ }
    override suspend fun refresh() = profile.value
    override suspend fun update(profile: Profile) = profile.also { writes++; this.profile.value = it }
    override suspend fun refreshSettings() = profile.value
    override suspend fun updateSettings(profile: Profile) = profile.also { writes++; this.profile.value = it }
}

private class ChildRealUsageRepository : UsageRepository {
    var overviewCalls = 0
    private val overview = UsageOverview(
        usage = DailyUsage(42, sessionCount = 3, maxSessionMinutes = 12),
        applications = listOf(AppUsage("Aplicación real", "com.example.real", 42)),
        applicationsStatus = UsageMetricStatus.MEASURED,
    )
    override suspend fun overview(period: DashboardPeriod): UsageOverview { overviewCalls++; return overview }
    override suspend fun daily(period: DashboardPeriod) = overview.usage
    override suspend fun apps() = overview.applications.orEmpty()
    override suspend fun recommendation() = Recommendation("", "", "")
}

private class ChildRealActivitiesRepository : ActivitySuggestionRepository {
    var suggestionCalls = 0
    override suspend fun suggestions(): ActivitySuggestionOffer {
        suggestionCalls++
        return ActivitySuggestionOffer(
            offerId = "backend-offer",
            activities = listOf(
                ActivitySuggestion("backend-activity", "Actividad del backend", "Descripción", "CREATIVE", "LOW", false, false, false),
            ),
        )
    }
    override suspend fun registerSelection(selection: ActivitySelection) = Unit
    override suspend fun selectionCount(startEpochMillis: Long, endEpochMillis: Long) = 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChildDashboardDemoTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `Santiago child presentation reuses the exact adult dashboard totals and applications`() = runTest(dispatcher) {
        val profiles = ChildDemoProfileRepository(Profile("Santiago", "🦊", 8, id = "profile-1"))
        val usage = ChildRealUsageRepository()
        val activities = ChildRealActivitiesRepository()
        val viewModel = ChildViewModel(profiles, usage, activities, dashboardDemo = true)
        advanceUntilIdle()

        val adultScenario = dashboardDemoFixture.overview(DashboardPeriod.TODAY)
        assertTrue(viewModel.state.value.dashboardDemoActive)
        assertEquals(adultScenario.usage.currentMinutes, viewModel.state.value.usage.currentMinutes)
        assertEquals(140, viewModel.state.value.usage.currentMinutes)
        assertEquals(
            adultScenario.applications.orEmpty().map { it.name to it.minutes },
            viewModel.state.value.apps.orEmpty().map { it.name to it.minutes },
        )
        assertEquals(140, viewModel.state.value.apps.orEmpty().sumOf { it.minutes })
        assertEquals(1, usage.overviewCalls)
        assertEquals(0, profiles.writes)
        assertEquals(1, activities.suggestionCalls)
        assertEquals("Actividad del backend", (viewModel.state.value.activityState as ActivityState.Suggested).activity.title)
    }

    @Test fun `non matching profile keeps real child usage and applications`() = runTest(dispatcher) {
        val viewModel = ChildViewModel(
            ChildDemoProfileRepository(Profile("Perfil real", "🐼", 8, id = "profile-real")),
            ChildRealUsageRepository(),
            ChildRealActivitiesRepository(),
            dashboardDemo = true,
        )
        advanceUntilIdle()

        assertFalse(viewModel.state.value.dashboardDemoActive)
        assertEquals(42, viewModel.state.value.usage.currentMinutes)
        assertEquals(listOf("Aplicación real" to 42), viewModel.state.value.apps.orEmpty().map { it.name to it.minutes })
    }

    @Test fun `disabled mode preserves real child behavior for Santiago`() = runTest(dispatcher) {
        val viewModel = ChildViewModel(
            ChildDemoProfileRepository(Profile("Santiago", "🦊", 8, id = "profile-1")),
            ChildRealUsageRepository(),
            ChildRealActivitiesRepository(),
            dashboardDemo = false,
        )
        advanceUntilIdle()

        assertFalse(viewModel.state.value.dashboardDemoActive)
        assertEquals(42, viewModel.state.value.usage.currentMinutes)
        assertEquals(listOf("Aplicación real" to 42), viewModel.state.value.apps.orEmpty().map { it.name to it.minutes })
    }
}
