package com.juan.habitos.data.mock

import androidx.lifecycle.ViewModelStore
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import com.juan.habitos.ui.state.ActivityState
import com.juan.habitos.ui.state.ChildViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticActivitiesDemoIsolationTest {
    @Test fun `demo never changes voluntary history count usage preferences or repository calls`() = verifyIsolation(false)
    @Test fun `voluntary network failure remains visible and demo never becomes its fallback`() = verifyIsolation(true)

    private fun verifyIsolation(offline: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val initialProfile = Profile("Niño", "🦊", 9, sleepStart = "22:00", id = "child")
            val profiles = object : ProfileRepository {
                override val profile = MutableStateFlow<Profile?>(initialProfile)
                override val onboardingComplete = MutableStateFlow(true)
                override suspend fun save(profile: Profile) = error("No profile writes allowed")
                override suspend fun completeOnboarding() = error("No onboarding writes allowed")
                override suspend fun create(profile: Profile): Profile = error("No create allowed")
                override suspend fun update(profile: Profile): Profile = error("No update allowed")
                override suspend fun updateSettings(profile: Profile): Profile = error("No settings writes allowed")
                override suspend fun refreshSettings(): Profile? = error("No settings reads allowed")
                override suspend fun refresh(): Profile? = error("No additional profile requests allowed")
            }
            val selections = mutableListOf<ActivitySelection>()
            var reads = 0
            var counts = 0
            val activities = object : ActivitySuggestionRepository {
                override suspend fun suggestions(): ActivitySuggestionOffer {
                    reads++
                    if (offline) throw java.io.IOException("AWS unavailable")
                    return ActivitySuggestionOffer("real-offer", listOf(ActivitySuggestion("real-activity", "Real", "Backend", "CREATIVE", "LOW", false, false, false)))
                }
                override suspend fun registerSelection(selection: ActivitySelection) { selections += selection }
                override suspend fun selectionCount(startEpochMillis: Long, endEpochMillis: Long): Int { counts++; return 7 + selections.size }
            }
            var usageReads = 0
            val usage = object : UsageRepository {
                override suspend fun daily(period: DashboardPeriod): DailyUsage { usageReads++; return DailyUsage(123, sessionCount = 4) }
                override suspend fun apps() = emptyList<AppUsage>()
                override suspend fun recommendation(): Recommendation = error("No recommendations requested")
            }
            val child = ChildViewModel(profiles, usage, activities)
            store.put("child", child)
            child.refreshActivitySelectionCount()
            advanceUntilIdle()
            val before = child.state.value
            val demo = AutomaticActivitiesDemoViewModel("child")
            store.put("demo", demo)
            demo.enterExperience()
            val initialId = demo.state.value.offer!!.id
            demo.presented(initialId); demo.dismissDialog(initialId); demo.enterExperience(); demo.reopenDialog()
            for (scenario in AutomaticDemoScenario.entries) {
                demo.reset()
                demo.simulate(scenario)
                demo.state.value.offer?.let {
                    demo.presented(it.id); demo.accept(it.id); demo.start(it.id); demo.finish(it.id)
                }
            }
            demo.reset()
            repeat(2) {
                demo.simulate(AutomaticDemoScenario.DAYTIME)
                val id = demo.state.value.offer!!.id
                demo.presented(id); demo.reject(id)
            }
            advanceUntilIdle()
            assertEquals(before, child.state.value)
            assertEquals(initialProfile, profiles.profile.value)
            assertEquals(1, usageReads)
            assertEquals(1, reads)
            assertEquals(1, counts)
            assertTrue(selections.isEmpty())
            assertEquals(7, child.state.value.chosenActivities)
            if (offline) {
                assertTrue(child.state.value.activityState is ActivityState.Error)
            } else {
                child.startActivity()
                advanceUntilIdle()
                assertEquals("real-offer", selections.single().offerId)
                assertEquals("real-activity", selections.single().activityId)
                assertEquals(8, child.state.value.chosenActivities)
                assertTrue(demo.state.value.paused)
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
