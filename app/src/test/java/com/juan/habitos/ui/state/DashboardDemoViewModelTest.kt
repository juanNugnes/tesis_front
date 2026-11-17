package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.Alert
import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.domain.model.DailyUsage
import com.juan.habitos.domain.model.DashboardPeriod
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.Recommendation
import com.juan.habitos.domain.model.UsageMetricStatus
import com.juan.habitos.domain.model.UsageOverview
import com.juan.habitos.domain.repository.AlertRepository
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

private class DashboardDemoProfileRepository(initial: Profile) : ProfileRepository {
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

private class DashboardRealUsageRepository : UsageRepository {
    var overviewCalls = 0
    private val realOverview = UsageOverview(
        usage = DailyUsage(42, sessionCount = 3, maxSessionMinutes = 12),
        applications = listOf(AppUsage("Real", "com.example.real", 42)),
        applicationsStatus = UsageMetricStatus.MEASURED,
    )
    override suspend fun overview(period: DashboardPeriod): UsageOverview { overviewCalls++; return realOverview }
    override suspend fun daily(period: DashboardPeriod) = realOverview.usage
    override suspend fun apps() = realOverview.applications.orEmpty()
    override suspend fun recommendation() = Recommendation("", "", "")
}

private object DashboardEmptyAlerts : AlertRepository {
    override suspend fun alerts(): List<Alert> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardDemoViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `matching demo profile uses fixture without reading or writing real usage`() = runTest(dispatcher) {
        val profiles = DashboardDemoProfileRepository(Profile("Santiago", "🦊", 8, id = "profile-1"))
        val realUsage = DashboardRealUsageRepository()
        val viewModel = AdultViewModel(profiles, realUsage, DashboardEmptyAlerts, dashboardDemo = true)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.dashboardDemoRequested)
        assertTrue(viewModel.state.value.dashboardDemoActive)
        assertEquals("Santiago", viewModel.state.value.profile.name)
        assertEquals(140, viewModel.state.value.usage.currentMinutes)
        assertEquals(8, viewModel.state.value.usage.sessionCount)
        assertEquals(35, viewModel.state.value.usage.maxSessionMinutes)
        assertEquals(140, viewModel.state.value.appsTab.applications.sumOf { it.minutes })
        assertEquals("22:30", viewModel.state.value.nightTab.lastActivity)
        assertEquals(0, realUsage.overviewCalls)
        assertEquals(0, profiles.writes)

        viewModel.period(DashboardPeriod.WEEK)
        advanceUntilIdle()
        assertEquals(980, viewModel.state.value.usage.currentMinutes)
        assertEquals(56, viewModel.state.value.usage.sessionCount)
        assertEquals(40, viewModel.state.value.usage.maxSessionMinutes)
        assertEquals(0, realUsage.overviewCalls)
    }

    @Test fun `demo analysis computes differences and percentages from the fixture`() = runTest(dispatcher) {
        val viewModel = AdultViewModel(
            DashboardDemoProfileRepository(Profile("Santiago", "🦊", 8, id = "profile-1")),
            DashboardRealUsageRepository(),
            DashboardEmptyAlerts,
            dashboardDemo = true,
        )
        advanceUntilIdle()

        val comparisons = viewModel.state.value.attentionTab.comparisons
        assertEquals("100 → 140 min/día", comparisons[0].value)
        assertEquals("+40 min/día (+40 %)", comparisons[0].detail)
        assertEquals("6 → 8 por día", comparisons[1].value)
        assertEquals("+2 (+33 %)", comparisons[1].detail)
        assertEquals("10 → 20 min/noche", comparisons[2].value)
        assertEquals("+10 min (+100 %)", comparisons[2].detail)
        assertFalse(comparisons.flatMap { listOfNotNull(it.value, it.detail) }.any { it.contains("+28 %") || it.contains("+18 %") })
    }

    @Test fun `different real profile is never renamed or overlaid with fixture data`() = runTest(dispatcher) {
        val profiles = DashboardDemoProfileRepository(Profile("Perfil real", "🐼", 8, id = "profile-real"))
        val realUsage = DashboardRealUsageRepository()
        val viewModel = AdultViewModel(profiles, realUsage, DashboardEmptyAlerts, dashboardDemo = true)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.dashboardDemoRequested)
        assertFalse(viewModel.state.value.dashboardDemoActive)
        assertEquals("Perfil real", viewModel.state.value.profile.name)
        assertEquals(42, viewModel.state.value.usage.currentMinutes)
        assertEquals(1, realUsage.overviewCalls)
        assertEquals(0, profiles.writes)
    }

    @Test fun `disabled dashboard demo preserves repository behavior even for Santiago`() = runTest(dispatcher) {
        val realUsage = DashboardRealUsageRepository()
        val viewModel = AdultViewModel(
            DashboardDemoProfileRepository(Profile("Santiago", "🦊", 8, id = "profile-1")),
            realUsage,
            DashboardEmptyAlerts,
            dashboardDemo = false,
        )
        advanceUntilIdle()

        assertFalse(viewModel.state.value.dashboardDemoRequested)
        assertFalse(viewModel.state.value.dashboardDemoActive)
        assertEquals(42, viewModel.state.value.usage.currentMinutes)
        assertEquals(1, realUsage.overviewCalls)
    }

    @Test fun `dashboard demo provides a local fictitious message and bypasses remote loading decision`() {
        val profile = Profile("Santiago", "🦊", 8, id = "profile-1")
        val state = dashboardDemoMessagesState(profile)

        assertTrue(state.demo)
        assertTrue(state.localOnly)
        assertEquals("Ejemplo ficticio", state.demoLabel)
        assertEquals(1, state.current.size)
        assertEquals("Más uso registrado en este teléfono", state.current.single().card.title)
        assertEquals("NOT_DELIVERED", state.current.single().card.deliveryStatus)
        assertEquals(null, state.error)
        assertFalse(state.authenticationRequired)
        assertFalse(shouldLoadRemoteAdultMessages(profile, dashboardDemoActive = true))
        assertTrue(shouldLoadRemoteAdultMessages(profile, dashboardDemoActive = false))
        assertFalse(shouldLoadRemoteAdultMessages(null, dashboardDemoActive = false))
    }
}
